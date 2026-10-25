package com.backtrail.app.trip

import com.backtrail.app.contracts.*

/** Display memory only. Never feed retained values into matching, recording or arrival. */
object GuidanceContinuity {
    fun present(previous: TravisUiState, current: TravisUiState, status: RouteFollower.Status?, now: Long, referenceTarget: TrailPoint? = null): TravisUiState {
        val sameJourney = previous.outing.id == current.outing.id && previous.replay == current.replay
        val knownFix = current.fix?.takeIf { current.fixQuality == FixQuality.USABLE }
            ?: previous.lastKnownFix.takeIf { sameJourney }
        val base = current.copy(lastKnownFix = knownFix, lastKnownGuidance = false, guidanceMeasuredAt = null, guidanceAgeSeconds = 0, approximateDirection = false, pointerDistanceMeters = null, pointerMessage = "")
        if (current.directionUsable && current.bearing?.isFinite() == true && current.fix != null)
            return base.copy(guidanceMeasuredAt = current.fix.time)
        val sameTarget = sameJourney && current.mode != GuidanceMode.NONE && previous.mode == current.mode &&
            previous.outing.guidance == current.outing.guidance && previous.target?.id == current.target?.id &&
            previous.routeTargetIndex == current.routeTargetIndex && previous.selectedMemoryId == current.selectedMemoryId
        // Display a bearing from the same current fix the map accepts. This is distinct
        // from authorizing a route match, progress, arrival, or a traversable connection.
        val fix = current.fix
        val pointerTarget = referenceTarget ?: current.target
        if (current.mode != GuidanceMode.NONE && current.fixQuality == FixQuality.USABLE && fix != null &&
            now - fix.time in 0..15_000 && TrailMath.validCoordinates(fix) &&
            pointerTarget != null && TrailMath.validCoordinates(pointerTarget)) {
            val distance = TrailMath.distance(fix, pointerTarget)
            val approachIndex = current.outing.points.indexOfFirst { it.id == pointerTarget.id }
            val approach = if (approachIndex >= 0) current.outing.points.drop(approachIndex + 1)
                .takeWhile { it.segment == pointerTarget.segment }
                .firstOrNull { TrailMath.distance(it, pointerTarget) > 1.0 } else null
            val bearing = when {
                distance > .2 -> TrailMath.bearing(fix, pointerTarget)
                sameTarget && previous.bearing?.isFinite() == true -> previous.bearing
                approach != null -> TrailMath.bearing(approach, pointerTarget)
                else -> null // At the exact same coordinate, there is no geometric direction.
            }
            if (bearing != null) return base.copy(
                bearing = bearing, approximateDirection = true, guidanceMeasuredAt = fix.time,
                pointerDistanceMeters = distance,
                pointerMessage = if (current.mode == GuidanceMode.FIND_LANDMARK) "Direct to saved place · GPS ±${fix.accuracy.toInt()} m" else when (status) {
                    RouteFollower.Status.NEAR_DESTINATION, RouteFollower.Status.POSITION_UNCERTAIN -> "Approximate · GPS ±${fix.accuracy.toInt()} m · check the saved photo"
                    RouteFollower.Status.OFF_ROUTE, RouteFollower.Status.AMBIGUOUS -> "Bearing to waypoint · check the recorded trail"
                    RouteFollower.Status.GAP -> "Bearing to recorded segment end · route has a gap"
                    else -> "Approximate waypoint bearing · GPS ±${fix.accuracy.toInt()} m"
                },
            )
        }
        val interrupted = when (current.mode) {
            GuidanceMode.RETRACE -> status == RouteFollower.Status.WEAK_GPS || status == RouteFollower.Status.ACQUIRING ||
                (current.replay && current.fixQuality != FixQuality.USABLE)
            GuidanceMode.FIND_LANDMARK -> current.fixQuality != FixQuality.USABLE || current.fix == null ||
                current.fix.accuracy > 15 || now - current.fix.time !in 0..5_000
            else -> false
        }
        if (!sameTarget || !interrupted || previous.bearing?.isFinite() != true || previous.guidanceMeasuredAt == null)
            return base
        return base.copy(
            bearing = previous.bearing, lastKnownGuidance = true, guidanceMeasuredAt = previous.guidanceMeasuredAt,
            guidanceAgeSeconds = ((now - previous.guidanceMeasuredAt).coerceAtLeast(0) / 1_000),
            approximateDirection = previous.approximateDirection, pointerDistanceMeters = previous.pointerDistanceMeters,
            pointerMessage = previous.pointerMessage,
            distanceMeters = previous.distanceMeters, remainingMeters = previous.remainingMeters,
            destinationDistanceMeters = previous.destinationDistanceMeters, routeProgress = previous.routeProgress,
            canConfirm = false,
            navigationMessage = "Last known direction · GPS updating. Distance and progress are held at the last measured position.",
        )
    }
}
