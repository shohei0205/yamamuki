import Foundation

/// OSM の山頂ノード1件。
public struct Mountain: Codable, Hashable, Sendable {
    public let osmId: Int64
    public let name: String
    public let latitude: Double
    public let longitude: Double
    /// 標高(m)。OSM に ele タグが無い、または解釈できない場合は nil。
    public let elevationM: Double?

    public init(osmId: Int64, name: String, latitude: Double, longitude: Double, elevationM: Double?) {
        self.osmId = osmId
        self.name = name
        self.latitude = latitude
        self.longitude = longitude
        self.elevationM = elevationM
    }
}

/// 現在地から見た山。距離と方位角(真北基準、時計回り 0〜360°)を持つ。
public struct NearbyMountain: Hashable, Sendable, Identifiable {
    public let mountain: Mountain
    public let distanceKm: Double
    public let bearingDeg: Double

    public var id: Int64 { mountain.osmId }

    public init(mountain: Mountain, distanceKm: Double, bearingDeg: Double) {
        self.mountain = mountain
        self.distanceKm = distanceKm
        self.bearingDeg = bearingDeg
    }
}

/// 方位盤で山アイコンの色と形を分ける標高の区分。
public enum ElevationClass: Sendable {
    /// 1000m 未満。標高不明もここに含める。
    case low
    /// 1000m 以上 2000m 未満。
    case middle
    /// 2000m 以上。
    case high
}

extension Mountain {
    /// ([latitude], [longitude]) から見たこの山の距離と方位。
    public func seen(fromLatitude lat: Double, longitude lon: Double) -> NearbyMountain {
        NearbyMountain(
            mountain: self,
            distanceKm: GeoMath.distanceKm(lat, lon, latitude, longitude),
            bearingDeg: GeoMath.bearingDeg(lat, lon, latitude, longitude)
        )
    }

    public var elevationClass: ElevationClass {
        guard let ele = elevationM else { return .low }
        if ele < 1000 { return .low }
        if ele < 2000 { return .middle }
        return .high
    }

    /// 詳細表示の標高。「1,212 m」。不明なら nil。
    public var elevationText: String? { YamamukiCore.elevationText(elevationM) }

    /// 標高が [minElevationM] 以上か。0 以下なら絞り込まない。
    /// 絞り込むときは、標高が不明な山は基準を満たすか分からないので除く。
    public func meetsMinElevation(_ minElevationM: Int) -> Bool {
        if minElevationM <= 0 { return true }
        guard let ele = elevationM else { return false }
        return ele >= Double(minElevationM)
    }
}

/// 詳細表示の標高。「1,212 m」。不明なら nil で、「不明」の文言はアプリの文字列リソースで出す。
/// 山と現在地で共通に使う。
public func elevationText(_ elevationM: Double?) -> String? {
    guard let ele = elevationM else { return nil }
    return "\(groupedInteger(Int(ele.rounded()))) m"
}

/// 詳細表示の緯度や経度の数値の部分。「35.36056°」。南緯・西経でも正の数にする。
/// 「北緯」「東経」などの語はアプリの文字列リソースで付ける。
public func degreeText(_ deg: Double) -> String { String(format: "%.5f°", abs(deg)) }

/// 詳細表示の距離。1km 未満は「850 m」、以上は「12.3 km」。
public func distanceText(_ distanceKm: Double) -> String {
    if distanceKm < 1 {
        return "\(Int((distanceKm * 1000).rounded())) m"
    }
    let s = String(format: "%.1f", distanceKm)
    let parts = s.split(separator: ".")
    return "\(groupedInteger(Int(parts[0]) ?? 0)).\(parts[1]) km"
}

/// キャッシュ容量の表示。「820 KB」「1.3 MB」。
public func byteSizeText(_ bytes: Int64) -> String {
    if bytes < 1024 { return "\(bytes) B" }
    if bytes < 1024 * 1024 { return "\(Int((Double(bytes) / 1024).rounded())) KB" }
    return String(format: "%.1f MB", Double(bytes) / (1024 * 1024))
}

/// 3 桁ごとにカンマを入れた整数(端末の地域設定によらない)。
public func groupedInteger(_ value: Int) -> String {
    let digits = String(value.magnitude)
    var out = ""
    for (i, c) in digits.enumerated() {
        if i > 0 && (digits.count - i) % 3 == 0 { out.append(",") }
        out.append(c)
    }
    return value < 0 ? "-" + out : out
}

/// 現在地が山頂にいるとみなす水平距離。山頂に着いてから使う想定なので狭くとる
/// (屋外の GPS の水平誤差 5〜20m に少し余裕を持たせた値)。標高は GPS の誤差が大きいので判定に使わない。
public let summitRadiusKm = 0.03

/// 現在地から [radiusKm] 以内にある山のうち、いちばん近いもの。無ければ nil。
public func summitAt(_ mountains: [NearbyMountain], radiusKm: Double = summitRadiusKm) -> NearbyMountain? {
    mountains.filter { $0.distanceKm <= radiusKm }.min { $0.distanceKm < $1.distanceKm }
}
