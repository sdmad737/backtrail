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
    @Test fun flatMapCompassUsesPhoneTopWhenCameraCannotPointHorizontally() {
        val north=floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        val east=floatArrayOf(0f,1f,0f, -1f,0f,0f, 0f,0f,1f)
        assertNull(CameraBearing.fromRotation(north,0.0))
        assertNull(CameraBearing.fromRotation(east,0.0))
        assertEquals(0.0,CameraBearing.mapFromRotation(north,0.0)!!,.001)
        assertEquals(90.0,CameraBearing.mapFromRotation(east,0.0)!!,.001)
    }
    @Test fun uprightMapFallsBackToRearCameraInsteadOfAnUnstableTopProjection() {
        val upright=floatArrayOf(1f,0f,0f, 0f,0f,-1f, 0f,1f,0f)
        // A small roll leaves the top mostly vertical, with a misleading eastward projection.
        val roll=Math.toRadians(10.0)
        val tilted=floatArrayOf(kotlin.math.cos(roll).toFloat(),kotlin.math.sin(roll).toFloat(),0f,
            0f,0f,-1f, -kotlin.math.sin(roll).toFloat(),kotlin.math.cos(roll).toFloat(),0f)
        assertEquals(12.0,CameraBearing.mapFromRotation(upright,12.0)!!,.001)
        assertEquals(12.0,CameraBearing.mapFromRotation(tilted,12.0)!!,.001)
    }
    @Test fun mapPrefersPhoneTopWhenBothProjectionsAreUseful() {
        val roll=Math.toRadians(30.0)
        val tilted=floatArrayOf(kotlin.math.cos(roll).toFloat(),kotlin.math.sin(roll).toFloat(),0f,
            0f,0f,-1f, -kotlin.math.sin(roll).toFloat(),kotlin.math.cos(roll).toFloat(),0f)
        assertEquals(0.0,CameraBearing.fromRotation(tilted,0.0)!!,.001)
        assertEquals(90.0,CameraBearing.mapFromRotation(tilted,0.0)!!,.001)
    }
    @Test fun mapCorrectsTrueNorthWrapAndRejectsInvalidInputs() {
        val north=floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        assertEquals(345.0,CameraBearing.mapFromRotation(north,-15.0)!!,.001)
        assertEquals(10.0,CameraBearing.mapFromRotation(north,370.0)!!,.001)
        assertNull(CameraBearing.mapFromRotation(FloatArray(8),0.0))
        assertNull(CameraBearing.mapFromRotation(FloatArray(9) { Float.NaN },0.0))
        assertNull(CameraBearing.mapFromRotation(north,Double.POSITIVE_INFINITY))
        assertNull(CameraBearing.mapFromRotation(FloatArray(9),0.0))
        assertNull(CameraBearing.mapFromRotation(north,0.0,4))
        assertNull(CameraBearing.mapFromRotation(north,0.0,-1))
    }
    @Test fun flatMapBearingTracksCurrentDisplayTopInEveryRotation() {
        val north=floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        listOf(0.0,270.0,180.0,90.0).forEachIndexed { turn,expected ->
            assertEquals(expected,CameraBearing.mapFromRotation(north,0.0,turn)!!,.001)
        }
        // Physically rotate the phone 90 degrees east and let the display rotate:
        // its original -X edge is now the screen top, pointing north again.
        val east=floatArrayOf(0f,1f,0f, -1f,0f,0f, 0f,0f,1f)
        assertEquals(0.0,CameraBearing.mapFromRotation(east,0.0,1)!!,.001)
    }
    @Test fun uprightLandscapeFallsBackToSameRearRayAsPortrait() {
        val landscape=floatArrayOf(0f,-1f,0f, 0f,0f,-1f, 1f,0f,0f)
        assertEquals(12.0,CameraBearing.mapFromRotation(landscape,12.0,1)!!,.001)
        assertEquals(12.0,CameraBearing.fromRotation(landscape,12.0)!!,.001)
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
