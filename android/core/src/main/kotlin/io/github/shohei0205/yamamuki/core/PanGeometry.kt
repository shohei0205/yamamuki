package io.github.shohei0205.yamamuki.core

import kotlin.math.*

data class MapCenter(val latitude: Double, val longitude: Double)

object PanGeometry {
    /** 北が上とみなす角度の幅。 */
    const val NORTH_UP_TOLERANCE_DEG = 0.5

    /**
     * 手動位置モードでコンパスをタップしたとき、地図を端末の向きに合わせるか。
     * 北が上のときは端末の向きに合わせ、それ以外のときは北を上にする。合わせたあとは端末の向きに付いていかない。
     */
    fun compassTapFacesDevice(headingDeg: Double): Boolean =
        abs(Heading.delta(headingDeg, 0.0)) < NORTH_UP_TOLERANCE_DEG

    /** 方角が変わっても双眼鏡を画面上の直線に沿って描画原点へ戻す。offsetはkm、yは上向き。 */
    fun returnViewport(observer: MapCenter, initialOffset: PlanOffset, heading: Double, fraction: Double): MapCenter {
        val remaining = 1.0 - fraction.coerceIn(0.0, 1.0)
        return drag(observer, initialOffset.x * remaining, -initialOffset.y * remaining, 1.0, heading)
    }

    /** 描画される双眼鏡の中心が画面内にあるか。寸法はdp。 */
    fun isObserverVisible(observer: MapCenter, viewport: MapCenter, heading: Double,
        rangeKm: Double, canvasWidth: Double, canvasHeight: Double): Boolean {
        if (canvasWidth <= 0 || canvasHeight <= DialGeometry.CHART_INSET_DP || rangeKm <= 0) return false
        val scale = (canvasHeight - DialGeometry.CHART_INSET_DP) / rangeKm
        val offset = observerOffset(observer, viewport, heading)
        val x = canvasWidth / 2 + offset.x * scale
        val y = canvasHeight - DialGeometry.ORIGIN_BOTTOM_DP - offset.y * scale
        return x in 0.0..canvasWidth && y in DialGeometry.CHART_TOP_DP..canvasHeight
    }

    /** 双眼鏡または画面中央を固定して方角を変える。 */
    fun rotateViewport(observer: MapCenter, viewport: MapCenter, heading: Double, nextHeading: Double,
        rangeKm: Double, canvasHeight: Double, aroundCenter: Boolean): MapCenter {
        if (!canvasHeight.isFinite() || canvasHeight <= DialGeometry.CHART_INSET_DP || !rangeKm.isFinite() || rangeKm <= 0 || heading == nextHeading || (!aroundCenter && observer == viewport)) return viewport
        val scale = (canvasHeight - DialGeometry.CHART_INSET_DP) / rangeKm
        val offset = observerOffset(observer, viewport, heading)
        val pivot = if (aroundCenter) PlanOffset(0.0, DialGeometry.ORIGIN_BOTTOM_DP - canvasHeight / 2)
            else PlanOffset(offset.x * scale, -offset.y * scale)
        return transformViewport(observer, viewport, pivot, pivot, scale, scale, heading, nextHeading)
    }

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

/**
 * 地図の向きを [start] から、動いていく行き先(端末の向きなど)へ回す。
 * 回す量はタップした時点の最短の回転から始め、その後は行き先が動いた分だけ足していく。
 * 行き先が真後ろ(180° 先)をまたいでも、途中で逆回りして地図が半回転して見えることはない。
 * 最初の行き先が読めない(NaN など)ときは回さずに始め、読めた行き先へあとから回す。
 */
class HeadingTurn(private val start: Double, target: Double) {
    private var target = Heading.normalize(if (target.isFinite()) target else start)
    private var sweep = Heading.delta(start, this.target)

    /** 行き先を [target] に更新し、進み具合 [progress](0〜1)での向きを返す。終わり(1)では行き先そのものを返す。 */
    fun headingAt(target: Double, progress: Double): Double {
        if (target.isFinite()) {
            sweep += Heading.delta(this.target, target)
            this.target = Heading.normalize(target)
        }
        val t = progress.coerceIn(0.0, 1.0)
        return if (t == 1.0) this.target else Heading.normalize(start + sweep * t * t * (3 - 2 * t))
    }
}
