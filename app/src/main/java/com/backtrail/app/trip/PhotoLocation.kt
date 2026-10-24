package com.backtrail.app.trip

import com.backtrail.app.contracts.*

/** Snapshot at camera dispatch, after readiness; never reuse button-press freshness. */
data class PhotoLocation(val capturedAt: Long, val point: TrailPoint?, val routeIndex: Int?) {
    companion object {
        fun atDispatch(outing: Outing, fix: TrailPoint?, now: Long): PhotoLocation {
            val measured = fix?.takeIf { TrailMath.quality(it, now) == FixQuality.USABLE }
            val index = outing.points.lastIndex.takeIf { it >= 0 &&
                TrailMath.canConfirm(measured, outing.points[it], now) }
            return PhotoLocation(now, measured, index)
        }
    }
}
