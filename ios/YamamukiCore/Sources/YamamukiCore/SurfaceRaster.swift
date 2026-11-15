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

    /// 補間の重みを整数で持つときの 1.0。
    private static let weightOne = 256

    /// 左上・右上・左下・右下の 4 画素(0xAARRGGBB)を、右への重み `wx` と下への重み `wy`(0〜`weightOne`)で混ぜる。
    /// 透明な画素の色が混ざらないよう、色は透明度を掛けてから混ぜる。整数で計算し、両 OS で同じ色にする。
    static func blend(_ c00: UInt32, _ c10: UInt32, _ c01: UInt32, _ c11: UInt32, _ wx: Int, _ wy: Int) -> UInt32 {
        if c00 == c10 && c00 == c01 && c00 == c11 { return c00 }
        let colors = [Int(c00), Int(c10), Int(c01), Int(c11)]
        let weights = [(weightOne - wx) * (weightOne - wy), wx * (weightOne - wy), (weightOne - wx) * wy, wx * wy]
        let alphas = (0..<4).map { (colors[$0] >> 24 & 0xFF) * weights[$0] }
        let alphaSum = alphas.reduce(0, +)
        if alphaSum == 0 { return 0 }
        let total = weightOne * weightOne
        let alpha = (alphaSum + total / 2) / total
        func channel(_ shift: Int) -> Int {
            ((0..<4).reduce(0) { $0 + (colors[$1] >> shift & 0xFF) * alphas[$1] } + alphaSum / 2) / alphaSum
        }
        return UInt32(alpha << 24 | channel(16) << 16 | channel(8) << 8 | channel(0))
    }

    /// `frame` の画像を作るのに要る、ズーム `z` のタイル。
    public static func tiles(_ frame: SurfaceFrame, z: Int) -> [SurfaceTile] {
        // 画像の角は、中心から半径の √2 倍の距離にある。
        let box = BoundingBox.around(frame.centerLat, frame.centerLon, radiusKm: frame.radiusKm * 2.0.squareRoot())
        return SurfaceTiles.covering(box, z: z)
    }

    /// `frame` の画像を作る。左上から行ごとに並べた一辺 `size` の画像で、1 画素(0xAARRGGBB)は `tile` が返す画像の
    /// 周りの 4 画素から滑らかに求める。
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

        // タイルをまたいで、世界全体の画素の位置(列・行)から色を読む。データの無い場所は透明(0)。
        var loaded: [Int: [UInt32]?] = [:]
        var currentKey = -1
        var current: [UInt32]?
        let worldPixels = (last + 1) * tilePixels
        func pixel(_ gx: Int, _ gy: Int) throws -> UInt32 {
            if gx < 0 || gy < 0 || gx >= worldPixels || gy >= worldPixels { return 0 }
            let tx = gx / tilePixels
            let ty = gy / tilePixels
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
            guard let image = current else { return 0 }
            return image[(gy - ty * tilePixels) * tilePixels + (gx - tx * tilePixels)]
        }

        for j in 0..<size {
            let b = j / grid
            let fy = (Double(j) + 0.5 - Double(b * grid)) / Double(grid)
            for i in 0..<size {
                let a = i / grid
                let fx = (Double(i) + 0.5 - Double(a * grid)) / Double(grid)
                let n = b * nodes + a
                let u = (us[n] * (1 - fx) + us[n + 1] * fx) * (1 - fy) + (us[n + nodes] * (1 - fx) + us[n + nodes + 1] * fx) * fy
                let v = (vs[n] * (1 - fx) + vs[n + 1] * fx) * (1 - fy) + (vs[n + nodes] * (1 - fx) + vs[n + nodes + 1] * fx) * fy
                // 画素の中心を基準に、周りの 4 画素から滑らかに求める(元の画素が四角く見えないように)。
                let gx = u * Double(worldPixels) - 0.5
                let gy = v * Double(worldPixels) - 0.5
                let x0 = Int(floor(gx))
                let y0 = Int(floor(gy))
                let wx = Int((gx - Double(x0)) * Double(weightOne))
                let wy = Int((gy - Double(y0)) * Double(weightOne))
                out[j * size + i] = blend(
                    try pixel(x0, y0), try pixel(x0 + 1, y0), try pixel(x0, y0 + 1), try pixel(x0 + 1, y0 + 1), wx, wy
                )
            }
        }
        return out
    }
}
