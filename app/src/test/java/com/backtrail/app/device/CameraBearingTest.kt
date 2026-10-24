package com.backtrail.app.device

import com.backtrail.app.trip.TrailMath
import org.junit.Assert.*
import org.junit.Test

class CameraBearingTest {
    @Test fun uprightCameraUsesOpticalAxisAndTrueNorthCorrection() {
        // Rear camera faces magnetic north; device top points up.
        val north = floatArrayOf(1f,0f,0f, 0f,0f,-1f, 0f,1f,0f)
        assertEquals(12.0, CameraBearing.fromRotation(north,12.0)!!, .001)
        // Rotate the device into landscape about the same viewing ray.
        val landscape = floatArrayOf(0f,-1f,0f, 0f,0f,-1f, 1f,0f,0f)
        assertEquals(12.0, CameraBearing.fromRotation(landscape,12.0)!!, .001)
        val east = floatArrayOf(0f,0f,-1f, -1f,0f,0f, 0f,1f,0f)
        assertEquals(90.0, CameraBearing.fromRotation(east,0.0)!!, .001)
    }
    @Test fun flatOrInvalidCameraHasNoInventedHorizontalBearing() {
        assertNull(CameraBearing.fromRotation(floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f),0.0))
        assertNull(CameraBearing.fromRotation(FloatArray(9) { Float.NaN },0.0))
    }
    @Test fun smoothingTakesShortPathAcrossNorthAndRecoversWithoutOldHeading() {
        val filter=CameraBearingFilter()
        filter.accept(359.0,1_000)
        val crossed=filter.accept(1.0,1_100)
        assertTrue(kotlin.math.abs(TrailMath.delta(crossed,0.0))<1.0)
        filter.reset()
        assertEquals(180.0,filter.accept(180.0,1_200),.001)
        assertEquals(90.0,filter.accept(90.0,4_000),.001)
    }
    @Test fun smoothingIsIndependentOfSensorRate() {
        val fast=CameraBearingFilter(); val slow=CameraBearingFilter()
        fast.accept(0.0,1_000);slow.accept(0.0,1_000)
        for(time in 1_020L..1_200L step 20) fast.accept(90.0,time)
        val fastResult=fast.accept(90.0,1_220)
        val slowResult=slow.accept(90.0,1_220)
        assertEquals(fastResult,slowResult,.001)
    }
}
