package io.github.shohei0205.yamamuki.core

import kotlin.math.*

data class RingLabelAnchor(val x: Double, val y: Double, val angle: Double)

object RingLabelGeometry {
    /** 前回のラベルの方向を地図と同じだけ回す。画面座標では方位角の増加と逆向き。 */
    fun rotatedAngle(angle: Double?, previousHeading: Double?, heading: Double): Double? =
        if (angle == null || previousHeading == null) angle
        else angle - Math.toRadians(Heading.delta(previousHeading, heading))

    /** 全距離で共通の方向。前の方向で表示できる間は固定し、見切れたら中央へ向け直す。 */
    fun direction(cx: Double, cy: Double, left: Double, top: Double, right: Double, bottom: Double,
        previousAngle: Double? = null, minimumSpan: Double = 48.0, visibleCount: ((Double) -> Int)? = null): Double {
        fun visibleSpan(angle: Double): Double {
            var near = 0.0
            var far = Double.POSITIVE_INFINITY
            fun clip(origin: Double, delta: Double, low: Double, high: Double): Boolean {
                if (abs(delta) < 1e-9) return origin in low..high
                val a = (low - origin) / delta
                val b = (high - origin) / delta
                near = max(near, min(a, b))
                far = min(far, max(a, b))
                return far >= near
            }
            if (left >= right || top >= bottom || !clip(cx, cos(angle), left, right) || !clip(cy, sin(angle), top, bottom)) return 0.0
            return max(0.0, far - near)
        }
        if (previousAngle != null && previousAngle.isFinite() &&
            (visibleCount?.invoke(previousAngle)?.let { it >= 2 } ?: (visibleSpan(previousAngle) >= minimumSpan))) return previousAngle
        val dx = (left + right) / 2 - cx
        val dy = (top + bottom) / 2 - cy
        val next = if (hypot(dx, dy) < 1e-6) -PI / 2 else atan2(dy, dx)
        if (visibleCount != null && previousAngle != null && previousAngle.isFinite() && visibleCount(next) <= visibleCount(previousAngle)) return previousAngle
        return next
    }

    /** 円と共通の半直線の交点に文字の中心を置く。収まらない数字は別方向へずらさず省く。 */
    fun place(cx: Double, cy: Double, radius: Double, left: Double, top: Double,
        right: Double, bottom: Double, width: Double, height: Double, angle: Double): RingLabelAnchor? {
        if (radius <= 0 || !listOf(cx, cy, radius, left, top, right, bottom, width, height, angle).all { it.isFinite() }) return null
        val x = cx + radius * cos(angle)
        val y = cy + radius * sin(angle)
        if (x - width / 2 < left || x + width / 2 > right || y - height / 2 < top || y + height / 2 > bottom) return null
        return RingLabelAnchor(x, y, angle)
    }
}
