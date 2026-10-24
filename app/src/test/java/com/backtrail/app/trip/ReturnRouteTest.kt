package com.backtrail.app.trip

import com.backtrail.app.contracts.*
import org.junit.Assert.*
import org.junit.Test

class ReturnRouteTest {
    private fun point(i: Int, lat: Double, lon: Double, segment: Int = 0) =
        TrailPoint("p$i", lat, lon, 3f, 1000L + i, segment)
    private val bent = listOf(point(0,0.0,0.0),point(1,0.0001,0.0),point(2,0.0001,0.0001))
    private fun outing(points: List<TrailPoint> = bent) = Outing(id="o",points=points,
        memories=listOf(Memory("m","Start",capturedAt=1,point=points.first(),routeIndex=0)))
    @Test fun shortBendIsNotSkippedForDestinationBearing() {
        val plan=ReturnRoute.plan(outing(),"m")!!
        assertEquals(1,plan.nextIndex);assertEquals(0,plan.goalIndex)
        assertEquals(0,ReturnRoute.next(outing(),1))
    }
    @Test fun gapCannotAcquireAnInventedReturnRoute() {
        assertNull(ReturnRoute.plan(outing(bent.mapIndexed { i,p->p.copy(segment=if(i==0)0 else 1) }),"m"))
    }
    @Test fun crossingUsesKnownCursorRatherThanNearestGeometry() {
        val points=bent+listOf(point(3,0.0,0.0),point(4,0.0001,-0.0001))
        val out=outing(points).copy(retraceIndex=3)
        assertEquals(3,ReturnRoute.plan(out,"m")!!.nextIndex)
        assertEquals(3,out.retraceIndex)
    }
    @Test fun findSelectionPreservesHomeCursorAndLandmarkHasItsOwnProgress() {
        val out=outing().copy(retraceIndex=1,guidance=SavedGuidance(GuidanceMode.FIND_LANDMARK,"m"))
        val plan=ReturnRoute.plan(out,"m")!!
        val returning=out.copy(guidance=SavedGuidance(GuidanceMode.RETRACE,"m",plan.nextIndex,plan.goalIndex,plan.startIndex))
        assertEquals(1,returning.retraceIndex)
        assertEquals(1,ReturnRoute.targetIndex(returning))
        assertEquals(0,ReturnRoute.targetIndex(returning.copy(guidance=returning.guidance.copy(returnIndex=0))))
        assertEquals(1,returning.retraceIndex)
    }
    @Test fun distanceFollowsBendAndDoesNotIncludeEarlierUnrequestedRoute() {
        val out=outing().copy(guidance=SavedGuidance(GuidanceMode.RETRACE,"m",1,0,2))
        assertTrue(ReturnRoute.remaining(out,bent.last())!! > TrailMath.distance(bent.first(),bent.last()))
    }
}
