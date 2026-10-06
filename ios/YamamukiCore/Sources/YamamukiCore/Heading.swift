import Foundation

/// 端末の向き(方位角)の計算。
public enum Heading {
    /// 角度を 0〜360° に正規化する。
    public static func normalize(_ deg: Double) -> Double {
        let r = deg.truncatingRemainder(dividingBy: 360)
        return r < 0 ? r + 360 : r
    }

    /// a から b への最短の回転角(-180 以上 180 未満、時計回りが正)。
    public static func delta(_ fromDeg: Double, _ toDeg: Double) -> Double {
        let d = normalize(toDeg - fromDeg)
        return d >= 180 ? d - 360 : d
    }

    /// 16 方位の番号。0 が北で、時計回りに 1 が北北東、4 が東、…、15 が北北西。
    /// 方位の名前はアプリの文字列リソースにあり、この番号で引く。
    public static func directionIndex(_ deg: Double) -> Int {
        Int((normalize(deg) + 11.25) / 22.5) % 16
    }
}

/// 画面上の矩形(pt)。
public struct ScreenBox: Hashable, Sendable {
    public let left: Double
    public let top: Double
    public let right: Double
    public let bottom: Double

    public init(left: Double, top: Double, right: Double, bottom: Double) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public func intersects(_ other: ScreenBox) -> Bool {
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }
}
