
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
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

        private const val TAG = "JARVIS"
        private const val SAMPLE_RATE = 16000

        private const val KWS_MODEL_DIR =
            "kws/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"

        private const val ASR_MODEL_DIR =
            "asr/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    }

    private var keywordSpotter: KeywordSpotter? = null
    private var wakeStream: OnlineStream? = null

    private var commandRecognizer: OnlineRecognizer? = null

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var tts: TextToSpeech? = null

    @Volatile
    private var isListening = false

    private var lastWakeTime = 0L

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("JARVIS is active")
                .setContentText("Listening for the wake word")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        startForeground(NOTIFICATION_ID, notification)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Microphone permission is missing")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isListening) {
            try {
                initializeKeywordSpotter()
                initializeCommandRecognizer()
                startListening()
            } catch (e: Exception) {
                Log.e(TAG, "Could not initialize JARVIS", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
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
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80
            ),
            modelConfig = modelConfig,
            keywordsFile = "$KWS_MODEL_DIR/keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f
        )

        keywordSpotter = KeywordSpotter(
            assetManager = assets,
            config = config
        )

        wakeStream = keywordSpotter!!.createStream()

        Log.i(TAG, "Offline wake-word model loaded")
    }

    private fun initializeCommandRecognizer() {

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$ASR_MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx",
                decoder = "$ASR_MODEL_DIR/decoder-epoch-99-avg-1.onnx",
                joiner = "$ASR_MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
            ),
            tokens = "$ASR_MODEL_DIR/tokens.txt",
            numThreads = 2,
            provider = "cpu",
            modelType = "zipformer"
        )

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80
            ),
            modelConfig = modelConfig,
            endpointConfig = EndpointConfig(),
            enableEndpoint = true
        )

        commandRecognizer = OnlineRecognizer(
            assetManager = assets,
            config = config
        )

        Log.i(TAG, "Offline English command model loaded")
    }

    private fun startListening() {

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize <= 0) {
            throw IllegalStateException("Invalid microphone buffer size")
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Microphone initialization failed")
        }

        audioRecord!!.startRecording()
        isListening = true

        recordingThread = thread(
            start = true,
            name = "JarvisWakeWord"
        ) {
            processMicrophone()
        }

        Log.i(TAG, "Listening for JARVIS")
    }

    private fun processMicrophone() {

        val recorder = audioRecord ?: return
        val kws = keywordSpotter ?: return
        val currentStream = wakeStream ?: return

        val buffer = ShortArray(1600)

        try {
            while (isListening) {

                val count = recorder.read(
                    buffer,
                    0,
                    buffer.size
                )

                if (count > 0) {

                    val samples = FloatArray(count) {
                        buffer[it] / 32768.0f
                    }

                    currentStream.acceptWaveform(
                        samples,
                        sampleRate = SAMPLE_RATE
                    )

                    while (kws.isReady(currentStream)) {

                        kws.decode(currentStream)

                        val detected =
                            kws.getResult(currentStream).keyword

                        if (detected.isNotBlank()) {

                            kws.reset(currentStream)
                            onWakeWordDetected(detected)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isListening) {
                Log.e(TAG, "Microphone processing failed", e)
            }
        }

        Log.i(TAG, "Wake-word listening stopped")
    }

    private fun onWakeWordDetected(keyword: String) {

        val now = SystemClock.elapsedRealtime()

        if (now - lastWakeTime < 4000) {
            return
        }

        lastWakeTime = now

        Log.i(TAG, "Wake word detected: $keyword")

        Handler(Looper.getMainLooper()).post {
            tts?.speak(
                "Yes, sir?",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "jarvis_wake"
            )
        }
    }

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {

            tts?.language = Locale.US

            val maleVoice = tts?.voices?.firstOrNull {
                it.locale.language == "en" &&
                    it.name.contains("male", ignoreCase = true)
            }

            if (maleVoice != null) {
                tts?.voice = maleVoice
            }

            Log.i(TAG, "Voice system initialized")

        } else {
            Log.e(TAG, "Text-to-speech initialization failed")
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Background Service",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {

        isListening = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            recordingThread?.join(500)
        } catch (_: InterruptedException) {
        }

        audioRecord?.release()
        audioRecord = null

        wakeStream?.release()
        wakeStream = null

        keywordSpotter?.release()
        keywordSpotter = null

        commandRecognizer?.release()
        commandRecognizer = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        super.onDestroy()
    }
}
