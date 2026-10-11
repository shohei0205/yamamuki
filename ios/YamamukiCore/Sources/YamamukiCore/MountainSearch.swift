import Foundation

/// 山の検索。山名・よみがな・別名のどれかに、入力した文字を含む山を探す。
/// 作るときに全件の文字をそろえておき、入力が変わるたびに search で絞り込む。
public struct MountainSearch: Sendable {
    /// 一度に返す件数の上限。
    public static let maxResults = 50

    private struct Entry: Sendable {
        let mountain: Mountain
        let keys: [String]
    }

    private let entries: [Entry]

    public init(_ mountains: [Mountain]) {
        entries = mountains.map { m in
            Entry(mountain: m, keys: ([m.name] + [m.nameReading].compactMap { $0 } + m.aliases)
                .map(MountainSearch.normalize).filter { !$0.isEmpty })
        }
    }

    /// query を含む山を、(latitude, longitude) から近い順に最大 limit 件返す。
    /// 地点が無ければ標高の高い順(標高不明は最後)。並びが同じなら osmId の順。入力が空白だけなら空。
    public func search(_ query: String, latitude: Double?, longitude: Double?, limit: Int = MountainSearch.maxResults) -> [Mountain] {
        let key = MountainSearch.normalize(query)
        if key.isEmpty { return [] }
        let hits = entries.filter { e in e.keys.contains { $0.contains(key) } }.map(\.mountain)
        let sorted: [Mountain]
        if let latitude, let longitude {
            sorted = hits.map { ($0, GeoMath.distanceKm(latitude, longitude, $0.latitude, $0.longitude)) }
                .sorted { $0.1 != $1.1 ? $0.1 < $1.1 : $0.0.osmId < $1.0.osmId }
                .map(\.0)
        } else {
            sorted = hits.sorted {
                let a = $0.elevationM ?? -.infinity
                let b = $1.elevationM ?? -.infinity
                return a != b ? a > b : $0.osmId < $1.osmId
            }
        }
        return Array(sorted.prefix(limit))
    }

    /// 比べるための文字の形。全角と半角(NFKC)、大文字と小文字、カタカナとひらがなの違いをなくし、空白を除く。
    /// 小さい仮名は大きい仮名にそろえる(「三ッ峠」と「三ツ峠」、「槍ヶ岳」と「槍ケ岳」を同じに扱う)。
    public static func normalize(_ text: String) -> String {
        let nfkc = text.precomposedStringWithCompatibilityMapping.lowercased()
        var out = String.UnicodeScalarView()
        for c in nfkc.unicodeScalars {
            switch c.value {
            case _ where c.properties.isWhitespace: continue
            case 0x30F5, 0x3095: out.append("か")
            case 0x30F6, 0x3096: out.append("け")
            case 0x30A1...0x30F4: out.append(large(Unicode.Scalar(c.value - 0x60)!))
            default: out.append(large(c))
            }
        }
        return String(out)
    }

    /// 小さいひらがな(ぁぃぅぇぉっゃゅょゎ)は大きいひらがなにする。
    private static func large(_ c: Unicode.Scalar) -> Unicode.Scalar {
        smallKana.contains(c) ? Unicode.Scalar(c.value + 1)! : c
    }

    private static let smallKana = Set("ぁぃぅぇぉっゃゅょゎ".unicodeScalars)
}
