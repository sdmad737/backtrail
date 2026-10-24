package com.backtrail.app.trip

import org.junit.Assert.*
import org.junit.Test

class ApproachFeedbackTest {
    private fun ApproachFeedback.fix(distance: Double, second: Long, accuracy: Float = 2f,
                                    target: String = "outing:point-a", enabled: Boolean = true) =
        observe(target, distance, accuracy, second * 1_000, second * 1_000, enabled)

    @Test fun `entering near a target and remaining still never creates an approach cue`() {
        val policy = ApproachFeedback()
        (1L..5L).forEach { assertFalse(policy.fix(80.0, it)) }
        assertFalse(policy.fix(72.0, 6)) // 8m apparent movement, only 4m beyond uncertainty.
        assertFalse(policy.fix(71.0, 7))
        assertFalse(policy.fix(70.0, 8))
        assertTrue(policy.fix(69.0, 9))
    }

    @Test fun `recomposition cannot count a GPS fix more than once`() {
        val policy = ApproachFeedback()
        assertFalse(policy.fix(120.0, 1))
        assertFalse(policy.fix(95.0, 2))
        repeat(20) { assertFalse(policy.fix(95.0, 2)) }
        assertFalse(policy.fix(94.0, 3))
        assertTrue(policy.fix(93.0, 4))
    }

    @Test fun `both baseline and present uncertainty must permit five metres of closing`() {
        val policy = ApproachFeedback()
        assertFalse(policy.fix(120.0, 1, 15f))
        assertFalse(policy.fix(100.0, 2, 15f))
        assertFalse(policy.fix(95.0, 3, 15f))
        assertFalse(policy.fix(90.0, 4, 15f))
        assertFalse(policy.fix(85.0, 5, 15f))
        assertFalse(policy.fix(84.0, 6, 15f))
        assertTrue(policy.fix(83.0, 7, 15f))
    }

    @Test fun `bands emit once with ten seconds between them`() {
        val policy = ApproachFeedback()
        assertFalse(policy.fix(120.0, 1))
        assertFalse(policy.fix(95.0, 2))
        assertFalse(policy.fix(94.0, 3))
        assertTrue(policy.fix(93.0, 4)) // 100m band.
        assertFalse(policy.fix(48.0, 5))
        assertFalse(policy.fix(47.0, 6))
        assertFalse(policy.fix(46.0, 7)) // Three fixes, but cooldown still active.
        assertTrue(policy.fix(45.0, 14)) // 50m band.
        assertFalse(policy.fix(23.0, 15))
        assertFalse(policy.fix(22.0, 16))
        assertFalse(policy.fix(21.0, 17))
        assertTrue(policy.fix(20.0, 24)) // 25m band.
        (25L..40L).forEach { assertFalse(policy.fix(20.0, it)) }
    }

    @Test fun `jumping into an inner band cannot replay outer cues while standing still`() {
        val policy = ApproachFeedback()
        assertFalse(policy.fix(120.0, 1))
        assertFalse(policy.fix(20.0, 2))
        assertFalse(policy.fix(19.0, 3))
        assertTrue(policy.fix(18.0, 4))
        (5L..30L).forEach { assertFalse(policy.fix(18.0, it)) }
        assertFalse(policy.fix(10.0, 40))
        assertFalse(policy.fix(9.0, 41))
        assertFalse(policy.fix(8.0, 42))
    }

    @Test fun `target changes retain cooldown and returning to an old target retains used bands`() {
        val policy = ApproachFeedback()
        policy.fix(120.0, 1);policy.fix(95.0, 2);policy.fix(94.0, 3)
        assertTrue(policy.fix(93.0, 4))
        assertFalse(policy.fix(120.0, 5, target = "outing:point-b"))
        assertFalse(policy.fix(95.0, 6, target = "outing:point-b"))
        assertFalse(policy.fix(94.0, 7, target = "outing:point-b"))
        assertFalse(policy.fix(93.0, 8, target = "outing:point-b"))
        assertTrue(policy.fix(92.0, 14, target = "outing:point-b"))
        assertFalse(policy.fix(120.0, 15))
        assertFalse(policy.fix(95.0, 24))
        assertFalse(policy.fix(94.0, 25))
        assertFalse(policy.fix(93.0, 26))
    }

    @Test fun `disabled or invalid input resets pending evidence and requires fresh closing`() {
        val interruptions: List<(ApproachFeedback) -> Boolean> = listOf(
            { it.fix(94.0, 3, enabled = false) },
            { it.fix(94.0, 3, accuracy = 16f) },
            { it.fix(Double.NaN, 3) },
            { it.fix(-1.0, 3) },
            { it.fix(94.0, 3, accuracy = Float.POSITIVE_INFINITY) },
            { it.observe("outing:point-a", 94.0, 2f, 3_001, 3_000, true) },
            { it.observe("outing:point-a", 94.0, 2f, 1_000, 16_001, true) },
            { it.observe("outing:point-a", 94.0, 2f, null, 3_000, true) },
        )
        interruptions.forEach { interrupt ->
            val policy = ApproachFeedback()
            policy.fix(120.0, 1);policy.fix(95.0, 2)
            assertFalse(interrupt(policy))
            assertFalse(policy.fix(94.0, 20)) // New baseline, not completion of old evidence.
            assertFalse(policy.fix(80.0, 21))
            assertFalse(policy.fix(79.0, 22))
            assertTrue(policy.fix(78.0, 23))
        }
    }

    @Test fun `disabled feedback preserves the previous cue cooldown`() {
        val policy = ApproachFeedback()
        policy.fix(120.0, 1);policy.fix(95.0, 2);policy.fix(94.0, 3)
        assertTrue(policy.fix(93.0, 4))
        assertFalse(policy.fix(70.0, 5, enabled = false))
        assertFalse(policy.fix(70.0, 6))
        assertFalse(policy.fix(48.0, 7))
        assertFalse(policy.fix(47.0, 8))
        assertFalse(policy.fix(46.0, 9))
        assertTrue(policy.fix(45.0, 14))
    }

    @Test fun `a GPS interruption cannot complete old approach evidence`() {
        val policy = ApproachFeedback()
        policy.fix(120.0, 1);policy.fix(95.0, 2);policy.fix(94.0, 3)
        assertFalse(policy.fix(93.0, 20))
        assertFalse(policy.fix(80.0, 21))
        assertFalse(policy.fix(79.0, 22))
        assertTrue(policy.fix(78.0, 23))
    }
}
