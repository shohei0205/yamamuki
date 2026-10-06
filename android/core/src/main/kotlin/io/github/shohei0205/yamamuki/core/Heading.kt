package io.github.shohei0205.yamamuki.core

import kotlin.math.atan2
import kotlin.math.hypot

/** 端末の向き(方位角)の計算。 */
object Heading {
    private val DIRECTION_NAMES = listOf(
        "北", "北北東", "北東", "東北東", "東", "東南東", "南東", "南南東",
        "南", "南南西", "南西", "西南西", "西", "西北西", "北西", "北北西",
    )

    /** 角度を 0〜360° に正規化する。 */
    fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** a から b への最短の回転角(-180 以上 180 未満、時計回りが正)。 */
    fun delta(fromDeg: Double, toDeg: Double): Double {
        val d = normalize(toDeg - fromDeg)
        return if (d >= 180.0) d - 360.0 else d
    }

    /** 16方位の名前(北、北北東、…)。 */
    fun directionName(deg: Double): String =
        DIRECTION_NAMES[((normalize(deg) + 11.25) / 22.5).toInt() % 16]

    /**
     * Android の回転行列(端末座標→世界座標 [東, 北, 上]、行優先 3×3)から、端末を向けている方位角を求める。
     *
     * 端末を立てて構えたときは背面(-Z)の向き、水平に持ったときは上端(+Y)の向きを使う。
     * 両者の水平成分は同じ向きになるため、Y - Z の水平成分を使えば途中の傾きでも向きが連続する。
     * 真上/真下を向いていて水平成分が無い場合は null。
     */
    fun azimuthFromRotationMatrix(r: FloatArray): Double? {
        require(r.size >= 9)
        val east = (r[1] - r[2]).toDouble()
        val north = (r[4] - r[5]).toDouble()
        if (hypot(east, north) < 1e-3) return null
        return normalize(Math.toDegrees(atan2(east, north)))
    }
}

/**
 * 方位角のぶれを抑える指数平滑。359°→1° のような 0° をまたぐ変化も最短方向に追従する。
 * [alpha] は新しい値の重み(0〜1)。大きいほど反応が速い。
 */
class HeadingFilter(private val alpha: Double = 0.15) {
    private var current: Double? = null

    fun update(rawDeg: Double): Double {
        val prev = current
        val next = if (prev == null) Heading.normalize(rawDeg) else Heading.normalize(prev + alpha * Heading.delta(prev, rawDeg))
        current = next
        return next
    }

    companion object {
        /** 既定の [alpha](0.15)が前提にしている、センサーの値が届く間隔(ミリ秒)。毎秒 50 回。 */
        const val BASE_PERIOD_MS = 20.0

        /**
         * 値が [periodMs] ごとに届くときの [alpha]。届く間隔を変えても、追いつくまでの時間が
         * 既定(20 ミリ秒ごとに 0.15)と同じになるようにする。
         */
        fun alphaForPeriod(periodMs: Double): Double =
            1.0 - Math.pow(1.0 - 0.15, periodMs.coerceAtLeast(1.0) / BASE_PERIOD_MS)
    }
}
