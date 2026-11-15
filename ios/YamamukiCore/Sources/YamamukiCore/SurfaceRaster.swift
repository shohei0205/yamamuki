// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation

/// 方位盤の背景に貼る地表の画像 1 枚の範囲。中心から東西南北へ `radiusKm` の正方形で、北が上。
/// 画像の中の位置は、山と同じく中心からの距離と方位で決める(方位盤の上で山とずれないように)。
public struct SurfaceFrame: Hashable, Sendable {
    public let centerLat: Double
    public let centerLon: Double
    public let radiusKm: Double

    public init(centerLat: Double, centerLon: Double, radiusKm: Double) {
        self.centerLat = centerLat
        self.centerLon = centerLon
        self.radiusKm = radiusKm
    }
}

/// 地表のタイルを、方位盤に貼る 1 枚の画像に並べ直す。
///
/// タイルは Web メルカトルで、方位盤は現在地からの距離と方位の平面図なので、タイルをそのまま貼ると継ぎ目がずれる。
/// そこで、表示している場所を中心にした北が上の画像を作っておき、方位盤ではそれを 1 枚だけ回して貼る。
/// 画像は、表示する場所や表示範囲が大きく変わったときだけ作り直す(`needsRender`)。
public enum SurfaceRaster {
    /// 画像の一辺の画素数。
    public static let size = 1280

    /// 画面のいちばん遠い端までの距離の、表示範囲(現在地から画面上端まで)に対する比。画面の角と、上端より上の余白の分。
    public static let reach = 1.2

    /// 作る画像の半径を、画面に要る半径よりどれだけ広くするか。少し動かしたくらいでは作り直さないための余裕。
    private static let margin = 1.5

    /// 画像の半径の刻み。拡縮のたびに作り直さないよう、段階にする。
    private static let minRadiusKm = 2.0
    private static let radiusStep = 2.0.squareRoot()

    /// 画像が、今の表示範囲に合う半径のこの倍より広くなったら、粗すぎるので作り直す。
    private static let tooWide = 2.0

    /// 緯度経度を正確に求める格子の間隔(画素)。間の画素は、格子の値から直線で求める。
    private static let grid = 32

    /// 表示範囲 `rangeKm` のときに作る画像の半径。
    public static func radiusKm(_ rangeKm: Double) -> Double {
        let needed = rangeKm * reach * margin
        var radius = minRadiusKm
        while radius < needed { radius *= radiusStep }
        return radius
    }

    /// 画像を作り直すか。まだ無いとき、画面が画像からはみ出すとき、画像が粗すぎるときに作り直す。
    /// `centerLat`・`centerLon` は画面の基準の位置(ヘディングアップでは現在地、手動位置モードでは動かした先)。
    public static func needsRender(_ frame: SurfaceFrame?, centerLat: Double, centerLon: Double, rangeKm: Double) -> Bool {
        guard let frame else { return true }
        let drift = GeoMath.distanceKm(frame.centerLat, frame.centerLon, centerLat, centerLon)
        if drift + rangeKm * reach > frame.radiusKm { return true }
        return frame.radiusKm > radiusKm(rangeKm) * tooWide
    }

    /// `frame` の画像の 1 画素の長さ(m)。
    public static func metersPerPixel(_ frame: SurfaceFrame, size: Int = SurfaceRaster.size) -> Double {
        frame.radiusKm * 2000 / Double(size)
    }

    /// `frame` の画像を作るのに読むズーム。画像の 1 画素より細かい段のうち、最も粗い段を選ぶ(読むタイルを増やさずに、
    /// 画像をぼかさない)。どの段も画像より粗いときは、最も細かい段。段が無ければ nil。
    public static func chooseLevel(_ levels: [SurfaceLevel], frame: SurfaceFrame, size: Int = SurfaceRaster.size) -> SurfaceLevel? {
        let target = metersPerPixel(frame, size: size)
        let coarseFirst = levels.sorted {
            SurfaceTiles.metersPerPixel($0, lat: frame.centerLat) > SurfaceTiles.metersPerPixel($1, lat: frame.centerLat)
        }
        return coarseFirst.first { SurfaceTiles.metersPerPixel($0, lat: frame.centerLat) <= target } ?? coarseFirst.last
    }

    /// `frame` の画像を作るのに要る、ズーム `z` のタイル。
    public static func tiles(_ frame: SurfaceFrame, z: Int) -> [SurfaceTile] {
        // 画像の角は、中心から半径の √2 倍の距離にある。
        let box = BoundingBox.around(frame.centerLat, frame.centerLon, radiusKm: frame.radiusKm * 2.0.squareRoot())
        return SurfaceTiles.covering(box, z: z)
    }

    /// `frame` の画像を作る。左上から行ごとに並べた一辺 `size` の画像で、1 画素は `tile` が返す値(0xAARRGGBB)をそのまま写す。
    ///
    /// - Parameter tile: `level` のタイルの画像(`SurfaceCompositor.compose` の結果。一辺は `SurfaceLevel.tilePixels`)。
    ///   データの無いタイルは nil を返す。その場所は透明(0)になる。1 枚のタイルにつき 1 回だけ呼ぶ。
    /// - Throws: タイルの画素数が `tilePixels` の 2 乗と合わないとき。
    public static func render(_ frame: SurfaceFrame, level: SurfaceLevel, size: Int = SurfaceRaster.size,
                              tile: (SurfaceTile) throws -> [UInt32]?) throws -> [UInt32] {
        guard size > 0 else { throw SurfaceDataError("画像の一辺 \(size) が正の数ではありません") }
        var out = [UInt32](repeating: 0, count: size * size)
        let tileCount = SurfaceTiles.tileCount(level.z)
        let last = Int(tileCount) - 1
        let tilePixels = level.tilePixels

        // 格子の点ごとに、世界全体を 0〜1 とした位置を正確に求める。
        let nodes = (size + grid - 1) / grid + 1
        var us = [Double](repeating: 0, count: nodes * nodes)
        var vs = [Double](repeating: 0, count: nodes * nodes)
        let kmPerPixel = frame.radiusKm * 2 / Double(size)
        for b in 0..<nodes {
            for a in 0..<nodes {
                let east = Double(a * grid) * kmPerPixel - frame.radiusKm
                let north = frame.radiusKm - Double(b * grid) * kmPerPixel
                let point = GeoMath.destination(
                    frame.centerLat, frame.centerLon, distanceKm: hypot(east, north), bearingDeg: degrees(atan2(east, north))
                )
                us[b * nodes + a] = SurfaceTiles.worldU(point.lon)
                vs[b * nodes + a] = SurfaceTiles.worldV(point.lat)
            }
        }

        var loaded: [Int: [UInt32]?] = [:]
        var currentKey = -1
        var current: [UInt32]?
        for j in 0..<size {
            let b = j / grid
            let fy = (Double(j) + 0.5 - Double(b * grid)) / Double(grid)
            for i in 0..<size {
                let a = i / grid
                let fx = (Double(i) + 0.5 - Double(a * grid)) / Double(grid)
                let n = b * nodes + a
                let u = (us[n] * (1 - fx) + us[n + 1] * fx) * (1 - fy) + (us[n + nodes] * (1 - fx) + us[n + nodes + 1] * fx) * fy
                let v = (vs[n] * (1 - fx) + vs[n + 1] * fx) * (1 - fy) + (vs[n + nodes] * (1 - fx) + vs[n + nodes + 1] * fx) * fy
                let wx = u * tileCount
                let wy = v * tileCount
                let tx = Int(floor(wx))
                let ty = Int(floor(wy))
                if tx < 0 || tx > last || ty < 0 || ty > last { continue }
                let key = ty * (last + 1) + tx
                if key != currentKey {
                    currentKey = key
                    if let known = loaded[key] {
                        current = known
                    } else {
                        let image = try tile(SurfaceTile(z: level.z, x: tx, y: ty))
                        if let image, image.count != tilePixels * tilePixels {
                            throw SurfaceDataError("タイルの画素数 \(image.count) が \(tilePixels * tilePixels) ではありません")
                        }
                        loaded[key] = .some(image)
                        current = image
                    }
                }
                guard let image = current else { continue }
                let px = min(Int((wx - Double(tx)) * Double(tilePixels)), tilePixels - 1)
                let py = min(Int((wy - Double(ty)) * Double(tilePixels)), tilePixels - 1)
                out[j * size + i] = image[py * tilePixels + px]
            }
        }
        return out
    }
}
