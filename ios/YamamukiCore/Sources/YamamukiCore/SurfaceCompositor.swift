// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation

/// 地表データのレイヤーの種類。ID と役割(画素の読み方)の組は、地表の形式の版 1 で決まっている。
/// データセットや配色が変わっても同じ意味で使う。
public enum SurfaceLayerKind: String, CaseIterable, Sendable {
    /// 森林の濃さ。0 は森林なし、255 は全面が森林。
    case forest
    /// 水域(海・川・湖)の濃さ。0 は水域なし、255 は全面が水域。
    case water
    /// 標高から作った陰影。中立の値との差で、下に描いた色の明るさを変える。
    case hillshade

    public var id: String { rawValue }

    public var role: String {
        switch self {
        case .forest, .water: return "mask"
        case .hillshade: return "hillshade"
        }
    }

    /// SQLite の surface_layers の id と role から種類を決める。大文字と小文字を区別して比べる。
    /// 知らない ID と、ID と役割の組が決まりと違う行は nil(その種類のレイヤーとして扱わない)。
    public static func of(id: String?, role: String?) -> SurfaceLayerKind? {
        allCases.first { $0.id == id && $0.role == role }
    }
}

/// 地表を塗る色。どれも 0xRRGGBB。データに入っている既定の色ではなく、アプリの配色を渡す。
public struct SurfacePalette: Hashable, Sendable {
    /// 森林でも水域でもない地面の色。
    public let ground: Int
    public let forest: Int
    public let water: Int

    public init(ground: Int, forest: Int, water: Int) {
        self.ground = ground
        self.forest = forest
        self.water = water
    }

    /// 方位盤の背景に使う配色。地面は方位盤の地面と同じクリーム色で、森林と水域は山のアイコンや山名の札が
    /// 埋もれないように淡くする。
    public static let dial = SurfacePalette(ground: 0xFFF4D8, forest: 0xC5DDA8, water: 0xA8D4EE)
}

/// 陰影の掛け方。陰影の画素が `neutral` のとき明るさを変えず、差に `strength` を掛けた分だけ明るく・暗くする。
public struct SurfaceShade: Hashable, Sendable {
    public let neutral: Int
    public let strength: Double

    public init(neutral: Int, strength: Double) {
        self.neutral = neutral
        self.strength = strength
    }

    /// 陰影のレイヤーの設定(surface_layers の settings_json)を読む。
    /// JSON として読めない、neutral が 1〜255 の整数でない、strength が 0 以上の数でないときは nil。
    public static func parse(_ settingsJson: String) -> SurfaceShade? {
        guard let root = (try? JSONSerialization.jsonObject(with: Data(settingsJson.utf8))) as? [String: Any],
              let neutral = PeakData.jsonInt(root["neutral"]), (1...255).contains(neutral),
              let strength = PeakData.jsonNumber(root["strength"]), strength.isFinite, strength >= 0
        else { return nil }
        return SurfaceShade(neutral: neutral, strength: strength)
    }
}

/// 地表のタイル 1 枚ぶんの、森林・陰影・水域を 1 枚の画像に合成する。
/// 画像の復号はアプリ側で行い、ここには 1 画素 1 バイト(0〜255)の濃さを渡す。
/// 計算は整数で行い、Android 版と iOS 版で同じ色になるようにしている。
public enum SurfaceCompositor {
    /// 陰影の倍率を整数で持つときの 1.0。
    private static let gainOne = 1024

    /// 森林の濃さで地面の色から森林の色へ塗り、陰影で明るさを変え、その上に水域の色を濃さの分だけ重ねる。
    /// 水域の色には陰影を掛けない。
    ///
    /// 無いレイヤーは nil で渡す。森林が無い場所は地面を塗らず、水域だけを濃さを透明度にして描く(水域も無ければ透明)。
    /// 陰影が無いか `shade` が nil なら、明るさを変えない。
    ///
    /// - Returns: 左上から行ごとに並べた、一辺 `tilePixels` の画像。1 画素は 0xAARRGGBB(色に透明度を掛けていない)。
    /// - Throws: 渡した濃さの長さが `tilePixels` の 2 乗と合わないとき。
    public static func compose(
        tilePixels: Int,
        forest: [UInt8]?,
        hillshade: [UInt8]?,
        water: [UInt8]?,
        palette: SurfacePalette,
        shade: SurfaceShade?
    ) throws -> [UInt32] {
        guard tilePixels > 0 else { throw SurfaceDataError("タイルの一辺 \(tilePixels) が正の数ではありません") }
        let count = tilePixels * tilePixels
        for (name, layer) in [("forest", forest), ("hillshade", hillshade), ("water", water)] {
            if let layer, layer.count != count {
                throw SurfaceDataError("\(name) の画素数 \(layer.count) が \(count) ではありません")
            }
        }
        let painter = Painter(palette: palette, shade: hillshade != nil ? shade : nil)
        let ground = forest != nil ? 255 : 0
        var out = [UInt32](repeating: 0, count: count)
        for i in 0..<count {
            out[i] = painter.paint(
                ground: ground,
                forest: forest.map { Int($0[i]) } ?? 0,
                hillshade: hillshade.map { Int($0[i]) } ?? painter.neutral,
                water: water.map { Int($0[i]) } ?? 0
            )
        }
        return out
    }

    /// 1 画素ぶんの濃さから色を決める。`compose` と、輪郭を鋭くしながら画像を作る `SurfaceRaster.render` が使う。
    /// `shade` が nil なら、陰影で明るさを変えない。
    public struct Painter {
        private let gains: [Int]?
        /// 陰影の画素の、明るさを変えない値。陰影が無い画素に使う。
        public let neutral: Int
        private let ground: (r: Int, g: Int, b: Int)
        private let forest: (r: Int, g: Int, b: Int)
        private let water: (r: Int, g: Int, b: Int)

        public init(palette: SurfacePalette, shade: SurfaceShade?) {
            gains = shade.map { SurfaceCompositor.gainTable($0) }
            neutral = shade?.neutral ?? 128
            ground = (palette.ground >> 16 & 0xFF, palette.ground >> 8 & 0xFF, palette.ground & 0xFF)
            forest = (palette.forest >> 16 & 0xFF, palette.forest >> 8 & 0xFF, palette.forest & 0xFF)
            water = (palette.water >> 16 & 0xFF, palette.water >> 8 & 0xFF, palette.water & 0xFF)
        }

        /// 森林の濃さ `forest` で地面の色から森林の色へ塗り、陰影 `hillshade` で明るさを変え、その上に水域の色を濃さ `water` の
        /// 分だけ重ねる。水域の色には陰影を掛けない。`ground` は地面を塗る範囲の濃さ(0〜255。森林のレイヤーがある場所は 255)。
        /// 地面が無く水域も無い画素は透明(0)。地面が無く水域だけがあるときは、水域の色を濃さの分の透明度で返す。
        /// 返す色は 0xAARRGGBB(色に透明度を掛けていない)。
        public func paint(ground groundAlpha: Int, forest forestMask: Int, hillshade: Int, water waterMask: Int) -> UInt32 {
            if groundAlpha == 0 && waterMask == 0 { return 0 }
            var gr = 0
            var gg = 0
            var gb = 0
            if groundAlpha > 0 {
                gr = SurfaceCompositor.mix(ground.r, forest.r, forestMask)
                gg = SurfaceCompositor.mix(ground.g, forest.g, forestMask)
                gb = SurfaceCompositor.mix(ground.b, forest.b, forestMask)
                if let gains {
                    let gain = gains[hillshade]
                    gr = SurfaceCompositor.scale(gr, gain)
                    gg = SurfaceCompositor.scale(gg, gain)
                    gb = SurfaceCompositor.scale(gb, gain)
                }
            }
            if waterMask == 0 { return UInt32(groundAlpha << 24 | gr << 16 | gg << 8 | gb) }
            if groundAlpha == 255 {
                let r = SurfaceCompositor.mix(gr, water.r, waterMask)
                let g = SurfaceCompositor.mix(gg, water.g, waterMask)
                let b = SurfaceCompositor.mix(gb, water.b, waterMask)
                return UInt32(255 << 24 | r << 16 | g << 8 | b)
            }
            // 地面が部分的にしか無いところ(データの端)は、水域の下に地面を透かして重ねる。
            let alpha = (waterMask * 255 + groundAlpha * (255 - waterMask) + 127) / 255
            let denominator = alpha * 255
            func over(_ waterChannel: Int, _ groundChannel: Int) -> Int {
                (waterChannel * waterMask * 255 + groundChannel * groundAlpha * (255 - waterMask) + denominator / 2) / denominator
            }
            return UInt32(alpha << 24 | over(water.r, gr) << 16 | over(water.g, gg) << 8 | over(water.b, gb))
        }
    }

    /// 陰影の画素の値ごとの、明るさの倍率(`gainOne` が 1.0)。
    fileprivate static func gainTable(_ shade: SurfaceShade) -> [Int] {
        (0..<256).map { value in
            let gain = 1 + shade.strength * Double(value - shade.neutral) / Double(shade.neutral)
            return max(0, Int((gain * Double(gainOne)).rounded()))
        }
    }

    /// `from` から `to` へ、濃さ `m`(0〜255)の分だけ寄せた色。
    fileprivate static func mix(_ from: Int, _ to: Int, _ m: Int) -> Int { (from * (255 - m) + to * m + 127) / 255 }

    fileprivate static func scale(_ color: Int, _ gain: Int) -> Int { min(255, (color * gain + gainOne / 2) / gainOne) }
}
