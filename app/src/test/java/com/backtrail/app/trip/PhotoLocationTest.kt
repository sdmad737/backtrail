package com.backtrail.app.trip

import com.backtrail.app.contracts.*
import org.junit.Assert.*
import org.junit.Test

class PhotoLocationTest {
    private val earlier = TrailPoint("early", 37.56, -122.36, 5f, 1_000, 0)

    @Test fun waitingForCameraCanExpireAPreviouslyUsableLocation() {
        val outing = Outing(id = "walk", points = listOf(earlier))
        assertNotNull(PhotoLocation.atDispatch(outing, earlier, 10_000).point)
        val afterReadiness = PhotoLocation.atDispatch(outing, earlier, 20_000)
        assertNull(afterReadiness.point)
        assertNull(afterReadiness.routeIndex)
        assertEquals(20_000L, afterReadiness.capturedAt)
    }

    @Test fun movementDuringCameraStartupUsesTheNewFixAndRoutePoint() {
        val later = earlier.copy(id = "later", longitude = -122.359, time = 8_000)
        val outing = Outing(id = "walk", points = listOf(earlier, later))
        val atDispatch = PhotoLocation.atDispatch(outing, later, 8_500)
        assertEquals(later, atDispatch.point)
        assertEquals(1, atDispatch.routeIndex)
    }
}
