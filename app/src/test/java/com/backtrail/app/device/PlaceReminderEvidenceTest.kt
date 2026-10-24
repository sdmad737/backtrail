package com.backtrail.app.device

import com.backtrail.app.contracts.*
import org.junit.Assert.*
import org.junit.Test

class PlaceReminderEvidenceTest {
    private val origin=TrailPoint("start",0.0,0.0,5f,1_000,0)
    private val memory=Memory("jacket","My jacket",capturedAt=1_000,point=origin)
    private fun fix(lon:Double=0.0,accuracy:Float=5f)=origin.copy(longitude=lon,accuracy=accuracy)

    @Test fun pickupRequiresLeavingThenThreeNewReturnObservationsAndNeverCollectsItself() {
        val evidence=PlaceReminderEvidence()
        var value=memory.copy(pickup=PickupPhase.WAITING_TO_LEAVE)
        repeat(5) { value=evidence.observe(value,fix(),1_000,true) }
        assertEquals(PickupPhase.WAITING_TO_LEAVE,value.pickup)
        repeat(3) { value=evidence.observe(value,fix(.001),1_000,true) }
        assertEquals(PickupPhase.ARMED,value.pickup)
        repeat(2) { value=evidence.observe(value,fix(),1_000,true) }
        assertEquals(PickupPhase.ARMED,value.pickup)
        value=evidence.observe(value,fix(),1_000,true)
        assertEquals(PickupPhase.NOTIFIED,value.pickup)
        repeat(5) { assertEquals(value,evidence.observe(value,fix(),1_000,true)) }
        assertEquals(PickupPhase.NOTIFIED,value.pickup)
    }
    @Test fun arrivalIsOptInAndSeparateFromPickupAndCannotRepeatAfterRestart() {
        val evidence=PlaceReminderEvidence()
        repeat(4) { assertEquals(memory,evidence.observe(memory,fix(),1_000,true)) }
        var value=memory.copy(arrival=ArrivalPhase.ARMED)
        repeat(2) { value=evidence.observe(value,fix(),1_000,true) }
        assertEquals(ArrivalPhase.ARMED,value.arrival)
        value=evidence.observe(value,fix(),1_000,true)
        assertEquals(ArrivalPhase.NOTIFIED,value.arrival)
        assertEquals(PickupPhase.NONE,value.pickup)
        repeat(4) { assertEquals(value,PlaceReminderEvidence().observe(value,fix(),1_000,true)) }
    }
    @Test fun explicitlyRearmingAnArrivalRequiresNewEvidence() {
        val evidence=PlaceReminderEvidence()
        var value=memory.copy(arrival=ArrivalPhase.ARMED)
        repeat(3) { value=evidence.observe(value,fix(),1_000,true) }
        assertEquals(ArrivalPhase.NOTIFIED,value.arrival)
        value=value.copy(arrival=ArrivalPhase.ARMED)
        repeat(2) { value=evidence.observe(value,fix(),1_000,true) }
        assertEquals(ArrivalPhase.ARMED,value.arrival)
    }
    @Test fun weakStaleOrInterruptedEvidenceCannotCompleteAnArrival() {
        val evidence=PlaceReminderEvidence()
        val value=memory.copy(arrival=ArrivalPhase.ARMED)
        repeat(2) { evidence.observe(value,fix(),1_000,true) }
        assertEquals(value,evidence.observe(value,fix(accuracy=40f),1_000,true))
        assertEquals(value,evidence.observe(value,fix(),1_000,true))
        assertEquals(value,evidence.observe(value,fix(),20_000,true))
        repeat(2) { assertEquals(value,evidence.observe(value,fix(),1_000,true)) }
        evidence.reset()
        assertEquals(value,evidence.observe(value,fix(),1_000,true))
    }
    @Test fun blockedNotificationsDoNotConsumeArrivalOrPickup() {
        val evidence=PlaceReminderEvidence()
        val armed=memory.copy(arrival=ArrivalPhase.ARMED,pickup=PickupPhase.ARMED)
        repeat(5) { assertEquals(armed,evidence.observe(armed,fix(),1_000,false)) }
        val delivered=evidence.observe(armed,fix(),1_000,true)
        assertEquals(ArrivalPhase.NOTIFIED,delivered.arrival)
        assertEquals(PickupPhase.NOTIFIED,delivered.pickup)
    }
    @Test fun checkpointUncertaintyAndOtherCheckpointsDoNotSupplyFalseEvidence() {
        val evidence=PlaceReminderEvidence()
        val uncertain=memory.copy(point=origin.copy(accuracy=40f),arrival=ArrivalPhase.ARMED)
        repeat(5) { assertEquals(uncertain,evidence.observe(uncertain,fix(),1_000,true)) }
        val a=memory.copy(arrival=ArrivalPhase.ARMED)
        val b=a.copy(id="another")
        repeat(2) { evidence.observe(a,fix(),1_000,true) }
        assertEquals(b,evidence.observe(b,fix(),1_000,true))
    }
}
