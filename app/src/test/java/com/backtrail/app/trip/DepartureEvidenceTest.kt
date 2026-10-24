package com.backtrail.app.trip

import com.backtrail.app.contracts.GuidanceMode
import org.junit.Assert.*
import org.junit.Test

class DepartureEvidenceTest {
    private fun away(evidence: DepartureEvidence, target: String = "walk:3", time: Long = 1_000) =
        evidence.observe(target, 100.0, 5f, true, time)

    @Test fun findDetourCannotReuseOldDepartureEvidence() {
        val evidence = DepartureEvidence()
        evidence.modeChanged(GuidanceMode.RETRACE)
        assertFalse(away(evidence)); assertFalse(away(evidence))
        val beforeFind = evidence.revision
        evidence.modeChanged(GuidanceMode.FIND_LANDMARK)
        evidence.modeChanged(GuidanceMode.RETRACE)
        assertTrue(evidence.revision > beforeFind)
        assertFalse(away(evidence)); assertFalse(away(evidence)); assertTrue(away(evidence))
    }

    @Test fun newTargetAndWeakGpsRestartEvidence() {
        val evidence = DepartureEvidence()
        evidence.modeChanged(GuidanceMode.RETRACE)
        away(evidence); away(evidence)
        assertFalse(away(evidence, "walk:2"))
        assertFalse(away(evidence, "walk:2"))
        assertFalse(evidence.observe("walk:2", 100.0, 50f, false, 1_000))
        assertFalse(away(evidence, "walk:2")); assertFalse(away(evidence, "walk:2"))
        assertTrue(away(evidence, "walk:2"))
    }

    @Test fun cooldownSurvivesModeChangesAndCloseFixClearsEvidence() {
        val evidence = DepartureEvidence()
        evidence.modeChanged(GuidanceMode.RETRACE)
        away(evidence); away(evidence); assertTrue(away(evidence))
        evidence.delivered(1_000)
        evidence.modeChanged(GuidanceMode.FIND_LANDMARK)
        evidence.modeChanged(GuidanceMode.RETRACE)
        repeat(3) { assertFalse(away(evidence, time = 10_000)) }
        assertFalse(evidence.observe("walk:3", 10.0, 5f, true, 190_000))
        assertFalse(away(evidence, time = 190_000)); assertFalse(away(evidence, time = 191_000))
        assertTrue(away(evidence, time = 192_000))
    }
}
