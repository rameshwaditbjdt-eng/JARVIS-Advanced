
package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var tts: TextToSpeech

    private val speechRequestCode = 100
    private val permissionRequestCode = 101
    private val servicePermissionRequestCode = 102

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
            startBackgroundService()
        }

        listenButton.setOnClickListener {
            checkPermissionAndListen()
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, JarvisService::class.java))
            statusText.text = "SYSTEM OFFLINE"
            speak("JARVIS shutting down.")
        }

        setContentView(layout)
    }

    // START BACKGROUND SERVICE

    private fun startBackgroundService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                servicePermissionRequestCode
            )
            return
        }

        try {
            val intent = Intent(this, JarvisService::class.java)
            startForegroundService(intent)

            statusText.text = "JARVIS BACKGROUND ACTIVE"
            speak("JARVIS background service started.")

        } catch (e: Exception) {
            statusText.text = "SERVICE START FAILED"

            Toast.makeText(
                this,
                "Could not start JARVIS background service.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // MANUAL SPEECH INPUT

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

            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault()
            )

            intent.putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Speak to JARVIS"
            )

            startActivityForResult(intent, speechRequestCode)
            statusText.text = "LISTENING..."

        } catch (e: Exception) {
            statusText.text = "SPEECH RECOGNITION UNAVAILABLE"

            Toast.makeText(
                this,
                "Speech recognition is unavailable.",
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

        if (requestCode == speechRequestCode &&
            resultCode == RESULT_OK) {

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

    // PERMISSION RESULTS

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            when (requestCode) {
                permissionRequestCode -> startListening()
                servicePermissionRequestCode -> startBackgroundService()
            }

        } else if (
            requestCode == permissionRequestCode ||
            requestCode == servicePermissionRequestCode
        ) {
            Toast.makeText(
                this,
                "Microphone permission is required for JARVIS.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // COMMAND HANDLING

    private fun respondToUser(message: String) {
        val command = message.lowercase(Locale.getDefault())

        when {
            command.contains("open youtube") ||
            command.contains("launch youtube") ->
                openApp("YouTube", "com.google.android.youtube")

            command.contains("open whatsapp") ->
                openApp("WhatsApp", "com.whatsapp")

            command.contains("open chrome") ->
                openApp("Chrome", "com.android.chrome")

            command.contains("open instagram") ->
                openApp("Instagram", "com.instagram.android")

            command.contains("open facebook") ->
                openApp("Facebook", "com.facebook.katana")

            command.contains("open settings") ->
                openSystemSettings()

            command.contains("open camera") ->
                openCamera()

            command.contains("open gallery") ||
            command.contains("open photos") ->
                openGallery()

            command.contains("open calculator") ->
                openApp(
                    "Calculator",
                    "com.sec.android.app.popupcalculator",
                    "com.google.android.calculator"
                )

            command.contains("open play store") ->
                openApp("Play Store", "com.android.vending")

            command.contains("open maps") ||
            command.contains("open google maps") ->
                openApp("Google Maps", "com.google.android.apps.maps")

            command.contains("open gmail") ->
                openApp("Gmail", "com.google.android.gm")

            command.contains("hello") ->
                speak("Hello. It is good to hear from you.")

            command.contains("your name") ->
                speak("I am JARVIS, your personal assistant.")

            command.contains("how are you") ->
                speak("I am functioning perfectly. How are you?")

            command.contains("time") ->
                speak(
                    "The time is ${
                        SimpleDateFormat(
                            "hh:mm a",
                            Locale.getDefault()
                        ).format(Date())
                    }."
                )

            command.contains("thank") ->
                speak("You are welcome.")

            else ->
                speak("I heard you say $message. I am still learning.")
        }
    }

    // OPEN INSTALLED APPS

    private fun openApp(name: String, vararg packages: String) {
        for (packageName in packages) {
            val launchIntent =
                packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {
                statusText.text = "OPENING $name"
                speak("Opening $name.")
                startActivity(launchIntent)
                return
            }
        }

        speak("$name is not installed or cannot be opened.")
    }

    private fun openSystemSettings() {
        try {
            speak("Opening Settings.")
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: Exception) {
            speak("I could not open Settings.")
        }
    }

    private fun openCamera() {
        try {
            speak("Opening Camera.")
            startActivity(Intent("android.media.action.IMAGE_CAPTURE"))
        } catch (e: Exception) {
            speak("I could not open Camera.")
        }
    }

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)

            intent.setDataAndType(
                Uri.parse("content://media/internal/images/media"),
                "image/*"
            )

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)

        } catch (e: Exception) {
            speak("I could not open Gallery.")
        }
    }

    // TEXT TO SPEECH

    private fun speak(message: String) {
        if (::tts.isInitialized) {
            tts.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "JARVIS_REPLY"
            )
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
