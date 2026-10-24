package com.backtrail.app.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Compose pointer dispatch; all movement is explicitly an Explore fixture. */
@RunWith(AndroidJUnit4::class)
class MapGestureDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun findMap(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString()?.startsWith("Offline map.") == true) return node
        repeat(node.childCount) { index -> findMap(node.getChild(index))?.let { return it } }
        return null
    }
    private fun map(): AccessibilityNodeInfo {
        repeat(60) {
            findMap(automation.rootInActiveWindow)?.let { it.refresh(); return it }
            SystemClock.sleep(100)
        }
        error("Offline map accessibility node did not appear")
    }
    private fun zoom(): Double = Regex("Zoom ([0-9.]+)").find(map().stateDescription.toString())!!.groupValues[1].trimEnd('.').toDouble()
    private fun pinch(centerX: Float, centerY: Float, from: Float, to: Float) {
        val down = SystemClock.uptimeMillis()
        val properties = Array(2) { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun inject(action: Int, span: Float, count: Int) {
            val coordinates = Array(count) { id -> MotionEvent.PointerCoords().apply { x = centerX + if (id == 0) -span else span; y = centerY; pressure = 1f; size = 1f } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            assertTrue("Pointer event delivered", automation.injectInputEvent(event, true)); event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, from, 1)
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), from, 2)
        repeat(70) { step -> inject(MotionEvent.ACTION_MOVE, from + (to - from) * (step + 1) / 70, 2); SystemClock.sleep(12) }
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), to, 2)
        inject(MotionEvent.ACTION_UP, to, 1)
        SystemClock.sleep(250)
    }
    @Test fun smallDeltaPinchChangesZoomInBothDirections() {
        val context = instrumentation.targetContext
        context.startActivity(Intent().setClassName(context.packageName, "com.backtrail.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("explore", true))
        val bounds = Rect(); map().getBoundsInScreen(bounds)
        SystemClock.sleep(600)
        val before = zoom()
        val centerX = bounds.exactCenterX()
        val centerY = bounds.top + bounds.height() * .30f
        pinch(centerX, centerY, 50f, 150f)
        val enlarged = zoom()
        assertTrue("Small-frame pinch must enlarge: $before -> $enlarged", enlarged > before + .4)
        pinch(centerX, centerY, 150f, 50f)
        val reduced = zoom()
        assertTrue("Pinch must shrink: $enlarged -> $reduced", reduced < enlarged - .4)
        assertTrue(map().stateDescription.toString().contains("Exploring map"))
        println("TRAVIS_MAP_PINCH before=$before enlarged=$enlarged reduced=$reduced")
    }
}
