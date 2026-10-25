package com.backtrail.app.device

import com.backtrail.app.contracts.TrailPoint
import com.backtrail.app.trip.TrailMath

/** Declination needs a geographic region, not a fresh navigation-quality GPS fix. */
object HeadingReference {
    fun choose(current: TrailPoint?, remembered: TrailPoint?): TrailPoint? =
        current?.takeIf(TrailMath::validCoordinates) ?: remembered?.takeIf(TrailMath::validCoordinates)
}
