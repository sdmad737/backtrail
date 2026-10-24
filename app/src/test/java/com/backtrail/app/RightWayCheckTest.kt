package com.backtrail.app

import com.backtrail.app.ai.RightWayCheck
import com.backtrail.app.ai.RightWayCheck.Answer
import com.backtrail.app.ai.RightWayCheck.Source
import com.backtrail.app.contracts.FixQuality
import com.backtrail.app.contracts.TrailPoint
import com.backtrail.app.contracts.Verdict
import org.junit.Assert.*
import org.junit.Test

class RightWayCheckTest {
    private val baseLat = 37.56
    private val baseLon = -122.36
    private val step = 0.0001 // ≈ 11 m of latitude
    private val metersPerDegLon = 111_320.0 * Math.cos(Math.toRadians(baseLat))

    /** Outbound trail walked due north; walking back (decreasing index) means heading south. */
    private val trail = (0 until 30).map { pt(it.toDouble(), 0.0, "t$it", it * 10_000L) }

    private fun pt(index: Double, eastMeters: Double, id: String = "f", time: Long = 0L, acc: Float = 5f) =
        TrailPoint(id, baseLat + index * step, baseLon + eastMeters / metersPerDegLon, acc, time, 0)

    private fun fixes(vararg indices: Double, east: Double = 0.0) =
        indices.mapIndexed { i, idx -> pt(idx, east, "live$i", 1_000_000L + i * 5_000L) }

    private fun input(
        recent: List<TrailPoint>,
        quality: FixQuality = FixQuality.USABLE,
        heading: Double? = null, reliable: Boolean = false,
        visual: Verdict? = null,
    ) = RightWayCheck.Input(
        trail = trail, targetIndex = 5, targetName = "the red mailbox",
        recentFixes = recent, fixQuality = quality,
        compassHeading = heading, compassReliable = reliable,
        visual = visual, visualSpotName = "the red mailbox",
    )

    @Test fun `on trail walking back toward target is yes`() {
        val r = RightWayCheck.check(input(fixes(22.0, 21.0, 20.0)))
        assertEquals(Answer.YES, r.answer)
        assertTrue(r.summary, r.summary.startsWith("Yes"))
        assertTrue(r.summary.contains("the red mailbox"))
        assertTrue(r.reasons.any { it.source == Source.GPS && it.text.contains("dropped") })
        assertEquals(167.0, r.distanceToTargetMeters!!, 5.0)
    }

    @Test fun `walking the opposite way is no`() {
        val r = RightWayCheck.check(input(fixes(18.0, 19.0, 20.0)))
        assertEquals(Answer.NO, r.answer)
        assertTrue(r.summary, r.summary.contains("heading away from the red mailbox"))
    }

    @Test fun `sixty meters off trail is no`() {
        val r = RightWayCheck.check(input(fixes(22.0, 21.0, 20.0, east = 60.0)))
        assertEquals(Answer.NO, r.answer)
        assertTrue(r.summary, r.summary.contains("from the trail you walked"))
        assertTrue(r.summary, r.summary.contains("60 m"))
    }

    @Test fun `weak gps is not sure and keeps camera reason`() {
        val r = RightWayCheck.check(input(fixes(22.0, 21.0, 20.0), quality = FixQuality.APPROXIMATE, visual = Verdict.SUPPORTS_MATCH))
        assertEquals(Answer.NOT_SURE, r.answer)
        assertTrue(r.summary.contains("GPS is weak"))
        assertTrue(r.reasons.any { it.source == Source.CAMERA })
        assertNull(r.distanceToTargetMeters)
        assertEquals(Answer.NOT_SURE, RightWayCheck.check(input(emptyList())).answer)
    }

    @Test fun `camera different place downgrades yes to not sure`() {
        val r = RightWayCheck.check(input(fixes(22.0, 21.0, 20.0), visual = Verdict.DIFFERENT_PLACE))
        assertEquals(Answer.NOT_SURE, r.answer)
        assertTrue(r.reasons.any { it.source == Source.CAMERA && it.text.contains("doesn't look like") })
        // Never forces NO on its own, and doesn't soften a measured NO either.
        assertEquals(Answer.NO, RightWayCheck.check(input(fixes(18.0, 19.0, 20.0), visual = Verdict.DIFFERENT_PLACE)).answer)
    }

    @Test fun `camera match alone without direction is not yes`() {
        val r = RightWayCheck.check(input(fixes(20.0), visual = Verdict.SUPPORTS_MATCH))
        assertEquals(Answer.NOT_SURE, r.answer)
        assertTrue(r.reasons.any { it.source == Source.CAMERA && it.text.contains("matches your saved photo of the red mailbox") })
    }

    @Test fun `compass used when standing still`() {
        val toward = RightWayCheck.check(input(fixes(20.0, 20.0), heading = 185.0, reliable = true))
        assertEquals(Answer.YES, toward.answer)
        assertTrue(toward.reasons.any { it.source == Source.COMPASS })
        assertEquals(Answer.NO, RightWayCheck.check(input(fixes(20.0), heading = 10.0, reliable = true)).answer)
        assertEquals(Answer.NOT_SURE, RightWayCheck.check(input(fixes(20.0), heading = 270.0, reliable = true)).answer)
        assertEquals(Answer.NOT_SURE, RightWayCheck.check(input(fixes(20.0), heading = 185.0, reliable = false)).answer)
    }

    @Test fun `steps are informational only`() {
        val r = RightWayCheck.check(input(fixes(20.0)).copy(stepsSinceLastSpot = 100, metersSinceLastSpotRecorded = 80.0))
        assertEquals(Answer.NOT_SURE, r.answer)
        assertTrue(r.reasons.any { it.source == Source.STEPS && it.text.contains("about the same as") })
    }
}
