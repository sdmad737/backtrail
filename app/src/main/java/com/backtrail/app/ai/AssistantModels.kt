package com.backtrail.app.ai

import kotlinx.serialization.Serializable

/** A question as the walker gave it: typed, or spoken (16 kHz mono WAV bytes). */
sealed interface Question {
    data class Text(val text: String) : Question {
        init { require(text.isNotBlank() && text.length <= MAX_QUESTION_CHARS) }
    }

    class Audio(val wav: ByteArray) : Question {
        init { require(wav.size in 1..MAX_AUDIO_BYTES) }
    }

    companion object {
        const val MAX_QUESTION_CHARS = 500
        /** About 30 s of 16 kHz 16-bit mono. */
        const val MAX_AUDIO_BYTES = 1_000_000
    }
}

/** An answer about the walker's own trip, citing the saved spots it used (real memory ids). */
data class TripAnswer(val answer: String, val sourceIds: List<String>, val grounded: Boolean)

enum class Confidence { HIGH, MEDIUM, LOW }

/** An answer about a photo just taken. Never a safety verdict: see [SafetyTopics]. */
data class PhotoAnswer(val answer: String, val confidence: Confidence, val safetyRefusal: Boolean = false)

/** A spoken or typed note turned into a return reminder, for the user to confirm. */
data class ReminderIntent(val remindOnReturn: Boolean, val task: String)

@Serializable
internal data class TripAnswerJson(val answer: String, val sourceIds: List<String>, val grounded: Boolean)

@Serializable
internal data class PhotoAnswerJson(val answer: String, val confidence: Confidence)

@Serializable
internal data class ReminderJson(val action: String, val task: String)
