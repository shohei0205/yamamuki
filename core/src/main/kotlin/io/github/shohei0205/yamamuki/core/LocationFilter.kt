package io.github.shohei0205.yamamuki.core

/**
 * 届いた測位結果を現在地として使うかを決める。
 *
 * Android は GPS とネットワーク位置(基地局や Wi-Fi から推定した位置)の両方を受け取る。山では基地局が遠く、
 * ネットワーク位置の誤差は数百m〜数km になるため、そのまま使うと現在地が飛ぶ。そこで、直前に使った位置より
 * 精度(誤差の半径)がはっきり悪い位置は捨てる。
 *
 * 許す誤差は「直前の誤差 + [toleranceM] + 経過時間 × [speedMps]」とし、時間とともに広げる。直前の位置から
 * 歩いて動ける範囲より粗い位置は使わないということで、良い位置が長く届かないときだけ粗い位置も使う
 * (誤差 10m の後の誤差 800m の位置なら、6 分ほど良い位置が届かなかったとき)。
 */
class LocationFilter(
    private val toleranceM: Double = 50.0,
    private val speedMps: Double = 2.0,
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
            val allowedM = prevAccuracy + toleranceM + (timeMs - prevTime) / 1000.0 * speedMps
            if (accuracyM > allowedM) return false
        }
        lastTimeMs = timeMs
        lastAccuracyM = accuracyM
        return true
    }
}
