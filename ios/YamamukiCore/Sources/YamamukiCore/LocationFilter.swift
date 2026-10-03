import Foundation

/// 届いた測位結果を現在地として使うかを決める。
///
/// Android は GPS とネットワーク位置(基地局や Wi-Fi から推定した位置)の両方を受け取る。山では基地局が遠く、
/// ネットワーク位置の誤差は数百m〜数km になるため、そのまま使うと現在地が飛ぶ。iOS は OS が位置を統合するが、
/// 同じように精度の悪い位置が混ざることがあるので、同じ判定をする。直前に使った位置より精度(誤差の半径)が
/// はっきり悪い位置は捨てる。ただし良い位置が `staleMs` 以上届かないときは、現在地が止まったままに
/// ならないよう悪い位置も使う。
public final class LocationFilter {
    private let staleMs: Int64
    private let toleranceM: Double
    private var lastTimeMs: Int64?
    private var lastAccuracyM: Double?

    public init(staleMs: Int64 = 60_000, toleranceM: Double = 50) {
        self.staleMs = staleMs
        self.toleranceM = toleranceM
    }

    /// `timeMs` は測位した時刻(ミリ秒)、`accuracyM` は水平方向の精度(m)。精度が分からない位置(nil や負の値)は使わない。
    /// 使うなら true を返し、次の判定の基準にする。
    public func accept(timeMs: Int64, accuracyM: Double?) -> Bool {
        guard let accuracyM, accuracyM >= 0 else { return false }
        if let prevTime = lastTimeMs, let prevAccuracy = lastAccuracyM {
            if timeMs <= prevTime { return false }
            let worse = accuracyM > prevAccuracy + toleranceM
            if worse && timeMs - prevTime < staleMs { return false }
        }
        lastTimeMs = timeMs
        lastAccuracyM = accuracyM
        return true
    }
}
