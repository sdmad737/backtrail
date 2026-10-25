package com.backtrail.app.device

import com.backtrail.app.contracts.TrailPoint
import org.junit.Assert.*
import org.junit.Test

class HeadingReferenceTest {
    private val saved=TrailPoint("saved",37.56,-122.36,5f,1,0)
    @Test fun gpsLossStillProvidesDeclinationReference() { assertEquals(saved,HeadingReference.choose(null,saved)) }
    @Test fun poorOrOldGpsDoesNotDisableCompass() {
        val weak=saved.copy(accuracy=100f)
        assertEquals(weak,HeadingReference.choose(weak,null))
    }
    @Test fun invalidCoordinatesFallBackAndNeverReachGeomagneticCalculation() {
        assertEquals(saved,HeadingReference.choose(saved.copy(latitude=Double.NaN),saved))
        assertNull(HeadingReference.choose(null,saved.copy(longitude=200.0)))
    }
}
