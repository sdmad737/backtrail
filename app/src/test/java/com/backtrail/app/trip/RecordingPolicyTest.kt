package com.backtrail.app.trip

import com.backtrail.app.contracts.*
import org.junit.Assert.*
import org.junit.Test

class RecordingPolicyTest {
    private val recording = Outing(id = "walk", phase = OutingPhase.RECORDING)
    private fun sample(elapsed: Long, lon: Double = 0.0, accuracy: Float = 5f, wall: Long = 100_000 + elapsed,
                       nowElapsed: Long = elapsed, lat: Double = 0.0) =
        RecordingSample(TrailPoint("fix-$elapsed", lat, lon, accuracy, wall, 0), elapsed, nowElapsed, wall)

    @Test fun ordinaryWalkPreservesOrderAndOneSegment() {
        val policy = RecordingPolicy()
        val first = policy.accept(recording, sample(1_000))
        val second = policy.accept(first.outing, sample(2_000, lon = .0001))
        assertEquals(listOf("fix-1000", "fix-2000"), second.outing.points.map { it.id })
        assertEquals(listOf(0, 0), second.outing.points.map { it.segment })
        assertFalse(second.evidenceInterrupted)
    }

    @Test fun briefProviderOutageDoesNotInventAConnectingLine() {
        val policy = RecordingPolicy()
        val first = policy.accept(recording, sample(1_000)).outing
        policy.interrupt()
        val next = policy.accept(first, sample(3_000, lon = .001))
        assertEquals(listOf(0, 1), next.outing.points.map { it.segment })
        assertEquals(0.0, TrailMath.length(next.outing.points), .001)
        assertNull(TrailMath.previousTarget(next.outing.points, 1))
        assertTrue(next.evidenceInterrupted)
    }

    @Test fun noCallbackForLongerThanFreshnessWindowCreatesAGap() {
        val policy = RecordingPolicy()
        val first = policy.accept(recording, sample(1_000)).outing
        val next = policy.accept(first, sample(17_000, lon = .001))
        assertEquals(1, next.outing.points.last().segment)
        assertTrue(next.evidenceInterrupted)
    }

    @Test fun rejectedOrVeryWeakFixDoesNotPolluteTheRouteAndBreaksContinuity() {
        for (bad in listOf(sample(2_000, accuracy = Float.NaN), sample(2_000, accuracy = 100f),
            sample(2_000, lat = 91.0), sample(2_000, nowElapsed = 18_000))) {
            val policy = RecordingPolicy()
            val first = policy.accept(recording, sample(1_000)).outing
            val rejected = policy.accept(first, bad)
            assertEquals(first.points, rejected.outing.points)
            assertTrue(rejected.evidenceInterrupted)
            val recovered = policy.accept(rejected.outing, sample(20_000, lon = .0001))
            assertEquals(1, recovered.outing.points.last().segment)
        }
    }

    @Test fun duplicateOrReorderedCallbacksCannotAdvanceRouteOrReminderEvidence() {
        val policy = RecordingPolicy()
        val first = policy.accept(recording, sample(2_000)).outing
        for (elapsed in listOf(2_000L, 1_000L)) {
            val next = policy.accept(first, sample(elapsed, lon = .001, nowElapsed = 3_000))
            assertEquals(first, next.outing)
            assertNull(next.fix)
            assertTrue(next.evidenceInterrupted)
        }
    }

    @Test fun wallClockChangesDoNotStopRecordingOrFabricateLongGaps() {
        val policy = RecordingPolicy()
        val first = policy.accept(recording, sample(1_000, wall = 500_000)).outing
        val backwards = policy.accept(first, sample(2_000, lon = .0001, wall = 1_000)).outing
        val forwards = policy.accept(backwards, sample(3_000, lon = .0002, wall = 9_000_000)).outing
        assertEquals(3, forwards.points.size)
        assertEquals(listOf(0, 0, 0), forwards.points.map { it.segment })
    }

    @Test fun pausedTrackingUpdatesPositionWithoutExtendingRouteThenResumesWithGap() {
        val policy = RecordingPolicy()
        val first = policy.accept(recording, sample(1_000)).outing
        val paused = policy.accept(first.copy(phase = OutingPhase.PAUSED, segment = 1), sample(2_000, lon = .001))
        assertEquals(first.points, paused.outing.points)
        assertNotNull(paused.fix)
        val resumed = policy.accept(paused.outing.copy(phase = OutingPhase.RECORDING), sample(3_000, lon = .002))
        assertEquals(listOf(0, 1), resumed.outing.points.map { it.segment })
    }

    @Test fun restartingPolicyOnAnExistingRouteKeepsOldPointsButBreaksContinuity() {
        val first = RecordingPolicy().accept(recording, sample(1_000)).outing
        val restarted = RecordingPolicy().accept(first, sample(2_000, lon = .0001))
        assertEquals(first.points.first(), restarted.outing.points.first())
        assertEquals(1, restarted.outing.points.last().segment)
    }

    @Test fun stationarySamplingUsesMonotonicTenSecondInterval() {
        val policy = RecordingPolicy()
        val first = policy.accept(recording, sample(1_000)).outing
        val early = policy.accept(first, sample(2_000, wall = 9_000_000)).outing
        assertEquals(1, early.points.size)
        val next = policy.accept(early, sample(11_000, wall = 1_000)).outing
        assertEquals(2, next.points.size)
    }

    @Test fun endedOutingStillReceivesConsecutiveFixesForFindAndReminders() {
        val policy = RecordingPolicy()
        val ended = recording.copy(phase = OutingPhase.ENDED)
        policy.accept(ended, sample(1_000))
        val next = policy.accept(ended, sample(2_000, lon = .0001))
        assertNotNull(next.fix)
        assertTrue(next.outing.points.isEmpty())
        assertFalse(next.evidenceInterrupted)
    }
}
