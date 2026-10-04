import Foundation

/// 端末の向き(方位角)の計算。
public enum Heading {
    private static let directionNames = [
        "北", "北北東", "北東", "東北東", "東", "東南東", "南東", "南南東",
        "南", "南南西", "南西", "西南西", "西", "西北西", "北西", "北北西",
    ]

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

    /// 16方位の名前(北、北北東、…)。
    public static func directionName(_ deg: Double) -> String {
        directionNames[Int((normalize(deg) + 11.25) / 22.5) % 16]
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
