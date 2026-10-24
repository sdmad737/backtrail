package com.backtrail.app.ai

import kotlin.math.sqrt

/**
 * Embedding-first retrieval over the spots saved on this trip: the cheap model narrows, the
 * big model only arbitrates when the narrowing is genuinely close.
 *
 * - [Decision.Direct]: one spot clearly wins. Propose it without calling Gemma E2B.
 * - [Decision.AskModel]: a few spots are close. Gemma E2B picks among only these ids.
 * - [Decision.NoMatch]: nothing is close. "No saved spot matches that" is decided by
 *   ordinary code, never by a model that prefers to pick something.
 *
 * Every outcome is still a proposal the user confirms. Thresholds are cosine similarities
 * and must be calibrated on device; see [Thresholds.UNCALIBRATED].
 */
class SpotRetriever(private val thresholds: Thresholds = Thresholds.UNCALIBRATED) {

    /** A saved spot's vectors: its photo, and its label + features + user note as text. */
    class SpotVectors(val spotId: String, val image: FloatArray?, val text: FloatArray?)

    data class Scored(val spotId: String, val score: Float)

    sealed interface Decision {
        data class Direct(val best: Scored, val ranked: List<Scored>) : Decision
        data class AskModel(val candidates: List<Scored>) : Decision
        data class NoMatch(val ranked: List<Scored>) : Decision
    }

    data class Thresholds(
        /** Below this, a spot is not a candidate at all. */
        val floor: Float,
        /** A winner needs at least this score… */
        val direct: Float,
        /** …and this lead over the runner-up. */
        val margin: Float,
        val maxCandidates: Int = 3,
    ) {
        companion object {
            /** Placeholders until the on-device benchmark measures real score distributions. */
            val UNCALIBRATED = Thresholds(floor = 0.30f, direct = 0.55f, margin = 0.08f)
        }
    }

    /** A spot scores as its best-matching modality: a query may match the note or the photo. */
    fun rank(query: FloatArray, spots: List<SpotVectors>): List<Scored> = spots
        .map { spot ->
            val score = listOfNotNull(spot.image, spot.text).maxOfOrNull { cosine(query, it) } ?: Float.NEGATIVE_INFINITY
            Scored(spot.spotId, score)
        }
        .sortedByDescending { it.score }

    fun decide(query: FloatArray, spots: List<SpotVectors>): Decision {
        val ranked = rank(query, spots)
        val candidates = ranked.filter { it.score >= thresholds.floor }.take(thresholds.maxCandidates)
        if (candidates.isEmpty()) return Decision.NoMatch(ranked)
        val best = candidates.first()
        val runnerUp = candidates.getOrNull(1)?.score ?: thresholds.floor
        return if (best.score >= thresholds.direct && best.score - runnerUp >= thresholds.margin) {
            Decision.Direct(best, ranked)
        } else {
            Decision.AskModel(candidates)
        }
    }

    companion object {
        /** Cosine similarity. Safe for vectors that are not exactly unit length. */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "Vector sizes differ: ${a.size} vs ${b.size}" }
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            if (normA == 0.0 || normB == 0.0) return 0f
            return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
        }
    }
}
