package com.backtrail.app.device

import com.backtrail.app.trip.TrailMath
import kotlin.math.*

/** Device rotation maps device axes into east/north/up. Rear optical axis is -Z.
 * Rotating the screen around that optical axis (portrait/landscape) does not change
 * camera bearing, so remapping to the top edge of the display would be incorrect. */
object CameraBearing {
    fun fromRotation(rotation: FloatArray, declination: Double): Double? {
        if (rotation.size != 9 || rotation.any { !it.isFinite() } || !declination.isFinite()) return null
        val east = -rotation[2].toDouble()
        val north = -rotation[5].toDouble()
        if (hypot(east, north) < 0.25) return null
        return ((Math.toDegrees(atan2(east, north)) + declination) % 360 + 360) % 360
    }

    /** Approximate map compass: current display top, or the rear camera when the top
     * points nearly vertically. A flat phone therefore still has a map bearing
     * even though its camera has no meaningful horizontal direction. */
    fun mapFromRotation(rotation: FloatArray, declination: Double, displayQuarterTurns: Int = 0): Double? {
        if (rotation.size != 9 || rotation.any { !it.isFinite() } || !declination.isFinite() || displayQuarterTurns !in 0..3) return null
        // Android display rotations 0/90/180/270 make display top +Y/-X/-Y/+X.
        val axis = if (displayQuarterTurns % 2 == 0) 1 else 0
        val sign = if (displayQuarterTurns == 1 || displayQuarterTurns == 2) -1 else 1
        val east = sign * rotation[axis].toDouble()
        val north = sign * rotation[axis + 3].toDouble()
        if (hypot(east, north) < 0.25) return fromRotation(rotation, declination)
        return ((Math.toDegrees(atan2(east, north)) + declination) % 360 + 360) % 360
    }
}

/** Time-based circular smoothing behaves consistently across sensor sample rates. */
class CameraBearingFilter {
    private var previous: Double? = null
    private var previousTime: Long? = null
    fun reset() { previous = null; previousTime = null }
    fun accept(degrees: Double, elapsedMillis: Long): Double {
        val last = previous
        val dt = previousTime?.let { elapsedMillis - it }
        val filtered = if (last == null || dt == null || dt !in 1..1_500) degrees else {
            val alpha = 1 - exp(-dt / 180.0)
            (last + alpha * TrailMath.delta(degrees, last) + 360) % 360
        }
        previous = filtered; previousTime = elapsedMillis
        return filtered
    }
}
