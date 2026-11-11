
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

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 80, 40, 40)

        val title = TextView(this)
        title.text = "J.A.R.V.I.S"
        title.textSize = 32f
        layout.addView(title)

        statusText = TextView(this)
        statusText.text = "SYSTEM STANDBY"
        statusText.textSize = 18f
        statusText.setPadding(0, 30, 0, 30)
        layout.addView(statusText)

        startButton = Button(this)
        startButton.text = "START JARVIS"
        layout.addView(startButton)

        startButton.setOnClickListener {
            startJarvis()
        }

        setContentView(layout)
    }

    private fun startJarvis() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                101
            )
            return
        }

        launchJarvisService()
    }

    private fun launchJarvisService() {
        try {
            val intent = Intent(this, JarvisService::class.java)
            startForegroundService(intent)

            statusText.text = "JARVIS BACKGROUND ACTIVE"
            startButton.text = "JARVIS IS ACTIVE"
            startButton.isEnabled = false

        } catch (e: Exception) {
            statusText.text = "START FAILED"

            Toast.makeText(
                this,
                "Could not start JARVIS.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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

        if (requestCode == 101) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchJarvisService()
            } else {
                statusText.text = "MICROPHONE PERMISSION REQUIRED"
            }
        }
    }
}
