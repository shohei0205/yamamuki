package io.github.shohei0205.yamamuki.core

import kotlin.math.*

data class RingLabelAnchor(val x: Double, val y: Double, val angle: Double)

object RingLabelGeometry {
    /** 寸法は同じ画面単位。ラベル全体が収まる円弧を選び、前回の角度を優先する。 */
    fun place(cx: Double, cy: Double, radius: Double, left: Double, top: Double,
        right: Double, bottom: Double, width: Double, height: Double, gap: Double,
        previousAngle: Double? = null): RingLabelAnchor? {
        if (radius <= 0 || !listOf(cx, cy, radius, left, top, right, bottom, width, height, gap).all { it.isFinite() }) return null
        val l = left + width / 2
        val r = right - width / 2
        val t = top + height / 2
        val b = bottom - height / 2
        if (l > r || t > b) return null
        fun fits(p: RingLabelAnchor) = p.x >= l - 1e-7 && p.x <= r + 1e-7 && p.y >= t - 1e-7 && p.y <= b + 1e-7
        fun point(a: Double) = RingLabelAnchor(cx + radius * cos(a), cy + radius * sin(a), a)
        val above = RingLabelAnchor(cx, cy - radius - height / 2 - gap, -PI / 2)
        if (fits(above)) return above
        previousAngle?.takeIf { it.isFinite() }?.let { if (fits(point(it))) return point(it) }
        val cuts = mutableListOf(0.0, 2 * PI)
        fun add(a: Double) { cuts += (a + 2 * PI) % (2 * PI) }
        for (x in listOf(l, r)) {
            val ratio = (x - cx) / radius
            if (ratio in -1.0..1.0) { val a = acos(ratio); add(a); add(-a) }
        }
        for (y in listOf(t, b)) {
            val ratio = (y - cy) / radius
            if (ratio in -1.0..1.0) { val a = asin(ratio); add(a); add(PI - a) }
        }
        val intervals = cuts.distinct().sorted().zipWithNext().filter { fits(point((it.first + it.second) / 2)) }.toMutableList()
        // 0度をまたぐ可視円弧を一つにつなげる。
        if (intervals.size > 1 && intervals.first().first == 0.0 && intervals.last().second == 2 * PI) {
            val first = intervals.removeAt(0)
            val last = intervals.removeAt(intervals.lastIndex)
            intervals += last.first to (first.second + 2 * PI)
        }
        val longest = intervals.maxByOrNull { it.second - it.first } ?: return null
        return point((longest.first + longest.second) / 2)
    }
}
