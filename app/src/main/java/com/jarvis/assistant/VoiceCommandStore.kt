package com.jarvis.assistant

import android.content.Context
import java.util.Locale

/**
 * Stores user-taught speech variants locally and maps them to supported actions.
 * The recognizer still produces the transcript; this layer personalizes command matching.
 */
object VoiceCommandStore {
    private const val PREFS_NAME = "jarvis_voice_commands"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun normalize(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun save(context: Context, phrase: String, action: String) {
        val normalized = normalize(phrase)
        require(normalized.length >= 3) { "Please enter a longer phrase." }
        preferences(context).edit().putString(normalized, action).apply()
    }

    fun all(context: Context): Map<String, String> =
        preferences(context).all.mapNotNull { (phrase, action) ->
            if (action is String) phrase to action else null
        }.toMap()

    fun findAction(context: Context, transcript: String): String? {
        val normalizedTranscript = normalize(transcript)
        return all(context)
            .entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (phrase, _) ->
                normalizedTranscript == phrase ||
                    normalizedTranscript.contains(" $phrase ") ||
                    normalizedTranscript.startsWith("$phrase ") ||
                    normalizedTranscript.endsWith(" $phrase")
            }?.value
    }
}
