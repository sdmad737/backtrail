package com.backtrail.app.ui

import android.Manifest
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator acceptance for full-screen navigation and the real journey-name persistence path. */
@RunWith(AndroidJUnit4::class)
class NavigationLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if(node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun find(label:String) = nodes(automation.rootInActiveWindow).firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun await(label:String, attempts:Int = 70):AccessibilityNodeInfo {
        repeat(attempts) { find(label)?.let { return it };SystemClock.sleep(100) }
        error("Missing $label: ${nodes(automation.rootInActiveWindow).mapNotNull { it.text?.toString() }}")
    }
    private fun tap(label:String) {
        val node=await(label)
        val bounds=Rect();node.getBoundsInScreen(bounds)
        val now=SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).forEach { action ->
            val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            check(automation.injectInputEvent(event,true));event.recycle()
        }
        SystemClock.sleep(650)
    }
    private fun launch(explore:Boolean) {
        context.startActivity(Intent().setClassName(context.packageName,"com.backtrail.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("explore",explore))
        await("Journeys")
        find("OK")?.let { tap("OK") }
    }
    private fun fill(text:String) {
        val field=nodes(automation.rootInActiveWindow).first { it.className?.toString()=="android.widget.EditText" }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text) }))
        SystemClock.sleep(300)
    }
    @Test fun bottomTabsAndFullBleedMapAreReachable() {
        launch(true)
        listOf("Map","Camera","Travis","Journeys").forEach { assertNotNull(await(it)) }
        val map=nodes(automation.rootInActiveWindow).first { it.contentDescription?.toString()?.startsWith("Offline map.")==true }
        val bounds=Rect();map.getBoundsInScreen(bounds)
        val screen=context.resources.displayMetrics
        assertEquals("Map extends beneath status bar",0,bounds.top)
        assertTrue("Map reaches the bottom navigation without an unused margin", bounds.bottom >= screen.heightPixels - (120 * screen.density).toInt())
        tap("Camera");await("Choose where to return")
        tap("Travis");await("Ask Travis");tap("Done")
        tap("Journeys");await("Start a new journey");tap("Done")
        tap("Map");assertNotNull(await("Hybrid · map style"))
    }
    @Test fun journeyCanBeNamedRenamedAndNewJourneyIsExplicit() {
        automation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_FINE_LOCATION)
        automation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_COARSE_LOCATION)
        launch(false)
        tap("Journeys");tap("Start a new journey")
        fill("Navigation test")
        if(find("Save & start new")!=null)tap("Save & start new")else tap("Start journey")
        await("Navigation test")
        tap("Journeys")
        val before=JSONArray(File(context.filesDir,"outings.json").readText())
        val id=(0 until before.length()).map { before.getJSONObject(it) }.first { it.getString("name")=="Navigation test" }.getString("id")
        tap("Rename");fill("Creek walk");tap("Save name");await("Creek walk")
        val after=JSONArray(File(context.filesDir,"outings.json").readText())
        assertEquals("Rename preserves owning journey",id,(0 until after.length()).map { after.getJSONObject(it) }.first { it.getString("name")=="Creek walk" }.getString("id"))
        tap("Start a new journey");await("Save this journey and start fresh?");tap("Keep current");tap("Done")
        await("Creek walk")
        find("Pause recording")?.let { tap("Pause recording") }
    }
    @Test fun realCameraIsPhotoFirstThenReviewRetakeAndSave() {
        automation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        automation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_FINE_LOCATION)
        automation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_COARSE_LOCATION)
        launch(false)
        find("Resume journey")?.let { tap("Resume journey") }
        find("Start journey")?.let { tap("Start journey") }
        tap("Save a place")
        await("Photograph a place or something to remember")
        assertNull("Notes appear only after the shutter",find("Note · optional"))
        tap("Take photo");await("Review your place", 300)
        tap("Retake");await("Take photo")
        tap("Take photo");await("Review your place", 300)
        fill("My backpack is beside the gate")
        tap("Save place");await("Journeys")
        val outings=JSONArray(File(context.filesDir,"outings.json").readText())
        val memories=(0 until outings.length()).flatMap { outing ->
            val entries=outings.getJSONObject(outing).getJSONArray("memories")
            (0 until entries.length()).map { entries.getJSONObject(it) }
        }
        val saved=memories.last { it.getString("note")=="My backpack is beside the gate" }
        val photo=File(saved.getString("photoPath"))
        assertTrue("Camera saved actual JPEG bytes",photo.length()>1000)
        assertNotEquals("Title is not a literal note",saved.getString("note"),saved.getString("title"))
        find("OK")?.let { tap("OK") }
        find("Pause recording")?.let { tap("Pause recording") }
    }

    @Test fun journeyDeletionCanBeCancelledThenConfirmed() {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.startsWith("sdk_gphone"))
        automation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_FINE_LOCATION)
        automation.grantRuntimePermission(context.packageName,Manifest.permission.ACCESS_COARSE_LOCATION)
        launch(false)
        tap("Journeys");tap("Start a new journey");fill("Delete me test")
        if(find("Save & start new")!=null)tap("Save & start new")else tap("Start journey")
        await("Delete me test");tap("Journeys");tap("Delete Delete me test")
        await("Delete Delete me test?");tap("Keep journey");await("Delete me test")
        tap("Delete Delete me test");tap("Delete journey")
        repeat(50) { if(find("Delete me test")!=null)SystemClock.sleep(100) }
        assertNull(find("Delete me test"))
        assertFalse(File(context.filesDir,"outings.json").readText().contains("Delete me test"))
        tap("Done");await("Start journey")
    }

}
