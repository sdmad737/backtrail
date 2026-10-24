package com.backtrail.app.ai

import kotlinx.serialization.json.Json

/** Strict parsing for the assistant answers, same rules as [ProposalValidator]. */
object AssistantValidator {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    /**
     * [idMap] maps the short ids the model saw (s1..sN) to real memory ids; [names] maps them
     * to the titles shown to the walker, so a leaked "spot s1" reads as the place's name.
     *
     * Grounded means "answered from a cited spot": an answer claiming grounding with no
     * citation is downgraded. An ungrounded answer may still cite the spots it looked at
     * (Pixel run: "s1 had a lamp post, but none mention a stone lantern").
     */
    fun tripAnswer(raw: String, idMap: Map<String, String>, names: Map<String, String> = emptyMap()): TripAnswer {
        val parsed = decode<TripAnswerJson>(raw)
        answerText(parsed.answer, max = 600)
        require(parsed.sourceIds.size <= idMap.size && parsed.sourceIds.all { it in idMap })
        val cited = parsed.sourceIds.distinct()
        return TripAnswer(cleanAnswer(parsed.answer, names), cited.map { idMap.getValue(it) }, parsed.grounded && cited.isNotEmpty())
    }

    /** "sourceIds: [s1]", "(grounded: true)" or "sourceIds:s2" copied into the prose, anywhere. */
    private val LEAKED_FIELDS = Regex("\\s*\\(?\\s*\\b(?:sourceIds|grounded)\\b\\s*:\\s*\\[?[^\\]).]*\\]?\\s*\\)?", RegexOption.IGNORE_CASE)
    /** A parenthetical id list such as "(s1)" or "(s1 and s3)": dropped, the citation is shown separately. */
    private val ID_PARENS = Regex("\\s*\\(\\s*s\\d+(?:\\s*(?:,|and)\\s*s\\d+)*\\s*\\)")
    private val SHORT_ID = Regex("\\b(?:[Ss]pot\\s+)?s(\\d+)\\b")

    internal fun cleanAnswer(answer: String, names: Map<String, String>): String = answer
        .replace(LEAKED_FIELDS, "")
        .replace(ID_PARENS, "")
        .replace(SHORT_ID) { m -> names["s${m.groupValues[1]}"]?.let { "\"$it\"" } ?: "a saved spot" }
        .replace(Regex("\\btheir saved places\\b", RegexOption.IGNORE_CASE), "your saved places")
        .replace(Regex("\\s+([.,;!?])"), "$1")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

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
