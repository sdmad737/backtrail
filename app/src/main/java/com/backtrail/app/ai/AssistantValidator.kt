package com.backtrail.app.ai

import kotlinx.serialization.json.Json

/** Strict parsing for the assistant answers, same rules as [ProposalValidator]. */
object AssistantValidator {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    /** [idMap] maps the short ids the model saw (s1..sN) to real memory ids. */
    fun tripAnswer(raw: String, idMap: Map<String, String>): TripAnswer {
        val parsed = decode<TripAnswerJson>(raw)
        answerText(parsed.answer, max = 600)
        require(parsed.sourceIds.size <= idMap.size && parsed.sourceIds.all { it in idMap })
        // An answer that claims grounding must cite something; an ungrounded one must not.
        require(parsed.grounded == parsed.sourceIds.isNotEmpty())
        return TripAnswer(parsed.answer.trim(), parsed.sourceIds.distinct().map { idMap.getValue(it) }, parsed.grounded)
    }

    fun photoAnswer(raw: String): PhotoAnswer {
        val parsed = decode<PhotoAnswerJson>(raw)
        answerText(parsed.answer, max = 600)
        if (SafetyTopics.isSafetyVerdict(parsed.answer)) {
            return PhotoAnswer(SafetyTopics.REFUSAL, Confidence.LOW, safetyRefusal = true)
        }
        return PhotoAnswer(parsed.answer.trim(), parsed.confidence)
    }

    fun reminder(raw: String): ReminderIntent {
        val parsed = decode<ReminderJson>(raw)
        return when (parsed.action) {
            "REMIND_ON_RETURN" -> {
                require(parsed.task.isNotBlank() && parsed.task.length <= 200)
                ReminderIntent(true, parsed.task.trim())
            }
            "NONE" -> ReminderIntent(false, "")
            else -> throw IllegalArgumentException("Unsupported action")
        }
    }

    private fun answerText(text: String, max: Int) {
        require(text.isNotBlank() && text.length <= max)
    }

    private inline fun <reified T> decode(raw: String): T {
        require(raw.length in 2..8_192)
        return json.decodeFromString<T>(raw)
    }
}
