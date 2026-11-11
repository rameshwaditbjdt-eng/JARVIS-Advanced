package com.jarvis.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.Locale
import kotlin.concurrent.thread

class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "jarvis_background"
        const val NOTIFICATION_ID = 101

        @Volatile
        var isRunning = false
            private set

        private const val TAG = "JARVIS"
        private const val SAMPLE_RATE = 16000
        private const val SLEEP_TIMEOUT = 30000L

        private const val KWS_MODEL_DIR =
            "kws/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        private const val ASR_MODEL_DIR =
            "asr/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    }

    private enum class ListeningMode { WAKE_WORD, SPEAKING, COMMAND }

    @Volatile private var mode = ListeningMode.WAKE_WORD
    @Volatile private var nextMode = ListeningMode.COMMAND
    @Volatile private var isListening = false
    @Volatile private var ttsReady = false

    private var keywordSpotter: KeywordSpotter? = null
    private var wakeStream: OnlineStream? = null
    private var commandRecognizer: OnlineRecognizer? = null
    private var commandStream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordingThread: Thread? = null
    private var tts: TextToSpeech? = null
    private var pendingSpeech: Pair<String, ListeningMode>? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    @Volatile private var initializationStarted = false
    @Volatile private var serviceDestroyed = false
    private var initializationThread: Thread? = null

    @Volatile private var lastWakeTime = 0L
    @Volatile private var lastCommandActivity = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("JARVIS is starting")
                .setContentText("Loading offline voice models")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        startForeground(NOTIFICATION_ID, notification)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Microphone permission is missing")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!initializationStarted && !isListening) {
            initializationStarted = true
            initializationThread = thread(start = true, name = "JarvisModelInit") {
                try {
                    acquireCpuWakeLock()
                    updateNotification("Loading wake-word model")
                    initializeKeywordSpotter()

                    if (serviceDestroyed) return@thread

                    updateNotification("Loading offline speech recognition")
                    initializeCommandRecognizer()

                    if (serviceDestroyed) return@thread

                    startListening()
                } catch (e: Exception) {
                    Log.e(TAG, "JARVIS initialization failed", e)
                    updateNotification("Startup failed — check microphone and model logs")
                    releaseCpuWakeLock()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun acquireCpuWakeLock() {
        if (cpuWakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        cpuWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JARVIS:MicrophoneProcessing"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseCpuWakeLock() {
        try {
            cpuWakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release CPU wake lock cleanly", e)
        } finally {
            cpuWakeLock = null
        }
    }

    private fun initializeKeywordSpotter() {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$KWS_MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                decoder = "$KWS_MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                joiner = "$KWS_MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
            ),
            tokens = "$KWS_MODEL_DIR/tokens.txt",
            numThreads = 2,
            provider = "cpu",
            modelType = "zipformer2"
        )
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            keywordsFile = "$KWS_MODEL_DIR/keywords.txt",
            // Improve recall for the short custom wake word.
            keywordsScore = 3.0f,
            keywordsThreshold = 0.05f
        )
        keywordSpotter = KeywordSpotter(assetManager = assets, config = config)
        wakeStream = keywordSpotter!!.createStream()
        Log.i(TAG, "Wake-word model loaded")
    }

    private fun initializeCommandRecognizer() {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$ASR_MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx",
                decoder = "$ASR_MODEL_DIR/decoder-epoch-99-avg-1.int8.onnx",
                joiner = "$ASR_MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
            ),
            tokens = "$ASR_MODEL_DIR/tokens.txt",
            numThreads = 2,
            provider = "cpu",
            modelType = "zipformer"
        )
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            enableEndpoint = true
        )
        commandRecognizer = OnlineRecognizer(assetManager = assets, config = config)
        commandStream = commandRecognizer!!.createStream()
        Log.i(TAG, "Offline command recognizer loaded")
    }

    private fun startListening() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) throw IllegalStateException("Invalid microphone buffer size")

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Microphone initialization failed")
        }

        // Enable Android's microphone gain control and noise reduction when
        // the device supports them. These effects process captured audio
        // before it reaches the offline wake-word and command models.
        val audioSessionId = audioRecord!!.audioSessionId
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
            Log.i(
                TAG,
                "Audio effects: AGC=" + (automaticGainControl?.enabled == true) +
                    ", noise suppression=" + (noiseSuppressor?.enabled == true)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Optional microphone effects could not be enabled", e)
        }

        audioRecord!!.startRecording()
        isListening = true
        lastCommandActivity = SystemClock.elapsedRealtime()
        recordingThread = thread(start = true, name = "JarvisAudio") { processMicrophone() }
        updateNotification("Listening for JARVIS")
        Log.i(TAG, "Microphone listening started")
    }

    private fun processMicrophone() {
        val recorder = audioRecord ?: return
        val buffer = ShortArray(1600)
        try {
            while (isListening) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val samples = FloatArray(count) { buffer[it] / 32768.0f }
                when (mode) {
                    ListeningMode.WAKE_WORD -> processWakeWord(samples)
                    ListeningMode.COMMAND -> processCommand(samples)
                    ListeningMode.SPEAKING -> Unit
                }
                checkSleepTimeout()
            }
        } catch (e: Exception) {
            if (isListening) Log.e(TAG, "Audio processing failed", e)
        }
        Log.i(TAG, "Microphone processing stopped")
    }

    private fun processWakeWord(samples: FloatArray) {
        val kws = keywordSpotter ?: return
        val stream = wakeStream ?: return
        // Apply a gentle software gain before wake-word decoding. Clamp samples
        // to avoid clipping; this can help with quieter speech, but may also
        // make background noise more noticeable.
        val boostedSamples = FloatArray(samples.size) {
            (samples[it] * 1.5f).coerceIn(-1.0f, 1.0f)
        }
        stream.acceptWaveform(boostedSamples, sampleRate = SAMPLE_RATE)
        while (kws.isReady(stream)) {
            kws.decode(stream)
            val detected = kws.getResult(stream).keyword
            if (detected.isNotBlank()) {
                kws.reset(stream)
                onWakeWordDetected(detected)
                return
            }
        }
    }

    private fun onWakeWordDetected(keyword: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeTime < 4000) return
        lastWakeTime = now
        Log.i(TAG, "Wake word detected: $keyword")
        updateNotification("Wake word detected — responding")
        lastCommandActivity = now
        speakThen("Yes, sir?", ListeningMode.COMMAND)
    }

    private fun processCommand(samples: FloatArray) {
        val recognizer = commandRecognizer ?: return
        val stream = commandStream ?: return
        stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)

        if (recognizer.isEndpoint(stream)) {
            val result = recognizer.getResult(stream).text.trim()
            recognizer.reset(stream)
            if (result.isNotBlank()) {
                // Only recognized speech resets the timer; room noise no longer keeps JARVIS awake.
                lastCommandActivity = SystemClock.elapsedRealtime()
                Log.i(TAG, "Recognized command: $result")
                handleCommand(result)
            }
        }
    }

    private fun checkSleepTimeout() {
        if (mode != ListeningMode.COMMAND) return
        if (SystemClock.elapsedRealtime() - lastCommandActivity >= SLEEP_TIMEOUT) {
            Log.i(TAG, "Returning to wake-word standby after silence")
            commandStream?.let { stream -> commandRecognizer?.reset(stream) }
            speakThen("I'll be here when you need me, sir.", ListeningMode.WAKE_WORD)
        }
    }

    private fun handleCommand(command: String) {
        val text = command.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        Log.i(TAG, "Processing: $text")

        if (text.contains("go to sleep") || text.contains("sleep now") || text == "sleep") {
            speakThen("Going to sleep, sir.", ListeningMode.WAKE_WORD)
            return
        }
        if (text.contains("open youtube") || text.contains("launch youtube") || text.contains("start youtube")) {
            openApp("com.google.android.youtube", "YouTube"); return
        }
        if (text.contains("open whatsapp") || text.contains("launch whatsapp") || text.contains("start whatsapp")) {
            openApp("com.whatsapp", "WhatsApp"); return
        }
        if (text.contains("open chrome") || text.contains("launch chrome") || text.contains("start chrome")) {
            openApp("com.android.chrome", "Chrome"); return
        }
        if (text.contains("open calculator") || text.contains("launch calculator") || text.contains("start calculator")) {
            openCalculator(); return
        }

        val accessibilityCommand = when {
            text == "scroll down" || text == "scroll" -> "scroll down"
            text == "scroll up" -> "scroll up"
            text == "go back" || text == "press back" -> "go back"
            text == "go home" || text == "press home" -> "go home"
            text == "show recent apps" || text == "open recent apps" -> "show recent apps"
            text.startsWith("tap ") || text.startsWith("click ") || text.startsWith("type ") -> command
            else -> null
        }

        if (accessibilityCommand != null) {
            if (!JarvisAccessibilityService.isEnabled()) {
                speakThen(
                    "App control is not enabled yet. Please enable JARVIS in Accessibility settings.",
                    ListeningMode.COMMAND
                )
            } else if (JarvisAccessibilityService.perform(accessibilityCommand)) {
                speakThen("Done, sir.", ListeningMode.COMMAND)
            } else {
                speakThen("I couldn't complete that action, sir.", ListeningMode.COMMAND)
            }
            return
        }

        speakThen("I heard you say $command. I don't know that command yet, sir.", ListeningMode.COMMAND)
    }

    private fun openApp(packageName: String, appName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                speakThen("$appName is not installed, sir.", ListeningMode.COMMAND)
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            speakThen("Opening $appName, sir.", ListeningMode.COMMAND)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open $appName", e)
            speakThen("I couldn't open $appName, sir.", ListeningMode.COMMAND)
        }
    }

    private fun openCalculator() {
        val packages = listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator")
        for (packageName in packages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: continue
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                speakThen("Opening Calculator, sir.", ListeningMode.COMMAND)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Calculator launch failed", e)
            }
        }
        speakThen("I couldn't find Calculator, sir.", ListeningMode.COMMAND)
    }

    private fun speakThen(message: String, afterSpeech: ListeningMode) {
        nextMode = afterSpeech
        mode = ListeningMode.SPEAKING
        if (!ttsReady || tts == null) {
            pendingSpeech = message to afterSpeech
            updateNotification("Wake word heard — preparing voice")
            return
        }
        speakNow(message, afterSpeech)
    }

    private fun speakNow(message: String, afterSpeech: ListeningMode) {
        nextMode = afterSpeech
        mode = ListeningMode.SPEAKING
        val result = tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "jarvis_${SystemClock.elapsedRealtime()}"
        )
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Text-to-speech rejected utterance")
            mode = afterSpeech
            lastCommandActivity = SystemClock.elapsedRealtime()
            updateNotification("Voice output failed — listening")
        }
    }

    private fun resumeListeningAfterSpeech() {
        if (nextMode == ListeningMode.WAKE_WORD) {
            // Recreate the KWS stream so the next wake word starts cleanly.
            try {
                wakeStream?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Could not release previous wake-word stream", e)
            }
            wakeStream = keywordSpotter?.createStream()
            mode = ListeningMode.WAKE_WORD
            Log.i(TAG, "Wake-word listener re-armed")
        } else {
            // Reset the command stream and listen for the user's sentence
            // immediately after JARVIS finishes saying "Yes, sir?"
            commandStream?.let { stream -> commandRecognizer?.reset(stream) }
            mode = ListeningMode.COMMAND
            lastCommandActivity = SystemClock.elapsedRealtime()
            Log.i(TAG, "Command listener active")
        }
    }

    private fun updateNotification(status: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS is active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            // Android does not expose a standard gender field for TTS voices.
            // Prefer a voice explicitly named male when the installed engine provides one.
            val maleVoice = tts?.voices?.firstOrNull {
                it.locale.language == "en" && it.name.contains("male", ignoreCase = true)
            }
            if (maleVoice != null) tts?.voice = maleVoice

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    resumeListeningAfterSpeech()
                    lastCommandActivity = SystemClock.elapsedRealtime()
                    updateNotification(
                        if (mode == ListeningMode.COMMAND) "Listening for your command"
                        else "Listening for JARVIS"
                    )
                }
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "Text-to-speech utterance failed")
                    resumeListeningAfterSpeech()
                    lastCommandActivity = SystemClock.elapsedRealtime()
                    updateNotification("Voice output failed — listening")
                }
            })
            ttsReady = true
            Log.i(TAG, "Voice system initialized")
            val queued = pendingSpeech
            pendingSpeech = null
            if (queued != null) speakNow(queued.first, queued.second)
        } else {
            Log.e(TAG, "Text-to-speech initialization failed: status=$status")
            pendingSpeech = null
            mode = nextMode
            updateNotification("Voice engine unavailable")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "JARVIS Background Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceDestroyed = true
        isRunning = false
        isListening = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { recordingThread?.join(1500) } catch (_: InterruptedException) { }
        audioRecord?.release()
        audioRecord = null
        try { automaticGainControl?.release() } catch (_: Exception) { }
        automaticGainControl = null
        try { noiseSuppressor?.release() } catch (_: Exception) { }
        noiseSuppressor = null
        wakeStream?.release()
        wakeStream = null
        keywordSpotter?.release()
        keywordSpotter = null
        commandStream?.release()
        commandStream = null
        commandRecognizer?.release()
        commandRecognizer = null
        releaseCpuWakeLock()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
