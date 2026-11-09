
package com.jarvis.assistant

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private lateinit var personality: Spinner
    private lateinit var tts: TextToSpeech
    private var voiceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(Color.rgb(12, 18, 32))
        }

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S"
            textSize = 34f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "YOUR PERSONAL AI ASSISTANT"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "SYSTEM OFFLINE"
            textSize = 18f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(0, 35, 0, 25)
        }

        val start = Button(this).apply {
            text = "START JARVIS"
            setOnClickListener {
                status.text = "JARVIS STARTED"
                status.setTextColor(Color.GREEN)
                speak("Hello. JARVIS is ready.")
            }
        }

        val stop = Button(this).apply {
            text = "STOP JARVIS"
            setOnClickListener {
                status.text = "SYSTEM OFFLINE"
                status.setTextColor(Color.YELLOW)
                speak("JARVIS shutting down.")
            }
        }

        val label = TextView(this).apply {
            text = "CHOOSE PERSONALITY"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 25, 0, 8)
        }

        personality = Spinner(this)
        val choices = arrayOf("Caring", "Playful", "Serious")
        personality.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            choices
        )

        val thoughts = CheckBox(this).apply {
            text = "Allow occasional JARVIS thoughts"
            setTextColor(Color.WHITE)
            isChecked = true
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(status)
        layout.addView(start)
        layout.addView(stop)
        layout.addView(label)
        layout.addView(personality)
        layout.addView(thoughts)

        setContentView(layout)
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val language = tts.setLanguage(Locale.US)
            voiceReady = language != TextToSpeech.LANG_MISSING_DATA &&
                         language != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun speak(message: String) {
        if (voiceReady) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "JARVIS")
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
