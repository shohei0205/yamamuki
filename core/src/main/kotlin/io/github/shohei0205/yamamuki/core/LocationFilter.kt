package io.github.shohei0205.yamamuki.core

/**
 * 届いた測位結果を現在地として使うかを決める。
 *
 * Android は GPS とネットワーク位置(基地局や Wi-Fi から推定した位置)の両方を受け取る。山では基地局が遠く、
 * ネットワーク位置の誤差は数百m〜数km になるため、そのまま使うと現在地が飛ぶ。そこで、直前に使った位置より
 * 精度(誤差の半径)がはっきり悪い位置は捨てる。ただし良い位置が [staleMs] 以上届かないときは、現在地が
 * 止まったままにならないよう悪い位置も使う。
 */
class LocationFilter(
    private val staleMs: Long = 60_000,
    private val toleranceM: Double = 50.0,
) {
    private var lastTimeMs: Long? = null
    private var lastAccuracyM: Double? = null

    /**
     * [timeMs] は測位した時刻(ミリ秒)、[accuracyM] は水平方向の精度(m)。精度が分からない位置(null や負の値)は使わない。
     * 使うなら true を返し、次の判定の基準にする。
     */
    fun accept(timeMs: Long, accuracyM: Double?): Boolean {
        if (accuracyM == null || !(accuracyM >= 0.0)) return false
        val prevTime = lastTimeMs
        val prevAccuracy = lastAccuracyM
        if (prevTime != null && prevAccuracy != null) {
            if (timeMs <= prevTime) return false
            val worse = accuracyM > prevAccuracy + toleranceM
            if (worse && timeMs - prevTime < staleMs) return false
        }
        lastTimeMs = timeMs
        lastAccuracyM = accuracyM
        return true
    }
}
