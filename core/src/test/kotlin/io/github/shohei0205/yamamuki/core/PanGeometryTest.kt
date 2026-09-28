package io.github.shohei0205.yamamuki.core

import kotlin.test.*

class PanGeometryTest {
    private val tokyo = MapCenter(35.696, 139.814)

    @Test fun headingSwipeMovesTapeTicksWithFingerAcrossNorth() {
        val width = 360.0
        for (dx in listOf(-90.0, 90.0)) {
            val next = DialGeometry.swipedHeading(0.0, dx, width)
            val northTick = DialGeometry.tapeTicks(next, DialGeometry.TAPE_SPAN_DEG).first { it.angleDeg == 0 }
            assertEquals(dx, northTick.offsetDeg / DialGeometry.TAPE_SPAN_DEG * width, 1e-8)
        }
        assertEquals(0.0, DialGeometry.swipedHeading(0.0, 2160.0, width), 1e-8)
        assertEquals(45.0, DialGeometry.swipedHeading(45.0, 10.0, 0.0))
    }

    private fun screenPoint(point: MapCenter, viewport: MapCenter, heading: Double, scale: Double): PlanOffset {
        val p = DialGeometry.project(GeoMath.distanceKm(tokyo.latitude, tokyo.longitude, point.latitude, point.longitude),
            GeoMath.bearingDeg(tokyo.latitude, tokyo.longitude, point.latitude, point.longitude), heading)
        val offset = PanGeometry.observerOffset(tokyo, viewport, heading)
        return PlanOffset((p.x + offset.x) * scale, -(p.y + offset.y) * scale)
    }

    @Test fun rotationPreservesOffCenterFingerPivotAcrossNorth() {
        val viewport = PanGeometry.drag(tokyo, 100.0, -50.0, 10.0, 30.0)
        val landmark = PanGeometry.drag(tokyo, -70.0, 120.0, 10.0, 0.0)
        for (heading in listOf(0.0, 45.0, 270.0, 359.0)) {
            val pivot = screenPoint(landmark, viewport, heading, 10.0)
            val nextHeading = Heading.normalize(heading - 70.0)
            val next = PanGeometry.transformViewport(tokyo, viewport, pivot, pivot, 10.0, 10.0, heading, nextHeading)
            val actual = screenPoint(landmark, next, nextHeading, 10.0)
            assertEquals(pivot.x, actual.x, 1e-6)
            assertEquals(pivot.y, actual.y, 1e-6)
        }
    }

    @Test fun simultaneousPinchRotationAndPanKeepLandmarkUnderFingers() {
        val landmark = PanGeometry.drag(tokyo, -80.0, 100.0, 10.0, 0.0)
        val before = screenPoint(landmark, tokyo, 20.0, 10.0)
        val after = PlanOffset(before.x + 60.0, before.y - 40.0)
        val viewport = PanGeometry.transformViewport(tokyo, tokyo, before, after, 10.0, 17.0, 20.0, 315.0)
        val actual = screenPoint(landmark, viewport, 315.0, 17.0)
        assertEquals(after.x, actual.x, 1e-6)
        assertEquals(after.y, actual.y, 1e-6)
    }

    @Test fun observerAndRingsFollowDragAtEveryHeading() {
        for (heading in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)) {
            val viewport = PanGeometry.drag(tokyo, 60.0, -120.0, 10.0, heading)
            val offset = PanGeometry.observerOffset(tokyo, viewport, heading)
            assertEquals(60.0, offset.x * 10.0, 1e-7)
            assertEquals(-120.0, -offset.y * 10.0, 1e-7)
        }
    }

    @Test fun resetViewportRestoresObserverToOrigin() {
        val offset = PanGeometry.observerOffset(tokyo, tokyo, 123.0)
        assertEquals(0.0, kotlin.math.abs(offset.x))
        assertEquals(0.0, kotlin.math.abs(offset.y))
    }

    @Test fun draggingDownMovesCenterAheadAtEveryHeading() {
        for (heading in listOf(0.0, 90.0, 180.0, 270.0, 359.0)) {
            val p = PanGeometry.drag(tokyo, 0.0, 100.0, 10.0, heading)
            assertEquals(10.0, GeoMath.distanceKm(tokyo.latitude, tokyo.longitude, p.latitude, p.longitude), 1e-8)
            assertEquals(0.0, Heading.delta(heading, GeoMath.bearingDeg(tokyo.latitude, tokyo.longitude, p.latitude, p.longitude)), 1e-8)
        }
    }

    @Test fun draggingRightMovesCenterToTheLeftOfHeading() {
        val p = PanGeometry.drag(tokyo, 50.0, 0.0, 10.0, 90.0)
        assertEquals(5.0, GeoMath.distanceKm(tokyo.latitude, tokyo.longitude, p.latitude, p.longitude), 1e-8)
        assertEquals(0.0, Heading.delta(0.0, GeoMath.bearingDeg(tokyo.latitude, tokyo.longitude, p.latitude, p.longitude)), 1e-8)
    }

    @Test fun ignoresInvalidScaleAndNoMovement() {
        assertEquals(tokyo, PanGeometry.drag(tokyo, 10.0, 5.0, 0.0, 0.0))
        assertEquals(tokyo, PanGeometry.drag(tokyo, 0.0, 0.0, 10.0, 0.0))
        assertEquals(tokyo, PanGeometry.drag(tokyo, Double.NaN, 0.0, 10.0, 0.0))
    }

    @Test fun wrapsLongitudeAcrossDateLine() {
        val p = PanGeometry.drag(MapCenter(0.0, 179.99), -100.0, 0.0, 10.0, 0.0)
        assertTrue(p.longitude < -179 && p.longitude >= -180)
    }
}
