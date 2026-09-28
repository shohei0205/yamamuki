package io.github.shohei0205.yamamuki.core

import kotlin.math.hypot
import kotlin.test.*

class RingLabelGeometryTest {
    private fun place(x: Double, y: Double, radius: Double, previous: Double? = null) =
        RingLabelGeometry.place(x, y, radius, 0.0, 76.0, 360.0, 748.0, 60.0, 22.0, 2.0, previous)

    @Test fun normalViewKeepsLabelAboveRing() {
        val p = assertNotNull(place(180.0, 748.0, 200.0))
        assertEquals(180.0, p.x)
        assertEquals(535.0, p.y)
    }

    @Test fun offscreenObserverGetsLabelOnVisibleArc() {
        for ((x, y) in listOf(-100.0 to 900.0, 500.0 to 900.0, 180.0 to -150.0, -100.0 to 400.0)) {
            val p = assertNotNull(place(x, y, 400.0))
            assertTrue(p.x in 30.0..330.0)
            assertTrue(p.y in 87.0..737.0)
            assertEquals(400.0, hypot(p.x - x, p.y - y), 1e-7)
        }
    }

    @Test fun smallPanRetainsPreviousAngle() {
        val before = assertNotNull(place(-100.0, 900.0, 400.0))
        val after = assertNotNull(place(-99.0, 899.0, 400.0, before.angle))
        assertEquals(before.angle, after.angle)
        assertEquals(1.0, after.x - before.x, 1e-7)
        assertEquals(-1.0, after.y - before.y, 1e-7)
    }

    @Test fun invisibleOrTooSmallAreaHasNoLabel() {
        assertNull(place(-500.0, 400.0, 1000.0))
        assertNull(place(180.0, 400.0, 0.0))
        assertNull(RingLabelGeometry.place(10.0, 10.0, 50.0, 0.0, 0.0, 20.0, 20.0, 60.0, 22.0, 2.0))
    }
}
