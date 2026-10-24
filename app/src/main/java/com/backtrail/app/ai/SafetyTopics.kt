package com.backtrail.app.ai

/**
 * Questions Travis must not answer from a photo: whether something is safe to eat, drink or
 * touch, or whether an animal or plant is dangerous. A wrong answer can hurt someone, and
 * docs/PRODUCT.txt excludes these verdicts.
 *
 * Checked twice: typed questions before the model runs, and every answer after it runs
 * (a spoken question cannot be screened as text).
 */
object SafetyTopics {
    const val REFUSAL = "Travis can't tell from a photo whether something is safe to eat, drink, touch or " +
        "approach. Don't rely on a photo for that."

    private val QUESTION = Regex(
        "\\b(eat|eats|eating|edible|eatable|forag\\w*|poison\\w*|toxic|venom\\w*|drink\\w*|drinkable|potable|" +
            "safe to (touch|eat|drink|pick|handle|swim|climb|cross|pet)|is it safe|dangerous|harmful|" +
            "allerg\\w*|sting\\w*|bite|bites)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** An answer that drifted into a safety verdict anyway. */
    private val ANSWER = Regex(
        "\\b(edible|drinkable|potable|safe (to|for \\w+ to) (eat|drink|touch|handle|climb|swim|pet)|" +
            "(is|are) (not |generally |usually )?(poisonous|toxic|venomous|dangerous|harmless|safe)|" +
            "not (considered |inherently |usually |generally )?(dangerous|toxic|poisonous|harmful)|" +
            "you can (eat|drink|touch)|fine to (eat|drink|touch))\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isSafetyQuestion(text: String): Boolean = QUESTION.containsMatchIn(text)

    fun isSafetyVerdict(answer: String): Boolean = ANSWER.containsMatchIn(answer)
}
