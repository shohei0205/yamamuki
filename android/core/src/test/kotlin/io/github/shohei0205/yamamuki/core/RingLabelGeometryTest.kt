package io.github.shohei0205.yamamuki.core

import kotlin.math.*
import kotlin.test.*

class RingLabelGeometryTest {
    @Test fun rotationKeepsOffscreenObserverLabelsOnTheMap() {
        val initialAngle = -PI / 2 + 0.1
        for (heading in listOf(0.0, 30.0, 90.0, 180.0, 270.0, 359.0)) {
            val turn = -Math.toRadians(heading)
            fun rotate(x: Double, y: Double) = Pair(
                180 + (x - 180) * cos(turn) - (y - 412) * sin(turn),
                412 + (x - 180) * sin(turn) + (y - 412) * cos(turn))
            val (cx, cy) = rotate(180.0, 900.0)
            fun place(radius: Double, angle: Double) =
                RingLabelGeometry.place(cx, cy, radius, 0.0, 76.0, 360.0, 748.0, 60.0, 22.0, angle)
            val rotated = assertNotNull(RingLabelGeometry.rotatedAngle(initialAngle, 0.0, heading))
            val chosen = RingLabelGeometry.direction(cx, cy, 0.0, 76.0, 360.0, 748.0, rotated,
                visibleCount = { angle -> listOf(500.0, 600.0).count { place(it, angle) != null } })
            assertEquals(rotated, chosen)
            for (radius in listOf(500.0, 600.0)) {
                val expected = rotate(180 + radius * cos(initialAngle), 900 + radius * sin(initialAngle))
                val actual = assertNotNull(place(radius, chosen))
                assertEquals(expected.first, actual.x, 1e-7)
                assertEquals(expected.second, actual.y, 1e-7)
            }
        }
    }

    @Test fun rotationCrossesNorthInBothDirectionsAndHandlesFirstFrame() {
        val angle = -PI / 2
        assertEquals(angle - Math.toRadians(2.0), RingLabelGeometry.rotatedAngle(angle, 359.0, 1.0)!!, 1e-7)
        assertEquals(angle + Math.toRadians(2.0), RingLabelGeometry.rotatedAngle(angle, 1.0, 359.0)!!, 1e-7)
        assertEquals(angle, RingLabelGeometry.rotatedAngle(angle, 45.0, 45.0))
        assertEquals(angle, RingLabelGeometry.rotatedAngle(angle, null, 45.0))
        assertNull(RingLabelGeometry.rotatedAngle(null, null, 45.0))
    }

    @Test fun directionChangesWithOneLabelButStaysWithTwo() {
        fun count(angle: Double) = listOf(100.0, 200.0, 300.0).count { radius ->
            RingLabelGeometry.place(180.0, 700.0, radius, 0.0, 76.0, 360.0, 748.0, 60.0, 22.0, angle) != null
        }
        assertEquals(1, count(0.0))
        assertEquals(2, count(-PI / 4))
        val changed = RingLabelGeometry.direction(180.0, 700.0, 0.0, 76.0, 360.0, 748.0, 0.0, visibleCount = ::count)
        assertTrue(count(changed) >= 2)
        assertEquals(-PI / 4, RingLabelGeometry.direction(180.0, 700.0, 0.0, 76.0, 360.0, 748.0, -PI / 4, visibleCount = ::count))
        assertEquals(0.0, RingLabelGeometry.direction(180.0, 700.0, 0.0, 76.0, 360.0, 748.0, 0.0, visibleCount = { 1 }))
    }

    @Test fun smallMovesKeepDirectionEvenAcrossScreenCenter() {
        val angle = -PI / 2
        for (x in listOf(175.0, 180.0, 185.0)) for (y in listOf(408.0, 412.0, 416.0, 740.0)) {
            assertEquals(angle, RingLabelGeometry.direction(x, y, 32.0, 90.0, 328.0, 734.0, angle))
        }
    }

    @Test fun offscreenRayChoosesNewDirectionAndKeepsItOnSmallMoves() {
        val angle = RingLabelGeometry.direction(400.0, 740.0, 32.0, 90.0, 328.0, 734.0, -PI / 2)
        assertNotEquals(-PI / 2, angle)
        for (x in listOf(398.0, 400.0, 402.0)) {
            assertEquals(angle, RingLabelGeometry.direction(x, 740.0, 32.0, 90.0, 328.0, 734.0, angle))
        }
    }

    @Test fun labelsStayOnOneRayForEveryObserverPositionAndTextSize() {
        for ((cx, cy) in listOf(180.0 to 748.0, -100.0 to 900.0, 500.0 to 900.0, 180.0 to -150.0, 180.0 to 412.0)) {
            val angle = RingLabelGeometry.direction(cx, cy, 0.0, 76.0, 360.0, 748.0)
            var count = 0
            for (i in 1..20) {
                val radius = i * 50.0
                val width = if (i % 2 == 0) 60.0 else 90.0
                val p = RingLabelGeometry.place(cx, cy, radius, 0.0, 76.0, 360.0, 748.0, width, 22.0, angle) ?: continue
                count++
                assertEquals(0.0, (p.x - cx) * sin(angle) - (p.y - cy) * cos(angle), 1e-7)
                assertEquals(radius, hypot(p.x - cx, p.y - cy), 1e-7)
                assertTrue(p.x - width / 2 >= 0 && p.x + width / 2 <= 360)
                assertTrue(p.y - 11 >= 76 && p.y + 11 <= 748)
            }
            assertTrue(count >= 2)
        }
    }

    @Test fun usualViewPointsUpAndClippedLabelsAreNotMovedSideways() {
        val angle = RingLabelGeometry.direction(180.0, 748.0, 0.0, 76.0, 360.0, 748.0)
        assertEquals(-PI / 2, angle)
        val p = assertNotNull(RingLabelGeometry.place(180.0, 748.0, 200.0, 0.0, 76.0, 360.0, 748.0, 60.0, 22.0, angle))
        assertEquals(180.0, p.x, 1e-7)
        assertEquals(548.0, p.y, 1e-7)
        assertNull(RingLabelGeometry.place(180.0, 748.0, 700.0, 0.0, 76.0, 360.0, 748.0, 60.0, 22.0, angle))
        assertNull(RingLabelGeometry.place(180.0, 748.0, 0.0, 0.0, 76.0, 360.0, 748.0, 60.0, 22.0, angle))
    }
}
