import Foundation

public enum GeoMath {
    public static let earthRadiusKm = 6371.0088

    /// 2点間の大円距離(km)。
    public static func distanceKm(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let dLat = radians(lat2 - lat1)
        let dLon = radians(lon2 - lon1)
        let a = pow(sin(dLat / 2), 2) + cos(radians(lat1)) * cos(radians(lat2)) * pow(sin(dLon / 2), 2)
        return 2 * earthRadiusKm * asin(min(1, sqrt(a)))
    }

    /// 地点1から地点2への初期方位角(真北 0°、時計回り、0〜360°)。
    public static func bearingDeg(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let phi1 = radians(lat1)
        let phi2 = radians(lat2)
        let dLon = radians(lon2 - lon1)
        let y = sin(dLon) * cos(phi2)
        let x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (degrees(atan2(y, x)) + 360).truncatingRemainder(dividingBy: 360)
    }
}

func radians(_ deg: Double) -> Double { deg * .pi / 180 }
func degrees(_ rad: Double) -> Double { rad * 180 / .pi }

/// 緯度経度の矩形。日付変更線をまたぐ範囲は扱わない。
public struct BoundingBox: Hashable, Sendable {
    public let south: Double
    public let west: Double
    public let north: Double
    public let east: Double

    public init(south: Double, west: Double, north: Double, east: Double) {
        self.south = south
        self.west = west
        self.north = north
        self.east = east
    }

    public func contains(_ lat: Double, _ lon: Double) -> Bool {
        (south...north).contains(lat) && (west...east).contains(lon)
    }

    /// 中心から半径 radiusKm の円を含む矩形。
    public static func around(_ lat: Double, _ lon: Double, radiusKm: Double) -> BoundingBox {
        let dLat = degrees(radiusKm / GeoMath.earthRadiusKm)
        let cosLat = max(cos(radians(lat)), 0.01)
        let dLon = min(degrees(radiusKm / (GeoMath.earthRadiusKm * cosLat)), 180)
        return BoundingBox(
            south: max(lat - dLat, -90),
            west: max(lon - dLon, -180),
            north: min(lat + dLat, 90),
            east: min(lon + dLon, 180)
        )
    }
}

/// キャッシュの単位となる緯度経度グリッドのタイル。
/// 取得済みかどうかをタイル単位で記録し、同じ範囲を何度も Overpass に問い合わせないようにする。
public struct Tile: Hashable, Codable, Sendable {
    public let latIndex: Int
    public let lonIndex: Int

    public init(latIndex: Int, lonIndex: Int) {
        self.latIndex = latIndex
        self.lonIndex = lonIndex
    }

    /// 0.5° ≒ 南北 55km。半径 100km の範囲で 5×6 枚程度。
    public static let sizeDeg = 0.5

    public var bounds: BoundingBox {
        BoundingBox(
            south: Double(latIndex) * Tile.sizeDeg,
            west: Double(lonIndex) * Tile.sizeDeg,
            north: Double(latIndex + 1) * Tile.sizeDeg,
            east: Double(lonIndex + 1) * Tile.sizeDeg
        )
    }

    public static func of(_ lat: Double, _ lon: Double) -> Tile {
        Tile(latIndex: Int(floor(lat / sizeDeg)), lonIndex: Int(floor(lon / sizeDeg)))
    }

    public static func covering(_ box: BoundingBox) -> [Tile] {
        let sw = of(box.south, box.west)
        let ne = of(box.north, box.east)
        return (sw.latIndex...ne.latIndex).flatMap { la in
            (sw.lonIndex...ne.lonIndex).map { lo in Tile(latIndex: la, lonIndex: lo) }
        }
    }

    /// タイル群をすべて含む最小の矩形。
    public static func union<C: Collection>(_ tiles: C) -> BoundingBox where C.Element == Tile {
        precondition(!tiles.isEmpty)
        return BoundingBox(
            south: Double(tiles.map(\.latIndex).min()!) * sizeDeg,
            west: Double(tiles.map(\.lonIndex).min()!) * sizeDeg,
            north: Double(tiles.map(\.latIndex).max()! + 1) * sizeDeg,
            east: Double(tiles.map(\.lonIndex).max()! + 1) * sizeDeg
        )
    }
}
