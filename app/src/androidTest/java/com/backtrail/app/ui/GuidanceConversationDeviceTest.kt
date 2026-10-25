package com.backtrail.app.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only opt-in Explore data. It never creates or advances a real outing. */
@RunWith(AndroidJUnit4::class)
class GuidanceConversationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    }
    private fun find(label: String) = nodes(automation.rootInActiveWindow).firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun awaitNode(label: String): AccessibilityNodeInfo {
        repeat(80) { find(label)?.let { return it }; SystemClock.sleep(100) }
        error("Missing UI control: $label. Visible: ${nodes(automation.rootInActiveWindow).mapNotNull { it.text?.toString() }}")
    }
    private fun tap(label: String) {
        var node = awaitNode(label)
        repeat(30) { if (!node.isEnabled) { SystemClock.sleep(100); node = awaitNode(label) } }
        assertTrue("Enabled $label", node.isEnabled)
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue("Input delivered for $label", automation.injectInputEvent(event, true)); event.recycle()
        }
        SystemClock.sleep(550)
    }
    private fun launch() {
        val context = instrumentation.targetContext
        context.startActivity(Intent().setClassName(context.packageName, "com.backtrail.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("explore", true))
        awaitNode("Camera")
        find("OK")?.let { tap("OK") }
    }
    @Test fun routeGuidanceKeepsCameraDirectionAndMakesDestinationExplicit() {
        launch()
        tap("Return to start")
        find("OK")?.let { tap("OK") }
        tap("Camera")
        awaitNode("BACK TO RECORDED START")
        awaitNode("Approximate camera compass toward the next target")
        assertNotNull(find("LEFT ON RECORDED TRAIL"))
        assertNotNull(find("TRAIL"))
        assertNull("Proximity must not claim arrival", find("Near the recorded place"))
        tap("Replay step")
        assertNull("Near is not an arrival claim", find("Near the recorded place"))
        tap("Show route details")
        awaitNode("Check this waypoint")
        assertNull("Intermediate waypoint is not the destination", find("I've reached my destination"))
    }
    @Test fun chatShowsUserTurnAndExplicitSampleResponse() {
        launch()
        tap("Travis")
        tap("What did I leave to collect?")
        tap("Send to Travis")
        awaitNode("SAMPLE RESPONSE")
        assertNotNull(find("What did I leave to collect?"))
        assertNotNull(find("Clear chat"))
        tap("Find in AR via trail")
        awaitNode("BACK TO STONE BRIDGE")
        awaitNode("LEFT ON RECORDED TRAIL")
        tap("Travis")
        tap("Clear chat")
        awaitNode("What do you want to remember?")
    }
}
