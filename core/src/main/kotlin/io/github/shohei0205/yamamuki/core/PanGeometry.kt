package io.github.shohei0205.yamamuki.core

import kotlin.math.*

data class MapCenter(val latitude: Double, val longitude: Double)

object PanGeometry {
    /** Preserve the point under the fingers in the observer's terrain projection.
     * Midpoints are pixels relative to the canvas origin, with y pointing down.
     */
    fun transformViewport(observer: MapCenter, viewport: MapCenter,
        previousMidpoint: PlanOffset, midpoint: PlanOffset,
        oldScale: Double, newScale: Double, oldHeading: Double, newHeading: Double): MapCenter {
        if (oldScale <= 0 || newScale <= 0 ||
            !listOf(oldScale, newScale, oldHeading, newHeading, previousMidpoint.x,
                previousMidpoint.y, midpoint.x, midpoint.y).all { it.isFinite() }) return viewport
        fun world(p: PlanOffset, scale: Double, heading: Double): PlanOffset {
            val a = Math.toRadians(heading)
            return PlanOffset((p.x * cos(a) - p.y * sin(a)) / scale,
                (-p.x * sin(a) - p.y * cos(a)) / scale)
        }
        val center = DialGeometry.project(
            GeoMath.distanceKm(observer.latitude, observer.longitude, viewport.latitude, viewport.longitude),
            GeoMath.bearingDeg(observer.latitude, observer.longitude, viewport.latitude, viewport.longitude), 0.0)
        val before = world(previousMidpoint, oldScale, oldHeading)
        val after = world(midpoint, newScale, newHeading)
        return drag(observer, -(center.x + before.x - after.x), center.y + before.y - after.y, 1.0, 0.0)
    }

    /** Offset of the fixed observer from the viewport origin, in screen-oriented km (y up). */
    fun observerOffset(observer: MapCenter, viewport: MapCenter, headingDeg: Double): PlanOffset {
        val p = DialGeometry.project(
            GeoMath.distanceKm(observer.latitude, observer.longitude, viewport.latitude, viewport.longitude),
            GeoMath.bearingDeg(observer.latitude, observer.longitude, viewport.latitude, viewport.longitude), headingDeg)
        return PlanOffset(-p.x, -p.y)
    }

    /** Drag the map with the fingers; the viewing origin moves in the opposite direction. */
    fun drag(center: MapCenter, dxPx: Double, dyPx: Double, pxPerKm: Double, headingDeg: Double): MapCenter {
        if (!pxPerKm.isFinite() || pxPerKm <= 0 || !dxPx.isFinite() || !dyPx.isFinite()) return center
        val x = -dxPx / pxPerKm
        val y = dyPx / pxPerKm
        val distance = hypot(x, y)
        if (distance == 0.0) return center
        val bearing = Math.toRadians(headingDeg) + atan2(x, y)
        val angular = distance / GeoMath.EARTH_RADIUS_KM
        val lat = Math.toRadians(center.latitude)
        val lon = Math.toRadians(center.longitude)
        val nextLat = asin((sin(lat) * cos(angular) + cos(lat) * sin(angular) * cos(bearing)).coerceIn(-1.0, 1.0))
        val nextLon = lon + atan2(sin(bearing) * sin(angular) * cos(lat), cos(angular) - sin(lat) * sin(nextLat))
        // Terrain uses Web Mercator, so stop at its practical latitude limits.
        return MapCenter(Math.toDegrees(nextLat).coerceIn(-85.0, 85.0), (Math.toDegrees(nextLon) + 540) % 360 - 180)
    }
}
