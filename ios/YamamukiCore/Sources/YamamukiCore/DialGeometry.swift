import Foundation

/// 方位盤の平面図の計算。現在地を原点とし、端末を向けている方位を画面の上方向とする。
/// 距離は画面上で等倍(1km あたりの pt が一定)に表す。
public enum DialGeometry {
    /// ピンチで変えられる表示範囲(現在地から画面上端までの距離)。
    public static let minRangeKm = 2.0
    public static let maxRangeKm = 80.0
    public static let defaultRangeKm = 15.0

    /// 山データを取得する半径の段階。画面の角は上端より遠いので、表示範囲より広く取る。
    private static let fetchRadiiKm = [20.0, 50.0, 120.0]
    private static let fetchMargin = 1.4

    private static let ringStepsKm = [0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0]

    /// 現在地から見た位置を画面上のオフセット(km 単位)に変換する。
    /// x は右が正、y は上(向いている方向)が正。
    public static func project(distanceKm: Double, bearingDeg: Double, headingDeg: Double) -> PlanOffset {
        let rel = radians(Heading.delta(headingDeg, bearingDeg))
        return PlanOffset(x: distanceKm * sin(rel), y: distanceKm * cos(rel))
    }

    /// ピンチの倍率を反映した表示範囲。指を広げる(zoom > 1)と近くを拡大する。
    public static func zoomedRange(_ rangeKm: Double, zoom: Double) -> Double {
        if zoom <= 0 || zoom.isNaN { return rangeKm }
        return min(max(rangeKm / zoom, minRangeKm), maxRangeKm)
    }

    /// 表示範囲に対して山データを取得する半径。段階的に広げ、少しの拡縮では取り直さない。
    public static func fetchRadiusKm(_ rangeKm: Double) -> Double {
        let needed = rangeKm * fetchMargin
        return fetchRadiiKm.first { $0 >= needed } ?? fetchRadiiKm.last!
    }

    /// 距離の同心円の間隔。表示範囲内に 2〜5 本入るきりのよい値。
    public static func ringStepKm(_ rangeKm: Double) -> Double {
        ringStepsKm.last { rangeKm / $0 >= 2 } ?? ringStepsKm.first!
    }

    /// 同心円の距離ラベル(「500m」「5km」)。
    public static func ringLabel(_ distanceKm: Double) -> String {
        if distanceKm < 1 { return "\(Int((distanceKm * 1000).rounded()))m" }
        let km = distanceKm == floor(distanceKm) ? String(Int(distanceKm)) : String(distanceKm)
        return "\(km)km"
    }

    /// 画面上部の方位目盛り。向いている方位を中心に [spanDeg] の幅を [stepDeg] 刻みで返す。
    /// offsetDeg は中心からのずれ(右が正)。
    public static func tapeTicks(headingDeg: Double, spanDeg: Double, stepDeg: Int = 5) -> [TapeTick] {
        let half = spanDeg / 2
        var a = Int(ceil((headingDeg - half) / Double(stepDeg))) * stepDeg
        var ticks: [TapeTick] = []
        while Double(a) <= headingDeg + half {
            ticks.append(TapeTick(angleDeg: floorMod(a, 360), offsetDeg: Double(a) - headingDeg))
            a += stepDeg
        }
        return ticks
    }

    /// 目盛りの角度に対応する方位記号(N, NE, E, …)。45° の倍数以外は nil。
    public static func cardinalLabel(_ angleDeg: Int) -> String? {
        switch floorMod(angleDeg, 360) {
        case 0: return "N"
        case 45: return "NE"
        case 90: return "E"
        case 135: return "SE"
        case 180: return "S"
        case 225: return "SW"
        case 270: return "W"
        case 315: return "NW"
        default: return nil
        }
    }

    private static func floorMod(_ a: Int, _ n: Int) -> Int {
        let r = a % n
        return r < 0 ? r + n : r
    }
}

public struct PlanOffset: Hashable, Sendable {
    public let x: Double
    public let y: Double
}

public struct TapeTick: Hashable, Sendable {
    public let angleDeg: Int
    public let offsetDeg: Double
}
