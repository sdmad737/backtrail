package com.backtrail.app.trip

import com.backtrail.app.contracts.Outing
import com.backtrail.app.contracts.OutingPhase
import com.backtrail.app.contracts.TrailPoint

/** Monotonic Android sample time is deliberately separate from the persisted wall timestamp. */
data class RecordingSample(
    val point: TrailPoint,
    val elapsedMillis: Long,
    val nowElapsedMillis: Long,
    val nowWallMillis: Long,
)

data class RecordingUpdate(val outing: Outing, val fix: TrailPoint?, val evidenceInterrupted: Boolean)

/** One instance per running outing; the service processes events sequentially. */
class RecordingPolicy {
    private var lastSampleElapsed: Long? = null
    private var lastRecordedElapsed: Long? = null
    private var routeInterrupted = true
    private var evidenceInterrupted = true

    fun interrupt() {
        routeInterrupted = true
        evidenceInterrupted = true
    }

    fun accept(outing: Outing, sample: RecordingSample): RecordingUpdate {
        val point = sample.point
        val age = sample.nowElapsedMillis - sample.elapsedMillis
        if (!TrailMath.validCoordinates(point) || !point.accuracy.isFinite() || point.accuracy <= 0 ||
            sample.elapsedMillis < 0 || age !in 0..15_000 ||
            lastSampleElapsed?.let { sample.elapsedMillis <= it } == true) {
            interrupt()
            return RecordingUpdate(outing, null, true)
        }
        if (lastSampleElapsed?.let { sample.elapsedMillis - it > 15_000 } == true) interrupt()
        lastSampleElapsed = sample.elapsedMillis
        // A wall-clock adjustment must not make new samples look older than the route.
        val fix = point.copy(time = sample.nowWallMillis - age)
        if (fix.accuracy > 50) {
            interrupt()
            return RecordingUpdate(outing, fix, true)
        }
        val brokenEvidence = evidenceInterrupted
        evidenceInterrupted = false
        if (outing.phase != OutingPhase.RECORDING) {
            routeInterrupted = true
            return RecordingUpdate(outing, fix, brokenEvidence)
        }
        val last = outing.points.lastOrNull()
        val shouldRecord = last == null || routeInterrupted || lastRecordedElapsed == null ||
            TrailMath.distance(last, fix) >= 2 || sample.elapsedMillis - lastRecordedElapsed!! >= 10_000
        if (!shouldRecord) return RecordingUpdate(outing, fix, brokenEvidence)
        val segment = if (last != null && routeInterrupted && last.segment == outing.segment) outing.segment + 1 else outing.segment
        lastRecordedElapsed = sample.elapsedMillis
        routeInterrupted = false
        return RecordingUpdate(outing.copy(points = outing.points + fix.copy(segment = segment), segment = segment), fix, brokenEvidence)
    }
}
