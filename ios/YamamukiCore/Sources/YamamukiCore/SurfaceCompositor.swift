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
        let gains: [Int]? = if hillshade != nil, let shade { gainTable(shade) } else { nil }
        let groundR = palette.ground >> 16 & 0xFF
        let groundG = palette.ground >> 8 & 0xFF
        let groundB = palette.ground & 0xFF
        let forestR = palette.forest >> 16 & 0xFF
        let forestG = palette.forest >> 8 & 0xFF
        let forestB = palette.forest & 0xFF
        let waterR = palette.water >> 16 & 0xFF
        let waterG = palette.water >> 8 & 0xFF
        let waterB = palette.water & 0xFF

        var out = [UInt32](repeating: 0, count: count)
        for i in 0..<count {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            if let forest {
                let m = Int(forest[i])
                a = 255
                r = mix(groundR, forestR, m)
                g = mix(groundG, forestG, m)
                b = mix(groundB, forestB, m)
                if let gains, let hillshade {
                    let gain = gains[Int(hillshade[i])]
                    r = scale(r, gain)
                    g = scale(g, gain)
                    b = scale(b, gain)
                }
            }
            if let water {
                let m = Int(water[i])
                if a == 255 {
                    r = mix(r, waterR, m)
                    g = mix(g, waterG, m)
                    b = mix(b, waterB, m)
                } else if m > 0 {
                    a = m
                    r = waterR
                    g = waterG
                    b = waterB
                }
            }
            out[i] = UInt32(a << 24 | r << 16 | g << 8 | b)
        }
        return out
    }

    /// 陰影の画素の値ごとの、明るさの倍率(`gainOne` が 1.0)。
    private static func gainTable(_ shade: SurfaceShade) -> [Int] {
        (0..<256).map { value in
            let gain = 1 + shade.strength * Double(value - shade.neutral) / Double(shade.neutral)
            return max(0, Int((gain * Double(gainOne)).rounded()))
        }
    }

    /// `from` から `to` へ、濃さ `m`(0〜255)の分だけ寄せた色。
    private static func mix(_ from: Int, _ to: Int, _ m: Int) -> Int { (from * (255 - m) + to * m + 127) / 255 }

    private static func scale(_ color: Int, _ gain: Int) -> Int { min(255, (color * gain + gainOne / 2) / gainOne) }
}
