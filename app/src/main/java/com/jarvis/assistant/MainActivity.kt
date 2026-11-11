
package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var tts: TextToSpeech
    private val speechRequestCode = 100
    private val permissionRequestCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 60, 40, 40)

        val title = TextView(this)
        title.text = "J.A.R.V.I.S"
        title.textSize = 30f
        layout.addView(title)

        statusText = TextView(this)
        statusText.text = "SYSTEM OFFLINE"
        statusText.textSize = 18f
        layout.addView(statusText)

        val startButton = Button(this)
        startButton.text = "START JARVIS"
        layout.addView(startButton)

        val listenButton = Button(this)
        listenButton.text = "TALK TO JARVIS"
        layout.addView(listenButton)

        val stopButton = Button(this)
        stopButton.text = "STOP JARVIS"
        layout.addView(stopButton)

        startButton.setOnClickListener {
            statusText.text = "JARVIS READY"
            speak("Hello. JARVIS is ready. You can talk to me.")
        }

        listenButton.setOnClickListener {
            checkPermissionAndListen()
        }

        stopButton.setOnClickListener {
            statusText.text = "SYSTEM OFFLINE"
            speak("JARVIS shutting down.")
        }

        setContentView(layout)
    }

    private fun checkPermissionAndListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                permissionRequestCode
            )
        } else {
            startListening()
        }
    }

    private fun startListening() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")

            startActivityForResult(intent, speechRequestCode)
            statusText.text = "LISTENING..."
        } catch (e: Exception) {
            statusText.text = "SPEECH RECOGNITION UNAVAILABLE"
            Toast.makeText(
                this,
                "Speech recognition is not available on this phone.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == speechRequestCode && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS
            )

            val spokenText = results?.firstOrNull()

            if (!spokenText.isNullOrBlank()) {
                statusText.text = "YOU: $spokenText"
                respondToUser(spokenText)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == permissionRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (requestCode == permissionRequestCode) {
            statusText.text = "MICROPHONE PERMISSION DENIED"
        }
    }

    private fun respondToUser(message: String) {
        val reply = when {
            message.contains("hello", ignoreCase = true) ->
                "Hello. It is good to hear from you."

            message.contains("your name", ignoreCase = true) ->
                "I am JARVIS, your personal assistant."

            message.contains("how are you", ignoreCase = true) ->
                "I am functioning perfectly. How are you?"

            message.contains("time", ignoreCase = true) ->
                "The time is ${
                    java.text.SimpleDateFormat(
                        "hh:mm a",
                        Locale.getDefault()
                    ).format(java.util.Date())
                }."

            message.contains("thank", ignoreCase = true) ->
                "You are welcome. Always happy to help."

            else ->
                "I heard you say: $message. My intelligence system is still being developed."
        }

        statusText.text = "JARVIS: $reply"
        speak(reply)
    }

    private fun speak(message: String) {
        if (::tts.isInitialized) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_REPLY")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
