package com.backtrail.app.device

import org.junit.Assert.*
import org.junit.Test

class HeadingQualityTest {
    private fun vector(error:Float)=floatArrayOf(0f,0f,0f,1f,error)
    @Test fun reportedLargeHeadingErrorCannotBecomeReliableArrow() {
        val result=HeadingQuality.assess(vector(Math.toRadians(70.0).toFloat()),3,1_000,1_020)
        assertFalse(result.usable)
        assertEquals(70.0,result.accuracyDegrees!!,.001)
    }
    @Test fun malformedRotationCannotSupplyDirection() {
        assertFalse(HeadingQuality.assess(floatArrayOf(Float.NaN,0f,0f,1f,0f),3,1_000,1_020).usable)
        assertFalse(HeadingQuality.assess(floatArrayOf(0f),3,1_000,1_020).usable)
    }
    @Test fun knownGoodMeasurementRetainsReportedUncertainty() {
        val result=HeadingQuality.assess(vector(Math.toRadians(8.0).toFloat()),3,1_000,1_020)
        assertTrue(result.usable)
        assertEquals(8.0,result.accuracyDegrees!!,.001)
    }
    @Test fun missingErrorIsUnknownNotPerfectAccuracy() {
        assertNull(HeadingQuality.assess(vector(-1f),3,1_000,1_020).accuracyDegrees)
        assertNull(HeadingQuality.assess(floatArrayOf(0f,0f,0f,1f),3,1_000,1_020).accuracyDegrees)
    }
    @Test fun staleFutureAndLowStatusFailClosed() {
        assertFalse(HeadingQuality.assess(vector(0f),3,1_000,2_501).usable)
        assertFalse(HeadingQuality.assess(vector(0f),3,1_000,999).usable)
        assertFalse(HeadingQuality.assess(vector(0f),1,1_000,1_020).usable)
    }
}
