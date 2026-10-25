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
    private fun longerOuting(): Outing {
        val points = listOf(point(0,0.0,0.0),point(1,.0001,0.0),point(2,.0001,.0001),
            point(3,.0001,.0002),point(4,.0001,.0003),point(5,.0001,.0004))
        return Outing(id="walk",points=points,memories=listOf(
            Memory("a","Jacket",capturedAt=1,point=points[3],routeIndex=3),
            Memory("b","Bench",capturedAt=2,point=points[1],routeIndex=1),
            Memory("start","Start",capturedAt=0,point=points[0],routeIndex=0)))
    }
    private fun atMemoryGoal(home: Int? = null): Outing = longerOuting().copy(retraceIndex=home,
        guidance=SavedGuidance(GuidanceMode.RETRACE,"a",3,3,5))

    @Test fun confirmedMemoryKeepsProgressWithoutChangingPreservedHomeCursor() {
        val completed=ReturnRoute.completeTarget(atMemoryGoal(home=4))!!
        assertEquals(GuidanceMode.FIND_LANDMARK,completed.guidance.mode)
        assertEquals(3,completed.guidance.returnIndex)
        assertEquals(4,completed.retraceIndex)
        assertTrue(ReturnRoute.canResume(completed))
        val continued=ReturnRoute.resume(completed)!!
        assertEquals(1,continued.retraceIndex) // next saved photo before the bend
        assertNull(continued.guidance.memoryId)
        assertEquals(3,continued.guidance.startIndex)
        assertEquals(continued,ReturnRoute.resume(continued)) // no double advancement
    }

    @Test fun directMemoryReturnCanContinueHomeWithoutAnEarlierHeadBack() {
        val completed=ReturnRoute.completeTarget(atMemoryGoal())!!
        assertNull(completed.retraceIndex)
        val continued=ReturnRoute.resume(completed)!!
        assertEquals(1,continued.retraceIndex)
        assertEquals(GuidanceMode.RETRACE,continued.guidance.mode)
        assertEquals(continued,ReturnRoute.headBack(completed))
    }

    @Test fun subsequentEarlierMemoryStartsFromConfirmedProgressNotOriginalTail() {
        val completed=ReturnRoute.completeTarget(atMemoryGoal())!!
        assertEquals(3,ReturnRoute.plan(completed,"b")!!.startIndex)
        val selected=ReturnRoute.suspendForFind(completed,"b")
        assertEquals(3,selected.suspendedGuidance!!.returnIndex)
        assertEquals(1,ReturnRoute.plan(selected,"b")!!.nextIndex)
        assertEquals(3,ReturnRoute.plan(selected,"b")!!.startIndex)
        assertTrue(ReturnRoute.canResume(selected))
    }

    @Test fun findDetourResumesTheSameMemoryReturnAndKeepsHomeCursor() {
        val returning=atMemoryGoal(home=4).copy(guidance=SavedGuidance(GuidanceMode.RETRACE,"a",4,3,5))
        val selected=ReturnRoute.suspendForFind(returning,"b")
        assertEquals(returning.guidance,selected.suspendedGuidance)
        val resumed=ReturnRoute.resume(selected)!!
        assertEquals(returning.guidance,resumed.guidance)
        assertEquals(4,resumed.retraceIndex)
        assertNull(resumed.suspendedGuidance)
    }

    @Test fun stoppedCompletedMemoryStillResumesFromItsConfirmedPoint() {
        val completed=ReturnRoute.completeTarget(atMemoryGoal())!!
        val stopped=completed.copy(guidance=SavedGuidance(),suspendedGuidance=ReturnRoute.suspendCurrent(completed))
        assertTrue(ReturnRoute.canResume(stopped))
        assertEquals(1,ReturnRoute.resume(stopped)!!.retraceIndex)
    }

    @Test fun completionCannotAdvanceBeforeTheMemoryGoal() {
        val returning=atMemoryGoal().copy(guidance=SavedGuidance(GuidanceMode.RETRACE,"a",4,3,5))
        assertNull(ReturnRoute.completeTarget(returning))
    }

    @Test fun continuationStopsAtARecordingGapInsteadOfJumpingHome() {
        val base=atMemoryGoal()
        val gapped=base.copy(points=base.points.mapIndexed { i,p->p.copy(segment=if(i<3)0 else 1) })
        val completed=ReturnRoute.completeTarget(gapped)!!
        assertNull(ReturnRoute.resume(completed))
        assertNull(ReturnRoute.headBack(completed))
        assertFalse(ReturnRoute.canResume(completed))
        assertEquals(3,completed.guidance.returnIndex)
    }

    @Test fun confirmedStartCompletesExactlyRatherThanRestartingAtTail() {
        val atStart=longerOuting().copy(guidance=SavedGuidance(GuidanceMode.RETRACE,"start",0,0,5))
        val completedMemory=ReturnRoute.completeTarget(atStart)!!
        val home=ReturnRoute.resume(completedMemory)!!
        assertNull(home.retraceIndex)
        assertNull(home.suspendedGuidance)
        assertEquals(GuidanceMode.NONE,home.guidance.mode)
        val homeTarget=longerOuting().copy(retraceIndex=0,guidance=SavedGuidance(GuidanceMode.RETRACE,startIndex=5))
        assertEquals(GuidanceMode.NONE,ReturnRoute.completeTarget(homeTarget)!!.guidance.mode)
    }

    @Test fun undoCannotWriteMemoryReturnProgressIntoHomeWhileFinding() {
        val returning=atMemoryGoal(home=5)
        val context=ReturnRoute.undoContext(returning)!!
        val finding=ReturnRoute.suspendForFind(returning,"b")
        assertNull(ReturnRoute.undo(finding,context,4))
        assertEquals(5,finding.retraceIndex)
        val undone=ReturnRoute.undo(returning,context,4)!!
        assertEquals(4,undone.guidance.returnIndex)
        assertEquals(5,undone.retraceIndex)
    }

    @Test fun undoContextSurvivesProgressButRejectsOtherOutingOrGoalOrGap() {
        val returning=atMemoryGoal(home=4)
        val context=ReturnRoute.undoContext(returning)!!
        assertEquals(context,ReturnRoute.undoContext(returning.copy(guidance=returning.guidance.copy(returnIndex=4))))
        assertNull(ReturnRoute.undo(returning.copy(id="another"),context,4))
        assertNull(ReturnRoute.undo(returning.copy(guidance=returning.guidance.copy(memoryId="b",goalIndex=1)),context,4))
        assertNull(ReturnRoute.undo(returning,context,2))
        val gapped=returning.copy(points=returning.points.mapIndexed { i,p->p.copy(segment=if(i<4)0 else 1) })
        assertNull(ReturnRoute.undo(gapped,context,4))
    }

    @Test fun deletingSuspendedDestinationCannotResumeAFalseRecordedStart() {
        val base=atMemoryGoal(home=4)
        val finding=ReturnRoute.suspendForFind(base,"b")
        val removed=ReturnRoute.removeMemory(finding,"a")
        assertNull(removed.suspendedGuidance)
        assertEquals("b",removed.guidance.memoryId)
        assertEquals(4,removed.retraceIndex)
        assertEquals(base.points,removed.points)
        assertEquals(base.revision+1,removed.revision)
        assertFalse(removed.memories.any { it.id=="a" })
        val removedActive=ReturnRoute.removeMemory(base,"a")
        assertEquals(GuidanceMode.NONE,removedActive.guidance.mode)
        assertEquals(4,removedActive.retraceIndex)
    }

    @Test fun continuingFromMemoryKeepsUnphotographedBendAsNextTarget() {
        val base=atMemoryGoal().let { it.copy(memories=it.memories.filter { m->m.id=="a" }) }
        val completed=ReturnRoute.completeTarget(base)!!
        val resumed=ReturnRoute.resume(completed)!!
        assertEquals(1,resumed.retraceIndex)
        assertEquals(0,ReturnRoute.next(resumed,1,0))
    }

    @Test fun knownSegmentCanContinueButCannotCrossEarlierGap() {
        val base=atMemoryGoal().let { it.copy(points=it.points.mapIndexed { i,p->p.copy(segment=if(i==0)0 else 1) }) }
        val completed=ReturnRoute.completeTarget(base)!!
        val resumed=ReturnRoute.resume(completed)!!
        assertEquals(1,resumed.retraceIndex)
        assertNull(ReturnRoute.next(resumed,1,0))
        assertNull(ReturnRoute.remaining(resumed,resumed.points[3]))
    }

    @Test fun itineraryNamesEverySavedWaypointBeforeTheRequestedGoal() {
        val base=longerOuting()
        val plan=ReturnRoute.plan(base,"start")!!
        val returning=base.copy(guidance=SavedGuidance(GuidanceMode.RETRACE,"start",plan.nextIndex,plan.goalIndex,plan.startIndex,firstIndex=plan.nextIndex))
        val itinerary=ReturnRoute.itinerary(returning)!!
        assertEquals(listOf(3,1,0),itinerary.steps.map { it.index })
        assertEquals(0,itinerary.currentStepIndex)
        assertEquals(0f,itinerary.progress)
        assertTrue(itinerary.reachesGoal)
        assertEquals(TrailMath.length(base.points),itinerary.totalDistanceMeters,.001)
        val atSecond=returning.copy(guidance=returning.guidance.copy(returnIndex=1))
        val progressed=ReturnRoute.itinerary(atSecond)!!
        assertEquals(1,progressed.currentStepIndex)
        assertTrue(progressed.progress>0f && progressed.progress<1f)
        assertEquals(TrailMath.length(base.points.subList(3,6)),progressed.confirmedDistanceMeters,.001)
    }

    @Test fun itineraryDoesNotCompleteBeforeExplicitFinalConfirmation() {
        val atGoal=atMemoryGoal()
        assertEquals(0f,ReturnRoute.itinerary(atGoal)!!.progress)
        val completed=ReturnRoute.completeTarget(atGoal)!!
        assertEquals(1f,ReturnRoute.itinerary(completed)!!.progress)
        val direct=ReturnRoute.suspendForFind(longerOuting(),"a")
        assertNull(ReturnRoute.itinerary(direct))
    }

    @Test fun itineraryStopsAtGapAndDoesNotCountDisconnectedDistance() {
        val base=longerOuting().let { it.copy(points=it.points.mapIndexed { i,p->p.copy(segment=if(i<2)0 else 1) }) }
        val returning=ReturnRoute.headBack(base)!!
        val itinerary=ReturnRoute.itinerary(returning)!!
        assertEquals(listOf(3,2),itinerary.steps.map { it.index })
        assertFalse(itinerary.reachesGoal)
        assertEquals(TrailMath.length(base.points.subList(2,6)),itinerary.totalDistanceMeters,.001)
    }

    @Test fun itineraryKeepsKnownTargetAndNeverUsesNearestCrossing() {
        val base=longerOuting().copy(guidance=SavedGuidance(GuidanceMode.RETRACE,"start",3,0,3))
        val itinerary=ReturnRoute.itinerary(base)!!
        assertEquals(listOf(3,1,0),itinerary.steps.map { it.index })
        assertEquals(0f,itinerary.progress)
        val finding=ReturnRoute.suspendForFind(base,"b")
        assertNull(ReturnRoute.itinerary(finding))
        assertEquals(itinerary,ReturnRoute.itinerary(ReturnRoute.resume(finding)!!))
    }

    @Test fun resumedFirstTargetKeepsStableItineraryAfterItsConfirmation() {
        val returning=longerOuting().copy(guidance=SavedGuidance(GuidanceMode.RETRACE,"start",3,0,3))
        val before=ReturnRoute.itinerary(returning)!!
        val advanced=returning.copy(guidance=returning.guidance.copy(returnIndex=ReturnRoute.next(returning,3,0)))
        val after=ReturnRoute.itinerary(advanced)!!
        assertEquals(before.steps.map { it.index },after.steps.map { it.index })
        assertEquals(before.currentStepIndex+1,after.currentStepIndex)
    }

    @Test fun persistedFirstTargetRemainsStableWhenItIsNotTheDeparture() {
        val base=longerOuting().copy(guidance=SavedGuidance(GuidanceMode.RETRACE,"start",3,0,5,firstIndex=3))
        val before=ReturnRoute.itinerary(base)!!
        val after=ReturnRoute.itinerary(base.copy(guidance=base.guidance.copy(returnIndex=1)))!!
        assertEquals(listOf(3,1,0),before.steps.map { it.index })
        assertEquals(before.steps,after.steps)
        assertEquals(1,after.currentStepIndex)
        assertTrue(after.progress>before.progress)
    }

    @Test fun newReturnModesPersistTheirFirstTarget() {
        val home=ReturnRoute.headBack(longerOuting())!!
        assertEquals(home.retraceIndex,home.guidance.firstIndex)
        val continued=ReturnRoute.resume(ReturnRoute.completeTarget(atMemoryGoal())!!)!!
        assertEquals(continued.retraceIndex,continued.guidance.firstIndex)
        val legacy=longerOuting().copy(retraceIndex=3)
        val resumed=ReturnRoute.resume(legacy)!!
        assertEquals(3,resumed.guidance.firstIndex)
    }

}
