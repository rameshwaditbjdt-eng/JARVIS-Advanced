package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }
        val title = TextView(this).apply {
            text = "J.A.R.V.I.S"
            textSize = 32f
        }
        layout.addView(title)

        statusText = TextView(this).apply {
            textSize = 18f
            setPadding(0, 30, 0, 30)
        }
        layout.addView(statusText)

        startButton = Button(this)
        layout.addView(startButton)
        startButton.setOnClickListener { startJarvis() }

        val voiceSetupButton = Button(this).apply {
            text = "MY VOICE SETUP"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, VoiceSetupActivity::class.java))
            }
        }
        layout.addView(voiceSetupButton)

        setContentView(layout)
        refreshServiceState()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
    }

    private fun refreshServiceState() {
        val active = JarvisService.isRunning
        statusText.text = if (active) "JARVIS BACKGROUND ACTIVE" else "SYSTEM STANDBY"
        startButton.text = if (active) "JARVIS IS ACTIVE" else "START JARVIS"
        startButton.isEnabled = !active
    }

    private fun startJarvis() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        launchJarvisService()
    }

    private fun launchJarvisService() {
        try {
            startForegroundService(Intent(this, JarvisService::class.java))
            statusText.text = "STARTING JARVIS..."
            startButton.text = "JARVIS IS STARTING"
            startButton.isEnabled = false
        } catch (e: Exception) {
            statusText.text = "START FAILED"
            startButton.text = "START JARVIS"
            startButton.isEnabled = true
            Toast.makeText(this, "Could not start JARVIS.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                launchJarvisService()
            } else {
                statusText.text = "MICROPHONE PERMISSION REQUIRED"
                startButton.text = "START JARVIS"
                startButton.isEnabled = true
            }
        }
    }
}
