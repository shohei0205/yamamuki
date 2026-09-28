import Foundation

/// 山データを事前ダウンロードする単位としての都道府県。
/// 範囲は境界を囲む矩形なので、隣の県の山も一部含む(県境付近から見える山なので、むしろ都合がよい)。
public struct Prefecture: Hashable, Identifiable, Sendable {
    /// JIS X 0401 の都道府県コード(北海道 = 1 〜 沖縄県 = 47)。
    public let code: Int
    public let name: String
    /// 本土(県庁所在地を含む範囲)と、山のある主な離島の矩形。
    public let areas: [BoundingBox]

    public var id: Int { code }

    public init(code: Int, name: String, areas: [BoundingBox]) {
        self.code = code
        self.name = name
        self.areas = areas
    }

    /// ダウンロードするタイル。
    public var tiles: [Tile] {
        var seen = Set<Tile>()
        return areas.flatMap { Tile.covering($0) }.filter { seen.insert($0).inserted }
    }

    /// 一覧で見出しにする地方。
    public var region: String {
        switch code {
        case 1...7: return "北海道・東北"
        case 8...14: return "関東"
        case 15...23: return "中部"
        case 24...30: return "近畿"
        case 31...35: return "中国"
        case 36...39: return "四国"
        default: return "九州・沖縄"
        }
    }

    public static func byCode(_ code: Int) -> Prefecture? { all.first { $0.code == code } }

    /// 本土の矩形は japanmap (Apache-2.0、国土数値情報をもとにした簡略な境界) から求め、0.01° 単位で外側に丸めた。
    /// 離島は東京都の伊豆諸島、兵庫県の淡路島、島根県の隠岐、香川県の小豆島、長崎県の対馬・五島列島、
    /// 鹿児島県の屋久島・奄美大島、沖縄県の石垣島・西表島を足した(小笠原諸島などは含まない)。
    /// Android 版の core/Prefecture.kt と同じ値。
    public static let all: [Prefecture] = [
        Prefecture(code: 1, name: "北海道", areas: [.init(south: 41.39, west: 139.77, north: 45.53, east: 145.83)]),
        Prefecture(code: 2, name: "青森県", areas: [.init(south: 40.21, west: 139.86, north: 41.55, east: 141.69)]),
        Prefecture(code: 3, name: "岩手県", areas: [.init(south: 38.74, west: 140.66, north: 40.45, east: 142.08)]),
        Prefecture(code: 4, name: "宮城県", areas: [.init(south: 37.77, west: 140.28, north: 39.00, east: 141.68)]),
        Prefecture(code: 5, name: "秋田県", areas: [.init(south: 38.87, west: 139.70, north: 40.51, east: 140.99)]),
        Prefecture(code: 6, name: "山形県", areas: [.init(south: 37.73, west: 139.54, north: 39.13, east: 140.65)]),
        Prefecture(code: 7, name: "福島県", areas: [.init(south: 36.78, west: 139.17, north: 37.98, east: 141.05)]),
        Prefecture(code: 8, name: "茨城県", areas: [.init(south: 35.73, west: 139.68, north: 36.94, east: 140.86)]),
        Prefecture(code: 9, name: "栃木県", areas: [.init(south: 36.19, west: 139.33, north: 37.16, east: 140.30)]),
        Prefecture(code: 10, name: "群馬県", areas: [.init(south: 35.97, west: 138.40, north: 37.06, east: 139.68)]),
        Prefecture(code: 11, name: "埼玉県", areas: [.init(south: 35.75, west: 138.71, north: 36.28, east: 139.90)]),
        Prefecture(code: 12, name: "千葉県", areas: [.init(south: 34.89, west: 139.75, north: 36.10, east: 140.87)]),
        Prefecture(code: 13, name: "東京都", areas: [.init(south: 35.49, west: 138.94, north: 35.90, east: 139.92), .init(south: 33.05, west: 139.10, north: 34.80, east: 139.90)]),
        Prefecture(code: 14, name: "神奈川県", areas: [.init(south: 35.13, west: 138.92, north: 35.67, east: 139.80)]),
        Prefecture(code: 15, name: "新潟県", areas: [.init(south: 36.73, west: 137.63, north: 38.55, east: 139.90)]),
        Prefecture(code: 16, name: "富山県", areas: [.init(south: 36.27, west: 136.77, north: 36.98, east: 137.77)]),
        Prefecture(code: 17, name: "石川県", areas: [.init(south: 36.06, west: 136.24, north: 37.53, east: 137.36)]),
        Prefecture(code: 18, name: "福井県", areas: [.init(south: 35.33, west: 135.45, north: 36.30, east: 136.84)]),
        Prefecture(code: 19, name: "山梨県", areas: [.init(south: 35.16, west: 138.18, north: 35.97, east: 139.14)]),
        Prefecture(code: 20, name: "長野県", areas: [.init(south: 35.19, west: 137.32, north: 37.03, east: 138.75)]),
        Prefecture(code: 21, name: "岐阜県", areas: [.init(south: 35.13, west: 136.28, north: 36.46, east: 137.65)]),
        Prefecture(code: 22, name: "静岡県", areas: [.init(south: 34.59, west: 137.48, north: 35.65, east: 139.16)]),
        Prefecture(code: 23, name: "愛知県", areas: [.init(south: 34.57, west: 136.67, north: 35.42, east: 137.84)]),
        Prefecture(code: 24, name: "三重県", areas: [.init(south: 33.72, west: 135.86, north: 35.26, east: 136.94)]),
        Prefecture(code: 25, name: "滋賀県", areas: [.init(south: 34.78, west: 135.77, north: 35.70, east: 136.46)]),
        Prefecture(code: 26, name: "京都府", areas: [.init(south: 34.70, west: 134.85, north: 35.78, east: 136.06)]),
        Prefecture(code: 27, name: "大阪府", areas: [.init(south: 34.26, west: 135.09, north: 35.05, east: 135.75)]),
        Prefecture(code: 28, name: "兵庫県", areas: [.init(south: 34.62, west: 134.25, north: 35.67, east: 135.48), .init(south: 34.15, west: 134.70, north: 34.62, east: 135.05)]),
        Prefecture(code: 29, name: "奈良県", areas: [.init(south: 33.85, west: 135.54, north: 34.78, east: 136.24)]),
        Prefecture(code: 30, name: "和歌山県", areas: [.init(south: 33.43, west: 135.05, north: 34.39, east: 136.01)]),
        Prefecture(code: 31, name: "鳥取県", areas: [.init(south: 35.05, west: 133.13, north: 35.61, east: 134.52)]),
        Prefecture(code: 32, name: "島根県", areas: [.init(south: 34.30, west: 131.67, north: 35.61, east: 133.32), .init(south: 35.95, west: 132.95, north: 36.35, east: 133.40)]),
        Prefecture(code: 33, name: "岡山県", areas: [.init(south: 34.42, west: 133.26, north: 35.35, east: 134.42)]),
        Prefecture(code: 34, name: "広島県", areas: [.init(south: 34.18, west: 132.04, north: 35.10, east: 133.46)]),
        Prefecture(code: 35, name: "山口県", areas: [.init(south: 33.82, west: 130.86, north: 34.68, east: 132.26)]),
        Prefecture(code: 36, name: "徳島県", areas: [.init(south: 33.54, west: 133.66, north: 34.24, east: 134.76)]),
        Prefecture(code: 37, name: "香川県", areas: [.init(south: 34.00, west: 133.56, north: 34.40, east: 134.45), .init(south: 34.40, west: 134.15, north: 34.60, east: 134.35)]),
        Prefecture(code: 38, name: "愛媛県", areas: [.init(south: 32.89, west: 132.01, north: 34.14, east: 133.70)]),
        Prefecture(code: 39, name: "高知県", areas: [.init(south: 32.71, west: 132.61, north: 33.88, east: 134.32)]),
        Prefecture(code: 40, name: "福岡県", areas: [.init(south: 32.99, west: 130.04, north: 33.97, east: 131.20)]),
        Prefecture(code: 41, name: "佐賀県", areas: [.init(south: 32.95, west: 129.76, north: 33.56, east: 130.55)]),
        Prefecture(code: 42, name: "長崎県", areas: [.init(south: 32.56, west: 129.55, north: 33.40, east: 130.39), .init(south: 34.05, west: 129.15, north: 34.72, east: 129.50), .init(south: 32.55, west: 128.55, north: 33.30, east: 129.20)]),
        Prefecture(code: 43, name: "熊本県", areas: [.init(south: 32.09, west: 130.36, north: 33.20, east: 131.34)]),
        Prefecture(code: 44, name: "大分県", areas: [.init(south: 32.73, west: 130.82, north: 33.69, east: 132.09)]),
        Prefecture(code: 45, name: "宮崎県", areas: [.init(south: 31.36, west: 130.70, north: 32.84, east: 131.89)]),
        Prefecture(code: 46, name: "鹿児島県", areas: [.init(south: 30.99, west: 130.10, north: 32.19, east: 131.21), .init(south: 30.20, west: 130.35, north: 30.50, east: 130.70), .init(south: 28.00, west: 129.10, north: 28.55, east: 129.75)]),
        Prefecture(code: 47, name: "沖縄県", areas: [.init(south: 26.06, west: 127.63, north: 26.88, east: 128.33), .init(south: 24.20, west: 123.60, north: 24.60, east: 124.35)]),
    ]
}
