package io.github.shohei0205.yamamuki.core

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 方位盤の平面図の計算。現在地を原点とし、端末を向けている方位を画面の上方向とする。
 * 距離は画面上で等倍(1km あたりの px が一定)に表す。
 */
object DialGeometry {
    const val TAPE_SPAN_DEG = 60.0

    /** Move the bearing tape with the finger: one screen width equals its displayed span. */
    fun swipedHeading(headingDeg: Double, dxPx: Double, widthPx: Double): Double {
        if (!dxPx.isFinite() || !widthPx.isFinite() || widthPx <= 0) return headingDeg
        return Heading.normalize(headingDeg - dxPx / widthPx * TAPE_SPAN_DEG)
    }

    /** ピンチで変えられる表示範囲(現在地から画面上端までの距離)。 */
    const val MIN_RANGE_KM = 2.0
    const val MAX_RANGE_KM = 80.0
    const val DEFAULT_RANGE_KM = 15.0

    /** 山データを取得する半径の段階。画面の角は上端より遠いので、表示範囲より広く取る。 */
    private val FETCH_RADII_KM = listOf(20.0, 50.0, 120.0)
    private const val FETCH_MARGIN = 1.4

    private val RING_STEPS_KM = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0)

    /**
     * 現在地から見た位置を画面上のオフセット(km 単位)に変換する。
     * x は右が正、y は上(向いている方向)が正。
     */
    fun project(distanceKm: Double, bearingDeg: Double, headingDeg: Double): PlanOffset {
        val rel = Math.toRadians(Heading.delta(headingDeg, bearingDeg))
        return PlanOffset(x = distanceKm * sin(rel), y = distanceKm * cos(rel))
    }

    /** ピンチの倍率を反映した表示範囲。指を広げる(zoom > 1)と近くを拡大する。 */
    fun zoomedRange(rangeKm: Double, zoom: Float): Double {
        if (zoom <= 0f || zoom.isNaN()) return rangeKm
        return (rangeKm / zoom).coerceIn(MIN_RANGE_KM, MAX_RANGE_KM)
    }

    /** 表示範囲に対して山データを取得する半径。段階的に広げ、少しの拡縮では取り直さない。 */
    fun fetchRadiusKm(rangeKm: Double): Double {
        val needed = rangeKm * FETCH_MARGIN
        return FETCH_RADII_KM.firstOrNull { it >= needed } ?: FETCH_RADII_KM.last()
    }

    /** 距離の同心円の間隔。表示範囲内に 2〜5 本入るきりのよい値。 */
    fun ringStepKm(rangeKm: Double): Double =
        RING_STEPS_KM.lastOrNull { rangeKm / it >= 2.0 } ?: RING_STEPS_KM.first()

    /** 同心円の距離ラベル(「500m」「5km」)。 */
    fun ringLabel(distanceKm: Double): String =
        if (distanceKm < 1.0) "${Math.round(distanceKm * 1000)}m" else "${formatKm(distanceKm)}km"

    private fun formatKm(km: Double): String =
        if (km == floor(km)) km.toLong().toString() else km.toString()

    /**
     * 画面上部の方位目盛り。向いている方位を中心に [spanDeg] の幅を [stepDeg] 刻みで返す。
     * offsetDeg は中心からのずれ(右が正)。
     */
    fun tapeTicks(headingDeg: Double, spanDeg: Double, stepDeg: Int = 5): List<TapeTick> {
        val half = spanDeg / 2
        val first = Math.ceil((headingDeg - half) / stepDeg).toInt() * stepDeg
        val ticks = mutableListOf<TapeTick>()
        var a = first
        while (a <= headingDeg + half) {
            ticks += TapeTick(angleDeg = Math.floorMod(a, 360), offsetDeg = a - headingDeg)
            a += stepDeg
        }
        return ticks
    }

    /** 目盛りの角度に対応する方位記号(N, NE, E, …)。45° の倍数以外は null。 */
    fun cardinalLabel(angleDeg: Int): String? = when (Math.floorMod(angleDeg, 360)) {
        0 -> "N"
        45 -> "NE"
        90 -> "E"
        135 -> "SE"
        180 -> "S"
        225 -> "SW"
        270 -> "W"
        315 -> "NW"
        else -> null
    }
}

data class PlanOffset(val x: Double, val y: Double)

data class TapeTick(val angleDeg: Int, val offsetDeg: Double)
