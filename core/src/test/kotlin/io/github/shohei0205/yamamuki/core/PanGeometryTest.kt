package io.github.shohei0205.yamamuki.core

import kotlin.test.*

class PanGeometryTest {
    private val tokyo = MapCenter(35.696, 139.814)
    /** 高さ 800dp の画面で 1km = 10dp になる表示範囲。 */
    private val range800 = (800.0 - DialGeometry.CHART_INSET_DP) / 10.0

    @Test fun returnPathIsStraightOnScreenWhileHeadingAndGpsChange() {
        for (offset in listOf(PlanOffset(12.0, -8.0), PlanOffset(-30.0, 20.0), PlanOffset(0.0, 0.0))) {
            for (targetHeading in listOf(10.0, 90.0, 180.0, 270.0)) {
                for (t in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                    val fraction = t * t * (3 - 2 * t)
                    val observer = PanGeometry.interpolateCenter(tokyo, MapCenter(35.7, 139.82), fraction)
                    val heading = Heading.normalize(350.0 + Heading.delta(350.0, targetHeading) * fraction)
                    val viewport = PanGeometry.returnViewport(observer, offset, heading, fraction)
                    val actual = PanGeometry.observerOffset(observer, viewport, heading)
                    assertEquals(offset.x * (1 - fraction), actual.x, 1e-7)
                    assertEquals(offset.y * (1 - fraction), actual.y, 1e-7)
                    if (t == 1.0) assertEquals(observer, viewport)
                }
            }
        }
    }

    @Test fun returnToLocationInterpolatesContinuouslyAndEndsExactlyAtTarget() {
        for ((from, to) in listOf(tokyo to MapCenter(35.75, 139.9), MapCenter(0.0, 179.9) to MapCenter(0.0, -179.9))) {
            val distance = GeoMath.distanceKm(from.latitude, from.longitude, to.latitude, to.longitude)
            assertEquals(from, PanGeometry.interpolateCenter(from, to, 0.0))
            assertEquals(to, PanGeometry.interpolateCenter(from, to, 1.0))
            for (fraction in listOf(0.25, 0.5, 0.75)) {
                val point = PanGeometry.interpolateCenter(from, to, fraction)
                assertEquals(distance * fraction, GeoMath.distanceKm(from.latitude, from.longitude, point.latitude, point.longitude), 1e-6)
            }
        }
        assertEquals(tokyo, PanGeometry.interpolateCenter(tokyo, tokyo, 0.5))
        assertEquals(0.0, Heading.normalize(350.0 + Heading.delta(350.0, 10.0) * 0.5), 1e-8)
    }

    @Test fun headingDragKeepsChosenPivotAcrossNorthAndRepeatedUpdates() {
        for (aroundCenter in listOf(false, true)) {
            var viewport = PanGeometry.drag(tokyo, -100.0, -100.0, 10.0, 359.0)
            var heading = 359.0
            val pivot = if (aroundCenter) PlanOffset(0.0, DialGeometry.ORIGIN_BOTTOM_DP - 800.0 / 2) else screenPoint(tokyo, viewport, heading, 10.0)
            val landmark = if (aroundCenter) PanGeometry.transformViewport(tokyo, viewport,
                pivot, PlanOffset(0.0, 0.0), 10.0, 10.0, heading, heading) else tokyo
            for (dx in listOf(-12.0, -60.0, 180.0, -360.0)) {
                val nextHeading = DialGeometry.swipedHeading(heading, dx, 360.0)
                viewport = PanGeometry.rotateViewport(tokyo, viewport, heading, nextHeading, range800, 800.0, aroundCenter)
                heading = nextHeading
                val actual = screenPoint(landmark, viewport, heading, 10.0)
                assertEquals(pivot.x, actual.x, 1e-6)
                assertEquals(pivot.y, actual.y, 1e-6)
            }
        }
    }

    @Test fun visibleBinocularsStayFixedEvenAfterPanning() {
        for (heading in listOf(45.0, 90.0, 270.0, 359.0)) {
            val viewport = PanGeometry.drag(tokyo, -100.0, -100.0, 10.0, heading)
            assertTrue(PanGeometry.isObserverVisible(tokyo, viewport, heading, range800, 360.0, 800.0))
            val before = screenPoint(tokyo, viewport, heading, 10.0)
            for (progress in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                val next = PanGeometry.northUpViewport(tokyo, viewport, heading, range800, 800.0, progress, aroundCenter = false)
                val after = screenPoint(tokyo, next, PanGeometry.northUpHeading(heading, progress), 10.0)
                assertEquals(before.x, after.x, 1e-6)
                assertEquals(before.y, after.y, 1e-6)
            }
            for ((dx, dy) in listOf(300.0 to -100.0, -300.0 to -100.0, 0.0 to -750.0, 0.0 to 100.0)) {
                val outside = PanGeometry.drag(tokyo, dx, dy, 10.0, heading)
                assertFalse(PanGeometry.isObserverVisible(tokyo, outside, heading, range800, 360.0, 800.0))
            }
        }
    }

    @Test fun northUpKeepsScreenCenterAfterPanningAtDifferentSizesAndRanges() {
        val panned = PanGeometry.drag(tokyo, 100.0, -50.0, 10.0, 30.0)
        for (viewport in listOf(tokyo, panned)) for (height in listOf(480.0, 900.0)) {
            for (range in listOf(10.0, 80.0)) for (heading in listOf(0.0, 45.0, 90.0, 180.0, 359.0)) {
                val scale = (height - DialGeometry.CHART_INSET_DP) / range
                val pivotY = DialGeometry.ORIGIN_BOTTOM_DP - height / 2
                val center = DialGeometry.project(
                    GeoMath.distanceKm(tokyo.latitude, tokyo.longitude, viewport.latitude, viewport.longitude),
                    GeoMath.bearingDeg(tokyo.latitude, tokyo.longitude, viewport.latitude, viewport.longitude), 0.0)
                val angle = Math.toRadians(heading)
                val east = -pivotY * kotlin.math.sin(angle) / scale
                val north = -pivotY * kotlin.math.cos(angle) / scale
                val landmark = PanGeometry.drag(tokyo, -(center.x + east), center.y + north, 1.0, 0.0)
                val before = screenPoint(landmark, viewport, heading, scale)
                assertEquals(0.0, before.x, 1e-6)
                assertEquals(pivotY, before.y, 1e-6)
                for (progress in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                    val animated = PanGeometry.northUpViewport(tokyo, viewport, heading, range, height, progress)
                    val position = screenPoint(landmark, animated, PanGeometry.northUpHeading(heading, progress), scale)
                    assertEquals(before.x, position.x, 1e-6)
                    assertEquals(before.y, position.y, 1e-6)
                    val normal = PanGeometry.northUpViewport(tokyo, tokyo, heading, range, height, progress, aroundCenter = false)
                    assertEquals(tokyo, normal)
                    val binoculars = screenPoint(tokyo, normal, PanGeometry.northUpHeading(heading, progress), scale)
                    assertEquals(0.0, binoculars.x, 1e-6)
                    assertEquals(0.0, binoculars.y, 1e-6)
                }
                val next = PanGeometry.northUpViewport(tokyo, viewport, heading, range, height)
                val after = screenPoint(landmark, next, 0.0, scale)
                assertEquals(before.x, after.x, 1e-6)
                assertEquals(before.y, after.y, 1e-6)
                assertEquals(next, PanGeometry.northUpViewport(tokyo, next, 0.0, range, height))
            }
        }
    }

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

    @Test fun northUpAnimationUsesShortestTurnAndStopsExactlyAtNorth() {
        assertEquals(45.0, PanGeometry.northUpHeading(90.0, 0.5), 1e-8)
        assertEquals(315.0, PanGeometry.northUpHeading(270.0, 0.5), 1e-8)
        assertEquals(359.5, PanGeometry.northUpHeading(359.0, 0.5), 1e-8)
        assertEquals(90.0, PanGeometry.northUpHeading(90.0, 0.0))
        assertEquals(0.0, PanGeometry.northUpHeading(359.0, 1.0))
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
