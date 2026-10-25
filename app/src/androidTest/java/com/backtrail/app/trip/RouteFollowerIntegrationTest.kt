package com.backtrail.app.trip

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backtrail.app.TravisViewModel
import com.backtrail.app.contracts.*
import com.backtrail.app.device.LiveSensors
import com.backtrail.app.device.RecordingService
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in emulator integration only. Synthetic geometry is never injected into a physical phone. */
@RunWith(AndroidJUnit4::class)
class RouteFollowerIntegrationTest {
    @Test fun measuredProgressAdvancesIntermediatePointsWithoutCompletingDestination():Unit=runBlocking {
        assumeTrue("Synthetic GPS is restricted to an emulator",Build.MODEL.startsWith("sdk_gphone") || Build.MODEL.contains("Emulator",true))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as Application
        instrumentation.runOnMainSync { app.stopService(Intent(app,RecordingService::class.java)) }
        delay(100)
        val recordFile=File(app.filesDir,"outings.json")
        val before=recordFile.takeIf { it.exists() }?.readBytes()
        val prefs=app.getSharedPreferences("travis",0)
        val oldActive=prefs.getString("active",null)
        val store=OutingStore.get(app)
        store.awaitLoaded()
        val now=System.currentTimeMillis()
        val points=(0..10).map { i->TrailPoint("synthetic-$i",0.0,i*10.0/111_195.0,3f,now-60_000+i*1_000,0) }
        val seed=Outing(id="synthetic-navigation-acceptance",name="Synthetic navigation acceptance",phase=OutingPhase.ENDED,
            startedAt=now,points=points,memories=listOf(
                Memory("goal","Synthetic destination",capturedAt=now,point=points[0],routeIndex=0,pickup=PickupPhase.ARMED),
                Memory("intermediate","Synthetic checkpoint",capturedAt=now,point=points[7],routeIndex=7,pickup=PickupPhase.ARMED)),
            guidance=SavedGuidance(GuidanceMode.RETRACE,"goal",7,0,10,firstIndex=7))
        val report=JSONObject().put("source","synthetic 100m route and injected emulator sensor state, not a physical walk")
        val models=ViewModelStore()
        lateinit var model:TravisViewModel
        try {
            store.save(seed)
            prefs.edit().putString("active",seed.id).commit()
            instrumentation.runOnMainSync {
                LiveSensors.fix.value=null;LiveSensors.trackingRunning.value=false
                model=TravisViewModel(app);models.put("navigation-test",model)
            }
            withTimeout(5_000) { model.state.first { it.outing.id==seed.id } }
            suspend fun fixes(x:Double,accuracy:Float=3f) {
                repeat(3) { ordinal ->
                    val stamp=System.currentTimeMillis()
                    instrumentation.runOnMainSync {
                        LiveSensors.fix.value=TrailPoint("synthetic-fix-$stamp-$ordinal",0.0,x/111_195.0,accuracy,stamp,0)
                    }
                    delay(50)
                }
            }
            fixes(60.0)
            val advanced=withTimeout(5_000) { model.state.first { it.routeTargetIndex!=null && it.routeTargetIndex!!<7 && it.bearing!=null } }
            assertTrue("Look-ahead continues west, not back east to the passed point",kotlin.math.abs(TrailMath.delta(advanced.bearing!!,270.0))<5)
            assertEquals(ReturnRoute.next(seed,7,0),store.outings.value.first { it.id==seed.id }.guidance.returnIndex)
            assertTrue(advanced.outing.memories.all { it.pickup==PickupPhase.ARMED })
            assertNull(advanced.recognizedMemoryId)
            report.put("intermediateCursorAdvanced",true).put("bearingContinuesWest",true).put("pickupAndRecognitionUnchanged",true)
            fixes(60.0,40f)
            val weak=withTimeout(5_000) { model.state.first { it.fix?.accuracy==40f } }
            assertNotNull("GPS loss must retain the last useful route pointer",weak.bearing)
            assertFalse("Old guidance cannot confirm arrival",weak.canConfirm)
            assertTrue(weak.lastKnownGuidance)
            val heldIndex=weak.routeTargetIndex
            instrumentation.runOnMainSync { LiveSensors.fix.value=null;LiveSensors.heading.value=90.0;LiveSensors.headingReliable.value=true }
            val lost=withTimeout(5_000) { model.state.first { it.fix==null && it.heading==90.0 && it.lastKnownGuidance } }
            assertEquals(weak.bearing,lost.bearing);assertEquals(weak.remainingMeters,lost.remainingMeters)
            assertEquals(heldIndex,lost.routeTargetIndex);assertNotNull(lost.lastKnownFix);assertFalse(lost.canConfirm)
            instrumentation.runOnMainSync { LiveSensors.heading.value=180.0 }
            val rotated=withTimeout(5_000) { model.state.first { it.heading==180.0 } }
            assertEquals(lost.bearing,rotated.bearing)
            assertTrue("Live heading changes the camera-relative pointer",TrailMath.delta(rotated.bearing!!,rotated.heading!!)!=TrailMath.delta(lost.bearing!!,lost.heading!!))
            fixes(59.0)
            val recovered=withTimeout(5_000) { model.state.first { it.directionUsable && !it.lastKnownGuidance } }
            assertNotNull(recovered.bearing)
            report.put("weakGpsRetainsReferenceBearing",true).put("nullGpsKeepsCompassAndPositionReference",true).put("recoveryUsesFreshGuidance",true)
            store.mutate(seed.id) { requireNotNull(it).copy(guidance=SavedGuidance(GuidanceMode.RETRACE,"goal",0,0,3,firstIndex=0)) }
            withTimeout(5_000) { model.state.first { it.routeTargetIndex==0 } }
            fixes(1.0)
            val near=withTimeout(5_000) { model.state.first { it.routeTargetIndex==0 && it.canConfirm } }
            assertEquals(GuidanceMode.RETRACE,near.mode)
            assertNull(near.recognizedMemoryId)
            assertEquals(0,store.outings.value.first { it.id==seed.id }.guidance.returnIndex)
            assertTrue(near.outing.memories.all { it.pickup==PickupPhase.ARMED })
            assertNotNull("Fresh GPS near the destination must keep an approximate pointer",near.bearing)
            assertTrue(near.approximateDirection)
            assertFalse(near.directionUsable)
            report.put("finalDestinationRequiresExplicitConfirmation",true)
        } finally {
            instrumentation.runOnMainSync { models.clear();LiveSensors.fix.value=null;LiveSensors.heading.value=null;LiveSensors.headingReliable.value=false;LiveSensors.trackingRunning.value=false }
            if(oldActive==null)prefs.edit().remove("active").commit()else prefs.edit().putString("active",oldActive).commit()
            if(before==null)recordFile.delete()else recordFile.writeBytes(before)
            File(app.filesDir,"bench").apply { mkdirs() }.resolve("route-follower-integration.json").writeText(report.toString(2))
        }
    }
}
