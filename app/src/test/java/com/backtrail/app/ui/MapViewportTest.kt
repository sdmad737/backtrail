package com.backtrail.app.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class MapViewportTest {
    private val initial = MapViewport(15.0, .16, .38)

    @Test fun `small pinch deltas accumulate instead of being discarded`() {
        var camera = initial
        repeat(50) { camera = camera.transform(350.0, 420.0, 0.0, 0.0, 1.01, 1080.0, 2000.0) }
        assertTrue(camera.zoom > 15.7)
        assertEquals(1.01.pow(50), camera.worldSize / initial.worldSize, 1e-9)
    }
    @Test fun `world point under pinch stays under translated centroid`() {
        val before = initial.worldAt(230.0, 810.0, 1080.0, 2000.0)
        val after = initial.transform(230.0, 810.0, 26.0, -13.0, 1.37, 1080.0, 2000.0)
            .worldAt(256.0, 797.0, 1080.0, 2000.0)
        assertEquals(before.first, after.first, 1e-12)
        assertEquals(before.second, after.second, 1e-12)
    }
    @Test fun `pan translates the view without changing scale`() {
        val moved = initial.transform(500.0, 700.0, 120.0, -80.0, 1.0, 1080.0, 2000.0)
        assertEquals(initial.zoom, moved.zoom, 0.0)
        assertEquals(initial.centerX - 120.0 / initial.worldSize, moved.centerX, 1e-12)
        assertEquals(initial.centerY + 80.0 / initial.worldSize, moved.centerY, 1e-12)
    }
    @Test fun `zoom bounds retain centroid anchoring`() {
        val before = initial.worldAt(120.0, 900.0, 1080.0, 2000.0)
        val max = initial.transform(120.0, 900.0, 0.0, 0.0, 1e20, 1080.0, 2000.0)
        val min = initial.transform(120.0, 900.0, 0.0, 0.0, 1e-20, 1080.0, 2000.0)
        assertEquals(20.0, max.zoom, 0.0)
        assertEquals(12.0, min.zoom, 0.0)
        assertEquals(before.first, max.worldAt(120.0, 900.0, 1080.0, 2000.0).first, 1e-12)
        assertEquals(before.second, min.worldAt(120.0, 900.0, 1080.0, 2000.0).second, 1e-12)
    }
    @Test fun `invalid gestures do not poison camera state`() {
        assertEquals(initial, initial.transform(0.0, 0.0, 0.0, 0.0, Double.NaN, 1080.0, 2000.0))
        assertEquals(initial, initial.transform(0.0, 0.0, 0.0, 0.0, 0.0, 1080.0, 2000.0))
        assertEquals(initial, initial.transform(0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 2000.0))
    }
}
