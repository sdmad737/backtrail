package com.backtrail.app.trip

import com.backtrail.app.contracts.*
import org.junit.Assert.*
import org.junit.Test

class GuidanceContinuityTest {
    private val fix = TrailPoint("fix",37.56,-122.36,3f,10_000,0)
    private val target = fix.copy(id="target",longitude=-122.37)
    private val outing = Outing(id="walk",guidance=SavedGuidance(GuidanceMode.RETRACE,startIndex=10,firstIndex=7))
    private val live = TravisUiState(outing=outing,mode=GuidanceMode.RETRACE,fix=fix,fixQuality=FixQuality.USABLE,
        target=target,routeTargetIndex=7,bearing=270.0,directionUsable=true,distanceMeters=80.0,remainingMeters=140.0,routeProgress=.3f)
    private fun saved() = GuidanceContinuity.present(TravisUiState(),live,RouteFollower.Status.FOLLOWING,10_000)
    private fun lost() = live.copy(fix=null,fixQuality=FixQuality.UNAVAILABLE,bearing=null,directionUsable=false,remainingMeters=null,distanceMeters=null)
    @Test fun gpsLossKeepsPointerAndDistanceWithoutInventingLivePosition() {
        val state=GuidanceContinuity.present(saved(),lost(),RouteFollower.Status.WEAK_GPS,20_000)
        assertEquals(270.0,state.bearing!!,0.0);assertTrue(state.lastKnownGuidance)
        assertNull(state.fix);assertEquals(fix,state.lastKnownFix)
        assertEquals(140.0,state.remainingMeters!!,0.0);assertEquals(10L,state.guidanceAgeSeconds)
        assertFalse(state.directionUsable);assertFalse(state.canConfirm)
    }
    @Test fun poorFixDoesNotReplaceGoodMapReferenceOrFrozenRouteDistance() {
        val poor=lost().copy(fix=fix.copy(accuracy=50f),fixQuality=FixQuality.APPROXIMATE,canConfirm=true)
        val state=GuidanceContinuity.present(saved(),poor,RouteFollower.Status.WEAK_GPS,12_000)
        assertEquals(fix,state.lastKnownFix);assertEquals(140.0,state.remainingMeters!!,0.0);assertFalse(state.canConfirm)
    }
    @Test fun fallbackAgeKeepsGrowingAndDoesNotBecomeFreshOnRefresh() {
        val first=GuidanceContinuity.present(saved(),lost(),RouteFollower.Status.WEAK_GPS,20_000)
        val second=GuidanceContinuity.present(first,lost(),RouteFollower.Status.WEAK_GPS,30_000)
        assertEquals(20L,second.guidanceAgeSeconds);assertEquals(10_000L,second.guidanceMeasuredAt)
    }
    @Test fun recoveryReplacesFallbackOnlyWithNewMeasuredDirection() {
        val held=GuidanceContinuity.present(saved(),lost(),RouteFollower.Status.WEAK_GPS,20_000)
        val recovered=GuidanceContinuity.present(held,live.copy(fix=fix.copy(time=21_000),bearing=250.0),RouteFollower.Status.FOLLOWING,21_000)
        assertFalse(recovered.lastKnownGuidance);assertEquals(250.0,recovered.bearing!!,0.0);assertEquals(21_000L,recovered.guidanceMeasuredAt)
    }
    @Test fun changedJourneyDestinationModeReplayOrCursorNeverReusesTheArrow() {
        val changed=listOf(lost().copy(outing=outing.copy(id="other")),lost().copy(selectedMemoryId="other"),
            lost().copy(mode=GuidanceMode.FIND_LANDMARK),lost().copy(replay=true),lost().copy(routeTargetIndex=4),
            lost().copy(outing=outing.copy(guidance=outing.guidance.copy(goalIndex=2))),lost().copy(mode=GuidanceMode.NONE))
        changed.forEach { assertNull(GuidanceContinuity.present(saved(),it,RouteFollower.Status.WEAK_GPS,20_000).bearing) }
    }
    @Test fun dangerousOrAmbiguousRouteEvidenceDoesNotReviveAnOldArrow() {
        listOf(RouteFollower.Status.GAP,RouteFollower.Status.OFF_ROUTE,RouteFollower.Status.AMBIGUOUS,
            RouteFollower.Status.NEAR_DESTINATION,RouteFollower.Status.POSITION_UNCERTAIN).forEach {
            assertNull(GuidanceContinuity.present(saved(),lost(),it,20_000).bearing)
        }
    }
    @Test fun neverInventsABearingWhenThereWasNoMeasuredDirection() {
        assertNull(GuidanceContinuity.present(lost(),lost(),RouteFollower.Status.WEAK_GPS,20_000).bearing)
    }
    @Test fun reacquisitionRetainsReferenceAndFreezesProgress() {
        val state=GuidanceContinuity.present(saved(),lost().copy(routeProgress=.6f),RouteFollower.Status.ACQUIRING,20_000)
        assertTrue(state.lastKnownGuidance);assertEquals(.3f,state.routeProgress,0f)
    }
    @Test fun directFindRetainsOnlyItsOwnMeasuredBearing() {
        val direct=live.copy(mode=GuidanceMode.FIND_LANDMARK,outing=outing.copy(guidance=SavedGuidance(GuidanceMode.FIND_LANDMARK,"memory")),selectedMemoryId="memory")
        val old=GuidanceContinuity.present(TravisUiState(),direct,null,10_000)
        val held=GuidanceContinuity.present(old,direct.copy(fix=null,bearing=null,directionUsable=false,fixQuality=FixQuality.UNAVAILABLE),null,20_000)
        assertTrue(held.lastKnownGuidance);assertEquals(270.0,held.bearing!!,0.0)
    }
}
