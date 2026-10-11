import Foundation

/// 届いた測位結果を現在地として使うかを決める。
///
/// Android は GPS とネットワーク位置(基地局や Wi-Fi から推定した位置)の両方を受け取る。山では基地局が遠く、
/// ネットワーク位置の誤差は数百m〜数km になるため、そのまま使うと現在地が飛ぶ。iOS は OS が位置を統合するが、
/// 同じように精度の悪い位置が混ざることがあるので、同じ判定をする。直前に使った位置より精度(誤差の半径)が
/// はっきり悪い位置は捨てる。
///
/// 許す誤差は「直前の誤差 + `toleranceM` + 経過時間 × `speedMps`」とし、時間とともに広げる。直前の位置から
/// 歩いて動ける範囲より粗い位置は使わないということで、良い位置が長く届かないときだけ粗い位置も使う
/// (誤差 10m の後の誤差 800m の位置なら、6 分ほど良い位置が届かなかったとき)。
public final class LocationFilter {
    private let toleranceM: Double
    private let speedMps: Double
    private var lastTimeMs: Int64?
    private var lastAccuracyM: Double?

    public init(toleranceM: Double = 50, speedMps: Double = 2) {
        self.toleranceM = toleranceM
        self.speedMps = speedMps
    }

    /// `timeMs` は測位した時刻(ミリ秒)、`accuracyM` は水平方向の精度(m)。精度が分からない位置(nil や負の値)は使わない。
    /// 使うなら true を返し、次の判定の基準にする。
    public func accept(timeMs: Int64, accuracyM: Double?) -> Bool {
        guard let accuracyM, accuracyM >= 0 else { return false }
        if let prevTime = lastTimeMs, let prevAccuracy = lastAccuracyM {
            if timeMs <= prevTime { return false }
            let allowedM = prevAccuracy + toleranceM + Double(timeMs - prevTime) / 1000 * speedMps
            if accuracyM > allowedM { return false }
        }
        lastTimeMs = timeMs
        lastAccuracyM = accuracyM
        return true
    }
}
