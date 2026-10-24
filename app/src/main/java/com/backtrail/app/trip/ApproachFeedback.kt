package com.backtrail.app.trip

/** Optional foreground feedback, never arrival confirmation or a navigation decision. */
class ApproachFeedback {
    private data class Observation(val distance: Double, val accuracy: Double) {
        val near get() = distance - accuracy
        val far get() = distance + accuracy
    }

    private var target: String? = null
    private var baseline: Observation? = null
    private var lastFixTime: Long? = null
    private var candidateBand: Int? = null
    private var consecutive = 0
    private var lastEmittedAt: Long? = null
    private val consumedBands = mutableMapOf<String, MutableSet<Int>>()

    fun observe(
        targetKey: String?,
        distanceMeters: Double?,
        accuracyMeters: Float?,
        fixTime: Long?,
        now: Long,
        enabled: Boolean,
    ): Boolean {
        if (targetKey != target) {
            target = targetKey
            resetEvidence()
        }
        if (!enabled || targetKey.isNullOrBlank() || distanceMeters == null ||
            !distanceMeters.isFinite() || distanceMeters < 0 || accuracyMeters == null ||
            !accuracyMeters.isFinite() || accuracyMeters !in 0f..15f || fixTime == null ||
            fixTime < 0 || fixTime > now || now - fixTime > 15_000
        ) {
            resetEvidence()
            return false
        }

        // Recomposition or a second sensor event must not count one GPS fix twice.
        if (fixTime == lastFixTime) return false
        if (lastFixTime?.let { fixTime < it } == true) {
            resetEvidence()
            return false
        }
        if (lastFixTime?.let { fixTime - it > 15_000 } == true) resetEvidence()
        lastFixTime = fixTime
        val current = Observation(distanceMeters, accuracyMeters.toDouble())
        val previous = baseline
        if (previous == null || current.near > previous.near) {
            baseline = current
            candidateBand = null
            consecutive = 0
            return false
        }
        val consumed = consumedBands[targetKey].orEmpty()
        val band = BANDS.firstOrNull { current.far <= it && it !in consumed }
        // A position fluctuation inside its uncertainty radius is not closing progress.
        if (band == null || previous.near - current.far < 5.0) {
            candidateBand = null
            consecutive = 0
            return false
        }
        consecutive = if (candidateBand == band) (consecutive + 1).coerceAtMost(3) else 1
        candidateBand = band
        if (consecutive < 3 || lastEmittedAt?.let { now - it < 10_000 } == true) return false

        // Crossing straight into an inner band must not later replay outer-band cues.
        consumedBands.getOrPut(targetKey) { mutableSetOf() }.addAll(BANDS.filter { it >= band })
        lastEmittedAt = now
        baseline = current
        candidateBand = null
        consecutive = 0
        return true
    }

    private fun resetEvidence() {
        baseline = null
        lastFixTime = null
        candidateBand = null
        consecutive = 0
    }

    private companion object {
        val BANDS = listOf(25, 50, 100)
    }
}
