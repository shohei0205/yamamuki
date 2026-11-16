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

    /// 濃さ 255 を、補間の重みを掛けた値で持つときの値(重みの合計は `weightOne` の 2 乗)。
    private static let full = 255 * weightOne * weightOne

    /// 輪郭を鋭くするときの、境目にする濃さ(0〜255 の割合)。森林は中間の濃さを境にする。水域は川のように 1 画素より細い
    /// ものがデータの中で薄く(0.1〜0.3 ほど)入っているので、低い境目から立ち上げて、途切れずに残す。
    private static let forestEdge = 0.5
    private static let waterEdge = 0.175

    /// 立ち上げの始まりの下限(濃さの割合)。これより薄い場所は、データの中の薄いばらつきとして色を付けない。
    private static let forestFloor = 0.0
    private static let waterFloor = 0.05

    /// 輪郭を鋭くする強さ(境目の濃さの傾き)の上限。元の画素が画像の何画素に拡大されても、境目はこの傾きより急にはしない。
    private static let maxSharpness = 8.0

    /// 水域の傾きの上限。川の薄い濃さが 0.05〜0.3 の間で、なだらかに濃くなるようにする(大きな湖の岸は別に鋭くなる)。
    private static let waterMaxSharpness = 4.0

    /// 傾きを整数で持つときの 1.0。
    private static let gainOne = 256

    /// 1 枚のタイルの、森林・陰影・水域の濃さ(1 画素 1 バイト、0〜255)。無いレイヤーは nil。
    public struct TileLayers {
        public let forest: [UInt8]?
        public let hillshade: [UInt8]?
        public let water: [UInt8]?

        public init(forest: [UInt8]?, hillshade: [UInt8]?, water: [UInt8]?) {
            self.forest = forest
            self.hillshade = hillshade
            self.water = water
        }
    }

    /// 元の画素が画像の何画素に拡大されるか(1 以上)。1 画素より細かい段が無くて拡大するときだけ、1 より大きくなる。
    /// 輪郭を鋭くする傾きに使う。
    public static func magnification(_ frame: SurfaceFrame, level: SurfaceLevel, size: Int = SurfaceRaster.size) -> Double {
        let scale = SurfaceTiles.metersPerPixel(level, lat: frame.centerLat) / metersPerPixel(frame, size: size)
        return min(max(scale, 1.0), maxSharpness)
    }

    /// 補間した濃さ `value`(0〜`full`)の境目を `edge` にして、傾き `gain` で立ち上げた濃さ(0〜255)。`gain` が 1.0 なら補間のまま。
    /// 立ち上がりの始まりは、境目から傾きの逆数の半分だけ下がったところ(ただし `floor`(0〜1 の割合)より下にはしない)で、
    /// そこより薄い場所に色が付かない(水域が無い場所が青くならない)。
    private static func steepen(_ value: Int, edge: Double, gain: Int, floor: Double) -> Int {
        let unit = weightOne * weightOne
        if gain == gainOne { return (value + unit / 2) / unit }
        let half = 0.5 * Double(gainOne) / Double(gain)
        let low = max(floor, edge - half)
        let high = edge + half
        let lowValue = Int(Double(full) * low)
        let highValue = Int(Double(full) * high)
        let x: Int
        if highValue > lowValue {
            x = (value - lowValue) * full / (highValue - lowValue)
        } else {
            x = value >= lowValue ? full : 0
        }
        let clamped = min(max(x, 0), full)
        return (clamped + unit / 2) / unit
    }

    /// `frame` の画像を作るのに要る、ズーム `z` のタイル。
    public static func tiles(_ frame: SurfaceFrame, z: Int) -> [SurfaceTile] {
        // 画像の角は、中心から半径の √2 倍の距離にある。
        let box = BoundingBox.around(frame.centerLat, frame.centerLon, radiusKm: frame.radiusKm * 2.0.squareRoot())
        return SurfaceTiles.covering(box, z: z)
    }

    /// `frame` の画像を作る。左上から行ごとに並べた一辺 `size` の画像で、1 画素(0xAARRGGBB)は周りの 4 画素の濃さから求める。
    ///
    /// 色にする前の濃さを補間し、森林と水域は境目の濃さで切って輪郭を鋭くする。色を補間すると、元の画素より細かく作ったときに
    /// 輪郭がぼけるため。切る急さは拡大の倍率(`magnification`)で決め、拡大しないときは補間のままにする。
    /// 森林が無い場所(データの端)は地面を塗らず、色は混ぜずに透明度だけが変わる。陰影は鋭くしない。
    ///
    /// - Parameter tile: `level` のタイルの濃さ。データの無いタイルは nil を返す。その場所は透明(0)になる。1 枚のタイルにつき 1 回だけ呼ぶ。
    /// - Parameter sharpen: false なら輪郭を鋭くしない(補間だけ)。
    /// - Throws: タイルの画素数が `tilePixels` の 2 乗と合わないとき。
    public static func render(_ frame: SurfaceFrame, level: SurfaceLevel, palette: SurfacePalette, shade: SurfaceShade?,
                              size: Int = SurfaceRaster.size, sharpen: Bool = true,
                              tile: (SurfaceTile) throws -> TileLayers?) throws -> [UInt32] {
        guard size > 0 else { throw SurfaceDataError("画像の一辺 \(size) が正の数ではありません") }
        var out = [UInt32](repeating: 0, count: size * size)
        let tileCount = SurfaceTiles.tileCount(level.z)
        let last = Int(tileCount) - 1
        let tilePixels = level.tilePixels
        let painter = SurfaceCompositor.Painter(palette: palette, shade: shade)
        let gain = sharpen ? Int((magnification(frame, level: level, size: size) * Double(gainOne)).rounded()) : gainOne
        let unit = weightOne * weightOne

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

        // タイルをまたいで、世界全体の画素の位置(列・行)から濃さを読む。データの無い場所は、すべて 0。
        var loaded: [Int: TileLayers?] = [:]
        var currentKey = -1
        var current: TileLayers?
        let worldPixels = (last + 1) * tilePixels
        // 4 画素ぶんの、地面を塗る範囲・森林・陰影・水域の濃さ。
        var ground = [Int](repeating: 0, count: 4)
        var forest = [Int](repeating: 0, count: 4)
        var hillshade = [Int](repeating: 0, count: 4)
        var water = [Int](repeating: 0, count: 4)
        func read(_ k: Int, _ gx: Int, _ gy: Int) throws {
            ground[k] = 0
            forest[k] = 0
            hillshade[k] = painter.neutral
            water[k] = 0
            if gx < 0 || gy < 0 || gx >= worldPixels || gy >= worldPixels { return }
            let tx = gx / tilePixels
            let ty = gy / tilePixels
            let key = ty * (last + 1) + tx
            if key != currentKey {
                currentKey = key
                if let known = loaded[key] {
                    current = known
                } else {
                    let layers = try tile(SurfaceTile(z: level.z, x: tx, y: ty))
                    if let layers {
                        for (name, layer) in [("forest", layers.forest), ("hillshade", layers.hillshade), ("water", layers.water)] {
                            if let layer, layer.count != tilePixels * tilePixels {
                                throw SurfaceDataError("タイルの \(name) の画素数 \(layer.count) が \(tilePixels * tilePixels) ではありません")
                            }
                        }
                    }
                    loaded[key] = .some(layers)
                    current = layers
                }
            }
            guard let layers = current else { return }
            let index = (gy - ty * tilePixels) * tilePixels + (gx - tx * tilePixels)
            if let layer = layers.forest {
                ground[k] = 255
                forest[k] = Int(layer[index])
            }
            if let layer = layers.hillshade { hillshade[k] = Int(layer[index]) }
            if let layer = layers.water { water[k] = Int(layer[index]) }
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
                // 画素の中心を基準に、周りの 4 画素から求める。
                let gx = u * Double(worldPixels) - 0.5
                let gy = v * Double(worldPixels) - 0.5
                let x0 = Int(floor(gx))
                let y0 = Int(floor(gy))
                let wx = Int((gx - Double(x0)) * Double(weightOne))
                let wy = Int((gy - Double(y0)) * Double(weightOne))
                try read(0, x0, y0)
                try read(1, x0 + 1, y0)
                try read(2, x0, y0 + 1)
                try read(3, x0 + 1, y0 + 1)
                let w00 = (weightOne - wx) * (weightOne - wy)
                let w10 = wx * (weightOne - wy)
                let w01 = (weightOne - wx) * wy
                let w11 = wx * wy
                let groundValue = ground[0] * w00 + ground[1] * w10 + ground[2] * w01 + ground[3] * w11
                var forestValue = forest[0] * w00 + forest[1] * w10 + forest[2] * w01 + forest[3] * w11
                let waterValue = water[0] * w00 + water[1] * w10 + water[2] * w01 + water[3] * w11
                if groundValue == 0 && waterValue == 0 {
                    out[j * size + i] = 0
                    continue
                }
                var shadeValue = painter.neutral
                if groundValue > 0 {
                    // 地面を塗らない場所(データの端)の分は、色に混ぜない。
                    let h = hillshade[0] * w00 + hillshade[1] * w10 + hillshade[2] * w01 + hillshade[3] * w11
                    if groundValue < full {
                        forestValue = forestValue * full / groundValue
                        shadeValue = min(max((h - painter.neutral * (full - groundValue) / 255) * 255 / groundValue, 0), 255)
                    } else {
                        shadeValue = (h + unit / 2) / unit
                    }
                }
                out[j * size + i] = painter.paint(
                    ground: (groundValue + unit / 2) / unit,
                    forest: steepen(min(forestValue, full), edge: forestEdge, gain: gain, floor: forestFloor),
                    hillshade: shadeValue,
                    water: steepen(waterValue, edge: waterEdge, gain: min(gain, Int(waterMaxSharpness * Double(gainOne))), floor: waterFloor)
                )
            }
        }
        return out
    }
}
