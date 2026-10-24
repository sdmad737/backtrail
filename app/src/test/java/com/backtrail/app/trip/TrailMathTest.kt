package com.backtrail.app.trip

import com.backtrail.app.contracts.*
import org.junit.Assert.*
import org.junit.Test

class TrailMathTest {
    private fun p(id:Int,lat:Double=0.0,lon:Double=id*.0001,segment:Int=0,accuracy:Float=5f,time:Long=10_000)=TrailPoint("$id",lat,lon,accuracy,time,segment)
    @Test fun crossingCannotSkipBackToNearestEarlierSegment() {
        val points=listOf(p(0),p(1,lon=.0003),p(2,lat=.0003,lon=.0003),p(3,lat=.0003,lon=0.0),p(4,lon=0.0),p(5,lon=.0003))
        assertEquals(4,TrailMath.previousTarget(points,5))
        assertEquals(3,TrailMath.previousTarget(points,4))
    }
    @Test fun gapsNeverCreateDistanceOrRetraceConnection() {
        val points=listOf(p(0),p(1),p(2,lon=1.0,segment=1))
        assertNull(TrailMath.previousTarget(points,2))
        assertTrue(TrailMath.length(points)<12)
    }
    @Test fun staleOrUncertainFixCannotConfirmEvenAtTarget() {
        val point=p(0)
        assertFalse(TrailMath.canConfirm(point,point,30_001))
        assertFalse(TrailMath.canConfirm(point.copy(accuracy=80f),point,10_000))
        assertTrue(TrailMath.canConfirm(point,point,10_000))
    }
    @Test fun compassWraparoundUsesShortRotation() {
        assertEquals(2.0,TrailMath.delta(1.0,359.0),.001)
        assertEquals(-2.0,TrailMath.delta(359.0,1.0),.001)
    }
    @Test fun pickupMustLeaveBeforeArrivalAndCannotRepeat() {
        assertEquals(PickupPhase.WAITING_TO_LEAVE,TrailMath.pickup(PickupPhase.WAITING_TO_LEAVE,0.0,5f,true))
        assertEquals(PickupPhase.ARMED,TrailMath.pickup(PickupPhase.WAITING_TO_LEAVE,80.0,5f,true))
        assertEquals(PickupPhase.NOTIFIED,TrailMath.pickup(PickupPhase.ARMED,10.0,5f,true))
        assertEquals(PickupPhase.NOTIFIED,TrailMath.pickup(PickupPhase.NOTIFIED,10.0,5f,true))
        assertEquals(PickupPhase.ARMED,TrailMath.pickup(PickupPhase.ARMED,10.0,80f,true))
        assertEquals(PickupPhase.ARMED,TrailMath.pickup(PickupPhase.ARMED,10.0,5f,false))
    }
}
