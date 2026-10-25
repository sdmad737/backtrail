package com.backtrail.app.trip

import com.backtrail.app.contracts.*
import org.junit.Assert.*
import org.junit.Test

class RouteFollowerTest {
    private fun p(i:Int,x:Double,y:Double=0.0,accuracy:Float=3f,time:Long=1_000L+i*1_000)=
        TrailPoint("p$i",y/111_195.0,x/111_195.0,accuracy,time,0)
    private fun line():Outing {
        val points=(0..10).map { p(it,it*10.0) }
        return Outing(id="walk",points=points,retraceIndex=7,guidance=SavedGuidance(GuidanceMode.RETRACE,startIndex=10,firstIndex=7))
    }
    private fun feed(f:RouteFollower,o:Outing,x:Double,y:Double=0.0,accuracy:Float=3f,start:Long=20_000):RouteFollower.Guidance {
        var result=RouteFollower.Guidance(RouteFollower.Status.NO_ROUTE)
        repeat(3) { i->val fix=p(100+i,x,y,accuracy,start+i*1_000);result=f.update(o,fix,fix.time) }
        return result
    }
    @Test fun passedIntermediateWaypointDoesNotReverseTheArrow() {
        val o=line();val g=feed(RouteFollower(),o,60.0)
        assertNotNull(g.bearing)
        assertTrue("The return continues west; the passed target at70m must not point east",kotlin.math.abs(TrailMath.delta(g.bearing!!,270.0))<5)
        assertEquals(7,g.passedTargetIndex)
        assertEquals(ReturnRoute.next(o,7,0),g.nextTargetIndex)
    }
    @Test fun destinationWithinGpsUncertaintyDoesNotProduceAnExactArrowOrAutoArrival() {
        val o=line().copy(retraceIndex=0,guidance=SavedGuidance(GuidanceMode.RETRACE,startIndex=3,firstIndex=0))
        val g=feed(RouteFollower(),o,4.0,accuracy=12f)
        assertNull(g.bearing)
        assertNull(g.passedTargetIndex)
        assertNull(g.nextTargetIndex)
        assertEquals(RouteFollower.Status.NEAR_DESTINATION,g.status)
    }
    @Test fun repeatedSameFixCannotAcquireOrAdvanceNavigation() {
        val f=RouteFollower();val out=line();val fix=p(100,60.0,time=20_000)
        repeat(10) {
            val g=f.update(out,fix,fix.time)
            assertEquals(RouteFollower.Status.ACQUIRING,g.status)
            assertNull(g.passedTargetIndex);assertNull(g.bearing)
        }
    }
    @Test fun staleOrPoorFixRemovesArrowAndNeedsNewEvidence() {
        val f=RouteFollower();val out=line();feed(f,out,80.0)
        assertEquals(RouteFollower.Status.WEAK_GPS,f.update(out,p(200,70.0,time=25_000),31_000).status)
        val fresh=p(201,69.0,time=32_000)
        assertEquals(RouteFollower.Status.ACQUIRING,f.update(out,fresh,fresh.time).status)
        assertNull(f.update(out,p(202,68.0,accuracy=20f,time=33_000),33_000).bearing)
    }
    @Test fun selfIntersectionDoesNotAcquireANearestEarlierBranch() {
        val points=listOf(p(0,-20.0),p(1,0.0),p(2,10.0,10.0),p(3,0.0),p(4,-20.0))
        val out=Outing(id="cross",points=points,retraceIndex=3,guidance=SavedGuidance(GuidanceMode.RETRACE,startIndex=4,firstIndex=3))
        val g=feed(RouteFollower(),out,0.0)
        assertEquals(RouteFollower.Status.AMBIGUOUS,g.status)
        assertNull(g.bearing);assertNull(g.passedTargetIndex)
    }
    @Test fun positionFarFromLocalRouteDoesNotPointToADifferentSection() {
        val g=feed(RouteFollower(),line(),60.0,40.0)
        assertEquals(RouteFollower.Status.OFF_ROUTE,g.status)
        assertNull(g.bearing);assertNull(g.passedTargetIndex)
    }
    @Test fun finalDestinationIsNeverAutomaticallyAdvancedEvenIfOvershot() {
        val out=line().copy(retraceIndex=0,guidance=SavedGuidance(GuidanceMode.RETRACE,startIndex=3,firstIndex=0))
        val g=feed(RouteFollower(),out,-10.0)
        assertNull(g.passedTargetIndex);assertNull(g.nextTargetIndex)
        assertTrue((g.progress?:0f)<1f)
    }
    @Test fun recordedGapDoesNotBecomeALookAheadShortcut() {
        val out=line().copy(points=line().points.mapIndexed { i,p->p.copy(segment=if(i<6)0 else 1) },retraceIndex=6)
        val g=feed(RouteFollower(),out,60.0)
        assertEquals(RouteFollower.Status.GAP,g.status)
        assertNull(g.bearing);assertNull(g.nextTargetIndex)
    }
    @Test fun aRealCornerKeepsTheTargetOnThisLegInsteadOfCuttingAcrossIt() {
        val points=listOf(p(0,0.0),p(1,0.0,30.0),p(2,30.0,30.0),p(3,60.0,30.0))
        val out=Outing(id="corner",points=points,retraceIndex=1,guidance=SavedGuidance(GuidanceMode.RETRACE,startIndex=3,firstIndex=1))
        val g=feed(RouteFollower(),out,12.0,30.0)
        assertNotNull(g.target)
        assertEquals(0.0,g.target!!.longitude,1e-8)
        assertEquals(points[1].latitude,g.target.latitude,1e-8)
        assertTrue(kotlin.math.abs(TrailMath.delta(g.bearing!!,270.0))<5)
    }
    @Test fun changingDestinationResetsMatchedProgress() {
        val f=RouteFollower();val out=line();feed(f,out,60.0)
        val other=out.copy(id="other")
        val fix=p(200,60.0,time=30_000)
        assertEquals(RouteFollower.Status.ACQUIRING,f.update(other,fix,fix.time).status)
    }

}
