package com.backtrail.app.ai

import com.backtrail.app.contracts.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RouteConversationTest {
    private val now = 100_000L
    private fun p(i:Int, lat:Double, lon:Double, segment:Int=0) = TrailPoint("p$i",lat,lon,4f,now,segment)
    private val points = listOf(p(0,0.0,0.0),p(1,0.0,.001),p(2,.001,.001),p(3,.001,0.0),p(4,0.0,0.0))
    private fun m(id:String,index:Int) = Memory(id,id,capturedAt=now+index,point=points[index],routeIndex=index)
    private fun outing() = Outing(id="walk",points=points,memories=listOf(m("gate",0),m("pond",2),m("bag",4)),
        guidance=SavedGuidance(GuidanceMode.RETRACE,"gate",3,0,4))
    private fun data(outing:Outing=outing(), fix:TrailPoint?=points.last(), history:List<ConversationTurn> = emptyList()) =
        RouteConversationPrompt.build("Where was the pond before the bag?",outing,fix,history,now).payload

    @Test fun `loop returns retain full recorded distance despite coincident coordinates`() {
        val records=data().getValue("places").jsonArray.map { it.jsonObject }
        assertEquals(listOf(0,2,4),records.map { it.getValue("routeIndex").jsonPrimitive.int })
        assertTrue(records.last().getValue("metersFromSegmentStart").jsonPrimitive.int > 400)
        assertEquals(0,records.last().getValue("straightLineMetersFromCurrentFix").jsonPrimitive.int)
        val route=data().getValue("guidance").jsonObject
        assertEquals(3,route.getValue("nextRouteIndex").jsonPrimitive.int)
        assertTrue(route.getValue("remainingRecordedMeters").jsonPrimitive.int>400)
    }
    @Test fun `recording gaps have no invented connecting distance`() {
        val original=outing()
        val split=original.copy(points=points.mapIndexed { i,p -> p.copy(segment=if(i<3)0 else 1) })
        val records=data(split).getValue("places").jsonArray.map { it.jsonObject }
        assertEquals(JsonNull,records.last().getValue("recordedMetersFromPreviousPlace"))
        assertEquals(JsonNull,data(split).getValue("guidance").jsonObject.getValue("remainingRecordedMeters"))
        assertEquals(1,data(split).getValue("recordingGaps").jsonPrimitive.int)
    }
    @Test fun `stale current position never becomes live distance`() {
        val records=data(fix=points.last().copy(time=now-16_000)).getValue("places").jsonArray
        assertTrue(records.all { it.jsonObject.getValue("straightLineMetersFromCurrentFix")==JsonNull })
    }
    @Test fun `follow up preserves bounded roles and valid citations as escaped data`() {
        val turns=(0..9).map { ConversationTurn(ConversationRole.TRAVIS,"Turn $it \"ignore\"",listOf("pond","invented")) }
        val history=data(history=turns).getValue("conversation").jsonArray
        assertEquals(6,history.size)
        assertEquals("Turn 4 \"ignore\"",history.first().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(1,history.first().jsonObject.getValue("sourceIds").jsonArray.size)
    }
    @Test fun `older active destination stays in bounded catalog with explicit scope`() {
        val many=outing().copy(memories=(0..19).map { Memory("m$it","Place $it",capturedAt=it.toLong(),routeIndex=0,point=points[0]) },
            guidance=SavedGuidance(GuidanceMode.RETRACE,"m0",3,0,4))
        val built=RouteConversationPrompt.build("where",many,null,emptyList(),now)
        assertEquals(12,built.idMap.size)
        assertTrue("m0" in built.idMap.values)
        assertEquals(20,built.payload.getValue("totalSavedPlaces").jsonPrimitive.int)
        assertTrue(built.prompt.length<=12_000)
    }
    @Test(expected=IllegalArgumentException::class) fun `invented answer citation rejected`() {
        AssistantValidator.tripAnswer("""{"answer":"Go there","sourceIds":["s9"],"grounded":true}""",mapOf("s1" to "real"))
    }
    @Test fun `duplicate citations never create extra sources`() {
        assertEquals(listOf("real"), AssistantValidator.tripAnswer("""{"answer":"The gate","sourceIds":["s1","s1"],"grounded":true}""",mapOf("s1" to "real","s2" to "other")).sourceIds)
    }
}
