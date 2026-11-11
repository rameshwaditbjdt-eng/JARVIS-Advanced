package com.jarvis.assistant

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class VoiceSetupActivity : Activity() {

    private lateinit var phraseInput: EditText
    private lateinit var actionSpinner: Spinner
    private lateinit var savedPhrases: TextView

    private val actions = listOf(
        "Open WhatsApp" to "open_whatsapp",
        "Open YouTube" to "open_youtube",
        "Open Chrome" to "open_chrome",
        "Open Calculator" to "open_calculator",
        "Go to sleep" to "sleep"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }

        content.addView(TextView(this).apply {
            text = "MY VOICE SETUP"
            textSize = 28f
        })
        content.addView(TextView(this).apply {
            text = "Teach JARVIS the words and pronunciation variants you use. Everything is saved locally on this phone."
            textSize = 16f
            setPadding(0, 16, 0, 24)
        })

        content.addView(TextView(this).apply {
            text = "PRONUNCIATION PRACTICE"
            textSize = 18f
        })
        content.addView(TextView(this).apply {
            text = "1. Start JARVIS and say its wake word.\n2. Say the command in your natural voice.\n3. If JARVIS misunderstands, enter the phrase it recognized below and connect it to the action you intended.\n\nThis teaches JARVIS your command variations. It does not retrain the speech model's neural weights."
            textSize = 14f
            setPadding(0, 8, 0, 24)
        })

        content.addView(TextView(this).apply {
            text = "PHRASE VARIANT"
            textSize = 14f
        })
        phraseInput = EditText(this).apply {
            hint = "Example: WhatsApp kholo"
            isSingleLine = true
        }
        content.addView(phraseInput, matchWidth())

        content.addView(TextView(this).apply {
            text = "WHAT SHOULD JARVIS DO?"
            textSize = 14f
            setPadding(0, 16, 0, 4)
        })
        actionSpinner = Spinner(this)
        actionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            actions.map { it.first }
        )
        content.addView(actionSpinner, matchWidth())

        content.addView(Button(this).apply {
            text = "SAVE MY PHRASE"
            setOnClickListener { savePhrase() }
        }, matchWidth())

        content.addView(TextView(this).apply {
            text = "YOUR SAVED PHRASES"
            textSize = 18f
            setPadding(0, 28, 0, 8)
        })
        savedPhrases = TextView(this).apply {
            textSize = 15f
        }
        content.addView(savedPhrases, matchWidth())

        content.addView(Button(this).apply {
            text = "DONE"
            setOnClickListener { finish() }
        }, matchWidth())

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        refreshSavedPhrases()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun savePhrase() {
        val phrase = phraseInput.text.toString()
        if (VoiceCommandStore.normalize(phrase).length < 3) {
            phraseInput.error = "Enter at least 3 characters"
            return
        }
        val action = actions[actionSpinner.selectedItemPosition].second
        VoiceCommandStore.save(this, phrase, action)
        phraseInput.text.clear()
        refreshSavedPhrases()
        Toast.makeText(this, "Your phrase has been saved offline.", Toast.LENGTH_SHORT).show()
    }

    private fun refreshSavedPhrases() {
        val entries = VoiceCommandStore.all(this)
        savedPhrases.text = if (entries.isEmpty()) {
            "No personal phrases saved yet."
        } else {
            entries.entries
                .sortedBy { it.key }
                .joinToString("\n") { (phrase, action) ->
                    val label = actions.firstOrNull { it.second == action }?.first ?: action
                    "• \"$phrase\"  →  $label"
                }
        }
    }
}
