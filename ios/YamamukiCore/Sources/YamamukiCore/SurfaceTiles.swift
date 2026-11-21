// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation

/// 地表データ(方位盤の背景に描く森林・水域・陰影の画像)のタイル。Web メルカトルの XYZ タイルで、原点は左上、
/// x は東向き、y は南向き。形式は yamamuki-data の surface/formats/sqlite.md にある。
/// 山データのキャッシュの単位(`Tile`、緯度経度の格子)とは別のもの。
public struct SurfaceTile: Hashable, Sendable {
    public let z: Int
    public let x: Int
    public let y: Int

    public init(z: Int, x: Int, y: Int) {
        self.z = z
        self.x = x
        self.y = y
    }

    /// タイルの緯度経度の範囲。
    public var bounds: BoundingBox {
        let n = SurfaceTiles.tileCount(z)
        return BoundingBox(
            south: SurfaceTiles.latitude(Double(y + 1) / n),
            west: SurfaceTiles.longitude(Double(x) / n),
            north: SurfaceTiles.latitude(Double(y) / n),
            east: SurfaceTiles.longitude(Double(x + 1) / n)
        )
    }
}

/// 地表データに収録されたズーム 1 段。`tilePixels` は正方形のタイル画像の一辺の画素数。
public struct SurfaceLevel: Hashable, Sendable {
    public let z: Int
    public let tilePixels: Int

    public init(z: Int, tilePixels: Int) {
        self.z = z
        self.tilePixels = tilePixels
    }
}

public enum SurfaceTiles {
    /// Web メルカトルで表せる緯度の上限。これより極に近い緯度は、この値に丸める。
    public static let maxLatitude = 85.0511287798

    /// ズーム `z` の、1 辺のタイルの枚数(2 の z 乗)。
    public static func tileCount(_ z: Int) -> Double { Double(1 << z) }

    /// 経度を、世界全体を 0〜1 とした横の位置にする(西端が 0)。
    public static func worldU(_ lon: Double) -> Double { (lon + 180) / 360 }

    /// 緯度を、世界全体を 0〜1 とした縦の位置にする(北端が 0)。
    public static func worldV(_ lat: Double) -> Double {
        let clamped = min(max(lat, -maxLatitude), maxLatitude)
        return (1 - asinh(tan(radians(clamped))) / .pi) / 2
    }

    /// `worldU` の逆。
    public static func longitude(_ u: Double) -> Double { u * 360 - 180 }

    /// `worldV` の逆。
    public static func latitude(_ v: Double) -> Double { degrees(atan(sinh(.pi * (1 - 2 * v)))) }

    /// その地点を含む、ズーム `z` のタイル。世界の端をはみ出す位置は端のタイルにする(経度は折り返さない)。
    public static func tileAt(_ lat: Double, _ lon: Double, z: Int) -> SurfaceTile {
        let n = tileCount(z)
        let last = Int(n) - 1
        return SurfaceTile(
            z: z,
            x: min(max(Int(floor(worldU(lon) * n)), 0), last),
            y: min(max(Int(floor(worldV(lat) * n)), 0), last)
        )
    }

    /// `box` にかかる、ズーム `z` のタイル。北西から、行ごとに東へ並べる。
    public static func covering(_ box: BoundingBox, z: Int) -> [SurfaceTile] {
        let nw = tileAt(box.north, box.west, z: z)
        let se = tileAt(box.south, box.east, z: z)
        return (nw.y...se.y).flatMap { y in (nw.x...se.x).map { x in SurfaceTile(z: z, x: x, y: y) } }
    }

    /// 緯度 `lat` での、`level` の 1 画素の東西の長さ(m)。
    public static func metersPerPixel(_ level: SurfaceLevel, lat: Double) -> Double {
        let parallelM = 2 * .pi * GeoMath.earthRadiusKm * 1000 * cos(radians(min(max(lat, -maxLatitude), maxLatitude)))
        return parallelM / (tileCount(level.z) * Double(level.tilePixels))
    }

    /// 画面の 1 画素が `metersPerScreenPixel` m のときに描くズーム。画面より細かくならない段のうち最も細かい段を選ぶ。
    /// 画面に入るタイルの枚数を増やさないためで、等倍より少しぼける。すべての段が画面より細かいときは、最も粗い段。
    /// 段が無ければ nil。
    public static func chooseLevel(_ levels: [SurfaceLevel], lat: Double, metersPerScreenPixel: Double) -> SurfaceLevel? {
        let coarseFirst = levels.sorted { metersPerPixel($0, lat: lat) > metersPerPixel($1, lat: lat) }
        guard metersPerScreenPixel.isFinite, metersPerScreenPixel > 0 else { return coarseFirst.last }
        return coarseFirst.last { metersPerPixel($0, lat: lat) >= metersPerScreenPixel } ?? coarseFirst.first
    }
}
