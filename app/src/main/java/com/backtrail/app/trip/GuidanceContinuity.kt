package com.backtrail.app.trip

import com.backtrail.app.contracts.*

/** Display memory only. Never feed retained values into matching, recording or arrival. */
object GuidanceContinuity {
    fun present(previous: TravisUiState, current: TravisUiState, status: RouteFollower.Status?, now: Long): TravisUiState {
        val sameJourney = previous.outing.id == current.outing.id && previous.replay == current.replay
        val knownFix = current.fix?.takeIf { current.fixQuality == FixQuality.USABLE }
            ?: previous.lastKnownFix.takeIf { sameJourney }
        val base = current.copy(lastKnownFix = knownFix, lastKnownGuidance = false, guidanceMeasuredAt = null, guidanceAgeSeconds = 0)
        if (current.directionUsable && current.bearing?.isFinite() == true && current.fix != null)
            return base.copy(guidanceMeasuredAt = current.fix.time)
        val sameTarget = sameJourney && current.mode != GuidanceMode.NONE && previous.mode == current.mode &&
            previous.outing.guidance == current.outing.guidance && previous.target?.id == current.target?.id &&
            previous.routeTargetIndex == current.routeTargetIndex && previous.selectedMemoryId == current.selectedMemoryId
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
            distanceMeters = previous.distanceMeters, remainingMeters = previous.remainingMeters,
            destinationDistanceMeters = previous.destinationDistanceMeters, routeProgress = previous.routeProgress,
            canConfirm = false,
            navigationMessage = "Last known direction · GPS updating. Distance and progress are held at the last measured position.",
        )
    }
}
