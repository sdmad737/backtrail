package com.backtrail.app.ai

import com.backtrail.app.contracts.FixQuality
import com.backtrail.app.contracts.TrailPoint
import com.backtrail.app.contracts.Verdict
import com.backtrail.app.trip.TrailMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * "Hey Travis, am I going the right way?" decided by ordinary code from measured facts.
 * Pure Kotlin (no Android APIs). Never says "you are here", never gives turn-by-turn;
 * every result lists its reasons, tagged with where each fact came from.
 */
object RightWayCheck {
    /** Off-trail floor: closer than this to the recorded trail always counts as on it. */
    const val OFF_TRAIL_MIN_METERS = 25.0
    /** Off-trail also scales with GPS accuracy: threshold = max(floor, this × accuracy). */
    const val OFF_TRAIL_ACCURACY_FACTOR = 2.0
    /** Movement across recent fixes needed before course-over-ground is trusted over the compass. */
    const val MIN_MOVE_FOR_COURSE_METERS = 8.0
    /** |heading − bearing to target| at or below this is "heading toward". */
    const val GOOD_DELTA_DEG = 45.0
    /** |heading − bearing to target| at or above this is "heading away". */
    const val AWAY_DELTA_DEG = 120.0
    /** Distance to target must shrink by more than this across recent fixes to count as progress. */
    const val PROGRESS_METERS = 5.0
    /** Within this of the target (or the fix accuracy, if larger) direction is meaningless; on-trail is enough. */
    const val NEAR_TARGET_METERS = 12.0
    /** Average step length used only for an informational comparison. */
    const val STEP_LENGTH_METERS = 0.75

    data class Input(
        val trail: List<TrailPoint>,
        val targetIndex: Int,
        val targetName: String?,
        val recentFixes: List<TrailPoint>,
        val fixQuality: FixQuality,
        val compassHeading: Double?,
        val compassReliable: Boolean,
        val visual: Verdict? = null,
        val visualSpotName: String? = null,
        val stepsSinceLastSpot: Int? = null,
        val metersSinceLastSpotRecorded: Double? = null,
    )

    enum class Answer { YES, NO, NOT_SURE }
    enum class Source { GPS, COMPASS, CAMERA, STEPS }
    data class Reason(val text: String, val source: Source)
    data class Result(
        val answer: Answer,
        val summary: String,
        val reasons: List<Reason>,
        val distanceToTargetMeters: Double?,
    )

    fun check(input: Input): Result {
        val reasons = mutableListOf<Reason>()
        val camera = cameraReason(input)
        val steps = stepsReason(input)
        val targetLabel = input.targetName?.takeIf { it.isNotBlank() } ?: "your next trail point"
        val target = input.trail.getOrNull(input.targetIndex)?.takeIf { TrailMath.validCoordinates(it) }
        val fixes = input.recentFixes.filter { TrailMath.validCoordinates(it) }
        val latest = fixes.lastOrNull()

        fun finish(answer: Answer, summary: String, distance: Double?): Result {
            val all = reasons.toMutableList()
            camera?.let { all += it }
            steps?.let { all += it }
            var a = answer
            var s = summary
            if (a == Answer.YES && input.visual == Verdict.DIFFERENT_PLACE) {
                a = Answer.NOT_SURE
                s = "Not sure — the GPS says you're on track, but your camera view doesn't look like your saved photo. Check your saved photos."
            }
            return Result(a, s, all, distance)
        }

        if (input.fixQuality != FixQuality.USABLE || latest == null) {
            reasons += Reason("GPS is weak here, so I can't check your direction.", Source.GPS)
            return finish(Answer.NOT_SURE, "Not sure — GPS is weak here, so I can't check your direction. Compare with your saved photos.", null)
        }
        if (target == null || input.trail.isEmpty()) {
            reasons += Reason("There's no recorded trail point to head back to.", Source.GPS)
            return finish(Answer.NOT_SURE, "Not sure — I don't have a recorded point to compare against.", null)
        }

        val distToTarget = TrailMath.distance(latest, target)
        val distRounded = roundMeters(distToTarget)
        val offTrail = crossTrackMeters(latest, input.trail)
        val limit = max(OFF_TRAIL_MIN_METERS, OFF_TRAIL_ACCURACY_FACTOR * latest.accuracy)
        if (offTrail > limit) {
            val n = roundMeters(offTrail)
            reasons += Reason("GPS puts you about $n m from the trail you recorded.", Source.GPS)
            return finish(Answer.NO, "No — you're about $n m from the trail you walked. Head back toward it before going on.", distToTarget)
        }
        reasons += Reason("GPS puts you within about ${roundMeters(offTrail)} m of the trail you recorded.", Source.GPS)

        // Progress across the recent window (supporting only).
        val first = fixes.first()
        val progress = TrailMath.distance(first, target) - distToTarget
        if (fixes.size >= 2 && progress > PROGRESS_METERS) {
            reasons += Reason("Your distance to $targetLabel dropped by about ${roundMeters(progress)} m in the last few moments.", Source.GPS)
        }

        if (distToTarget <= max(NEAR_TARGET_METERS, latest.accuracy.toDouble())) {
            reasons += Reason("$targetLabel is about $distRounded m away by GPS.", Source.GPS)
            return finish(Answer.YES, "Yes — you're on your trail and within about $distRounded m of $targetLabel.", distToTarget)
        }

        val moved = if (fixes.size >= 2) TrailMath.distance(first, latest) else 0.0
        val heading: Double?
        val headingSource: Source?
        if (moved > MIN_MOVE_FOR_COURSE_METERS) {
            heading = TrailMath.bearing(first, latest); headingSource = Source.GPS
        } else if (input.compassReliable && input.compassHeading?.isFinite() == true) {
            heading = input.compassHeading; headingSource = Source.COMPASS
        } else {
            heading = null; headingSource = null
        }
        if (heading == null || headingSource == null) {
            reasons += Reason("You haven't moved enough and the compass isn't reliable, so I can't tell which way you're facing.", Source.COMPASS)
            return finish(Answer.NOT_SURE, "Not sure — you're near your trail, but I can't tell which way you're heading. Walk a few steps and ask again.", distToTarget)
        }

        val delta = abs(TrailMath.delta(TrailMath.bearing(latest, target), heading))
        val how = if (headingSource == Source.GPS) "The way you've been walking" else "The way your phone is pointing"
        return when {
            delta <= GOOD_DELTA_DEG -> {
                reasons += Reason("$how is within ${delta.roundToInt()}° of the direction to $targetLabel.", headingSource)
                finish(Answer.YES, "Yes — you're on your trail, heading toward $targetLabel, about $distRounded m away.", distToTarget)
            }
            delta >= AWAY_DELTA_DEG -> {
                reasons += Reason("$how is ${delta.roundToInt()}° off the direction to $targetLabel.", headingSource)
                finish(Answer.NO, "No — you're heading away from $targetLabel. It's about $distRounded m away.", distToTarget)
            }
            else -> {
                reasons += Reason("$how is ${delta.roundToInt()}° off the direction to $targetLabel — not clearly toward or away.", headingSource)
                finish(Answer.NOT_SURE, "Not sure — you're on your trail, but not clearly heading toward $targetLabel. It's about $distRounded m away.", distToTarget)
            }
        }
    }

    private fun cameraReason(input: Input): Reason? {
        val spot = input.visualSpotName?.takeIf { it.isNotBlank() } ?: input.targetName?.takeIf { it.isNotBlank() } ?: "this spot"
        return when (input.visual) {
            Verdict.SUPPORTS_MATCH -> Reason("Your camera view matches your saved photo of $spot.", Source.CAMERA)
            Verdict.DIFFERENT_PLACE -> Reason("Your camera view doesn't look like your saved photo of $spot.", Source.CAMERA)
            else -> null
        }
    }

    private fun stepsReason(input: Input): Reason? {
        val steps = input.stepsSinceLastSpot ?: return null
        val recorded = input.metersSinceLastSpotRecorded?.takeIf { it.isFinite() && it > 0 } ?: return null
        if (steps < 0) return null
        val walked = steps * STEP_LENGTH_METERS
        val comparison = when {
            walked < recorded * 0.7 -> "fewer than"
            walked > recorded * 1.3 -> "more than"
            else -> "about the same as"
        }
        return Reason("You've taken about $steps steps (roughly ${roundMeters(walked)} m), $comparison the ${roundMeters(recorded)} m recorded between these spots.", Source.STEPS)
    }

    /** Smallest distance from [p] to any recorded segment (consecutive points in the same segment), local equirectangular projection. */
    internal fun crossTrackMeters(p: TrailPoint, trail: List<TrailPoint>): Double {
        val pts = trail.filter { TrailMath.validCoordinates(it) }
        if (pts.isEmpty()) return Double.POSITIVE_INFINITY
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(p.latitude))
        fun xy(q: TrailPoint) = Pair((q.longitude - p.longitude) * mPerDegLon, (q.latitude - p.latitude) * mPerDegLat)
        var best = pts.minOf { val (x, y) = xy(it); sqrt(x * x + y * y) }
        for ((a, b) in pts.zipWithNext()) {
            if (a.segment != b.segment) continue
            val (ax, ay) = xy(a); val (bx, by) = xy(b)
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val cx = ax + tc * dx; val cy = ay + tc * dy
            best = minOf(best, sqrt(cx * cx + cy * cy))
        }
        return best
    }

    private fun roundMeters(m: Double): Int = when {
        m < 20 -> m.roundToInt()
        m < 200 -> (m / 5).roundToInt() * 5
        else -> (m / 10).roundToInt() * 10
    }
}
