package com.backtrail.app.ai

/**
 * Turns a stream of per-frame similarities (live camera frame vs the saved photo, both
 * embedded by EmbeddingGemma) into a calm "lining up" level for the camera screen.
 *
 * Smoothing stops the meter flickering on hand shake; hysteresis stops it bouncing across a
 * boundary. The meter is guidance for aiming the camera only. It never confirms a spot:
 * the user's tap does, after Gemma E2B has compared the two photos and given its reasons.
 */
class AlignmentMeter(
    private val closeAt: Float = 0.55f,
    private val alignedAt: Float = 0.70f,
    private val hysteresis: Float = 0.03f,
    private val smoothing: Float = 0.35f,
) {
    enum class Level { SEARCHING, CLOSE, ALIGNED }

    init {
        require(closeAt < alignedAt && hysteresis >= 0f && smoothing in 0f..1f)
    }

    var level: Level = Level.SEARCHING
        private set

    /** Smoothed similarity, or null before the first frame. */
    var smoothed: Float? = null
        private set

    fun reset() {
        level = Level.SEARCHING
        smoothed = null
    }

    fun update(similarity: Float): Level {
        val value = smoothed?.let { it + smoothing * (similarity - it) } ?: similarity
        smoothed = value
        level = when (level) {
            Level.SEARCHING -> when {
                value >= alignedAt + hysteresis -> Level.ALIGNED
                value >= closeAt + hysteresis -> Level.CLOSE
                else -> Level.SEARCHING
            }
            Level.CLOSE -> when {
                value >= alignedAt + hysteresis -> Level.ALIGNED
                value < closeAt - hysteresis -> Level.SEARCHING
                else -> Level.CLOSE
            }
            Level.ALIGNED -> when {
                value < closeAt - hysteresis -> Level.SEARCHING
                value < alignedAt - hysteresis -> Level.CLOSE
                else -> Level.ALIGNED
            }
        }
        return level
    }
}
