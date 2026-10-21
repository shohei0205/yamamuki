import Foundation

/// 山の検索。山名・よみがな・別名のどれかに、入力した文字を含む山を探す。先頭が一致した山を先に並べる。
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

    /// 検索の結果。山名・よみがな・別名のどれかの先頭が入力と一致した山(prefix)と、途中に含む山(partial)に分ける。
    /// 「や」で「槍ヶ岳(やりがたけ)」を探すときに、「〜山(やま)」の山が先に並ばないようにするため。
    public struct Results: Equatable, Sendable {
        public var prefix: [Mountain]
        public var partial: [Mountain]

        public init(prefix: [Mountain], partial: [Mountain]) {
            self.prefix = prefix
            self.partial = partial
        }

        public static let empty = Results(prefix: [], partial: [])

        /// 先頭が一致した山、途中に含む山の順に並べたもの。
        public var all: [Mountain] { prefix + partial }

        public var isEmpty: Bool { prefix.isEmpty && partial.isEmpty }
    }

    /// query を含む山を、先頭が一致した山、途中に含む山の順に、合わせて最大 limit 件返す。
    /// それぞれの中は (latitude, longitude) から近い順。地点が無ければ標高の高い順(標高不明は最後)。
    /// 並びが同じなら地点の ID の順。入力が空白だけなら空。
    /// 入力が仮名か英数字 1 文字だけのときは、途中に含む山が多すぎて探しにくいので返さない(漢字 1 文字なら返す)。
    /// ただし先頭が一致した山が 1 つも無いときは、何も出ないよりよいので返す。
    public func search(_ query: String, latitude: Double?, longitude: Double?, limit: Int = MountainSearch.maxResults) -> Results {
        let key = MountainSearch.normalize(query)
        if key.isEmpty { return .empty }
        let hits = entries.filter { e in e.keys.contains { $0.contains(key) } }
        let isPrefix = { (e: Entry) in e.keys.contains { $0.hasPrefix(key) } }
        let prefix = Array(sort(hits.filter(isPrefix).map(\.mountain), latitude, longitude).prefix(limit))
        if !prefix.isEmpty, key.unicodeScalars.count == 1, let c = key.unicodeScalars.first, MountainSearch.isKanaOrAlphanumeric(c) {
            return Results(prefix: prefix, partial: [])
        }
        let partial = Array(sort(hits.filter { !isPrefix($0) }.map(\.mountain), latitude, longitude).prefix(limit - prefix.count))
        return Results(prefix: prefix, partial: partial)
    }

    private func sort(_ hits: [Mountain], _ latitude: Double?, _ longitude: Double?) -> [Mountain] {
        if let latitude, let longitude {
            return hits.map { ($0, GeoMath.distanceKm(latitude, longitude, $0.latitude, $0.longitude)) }
                .sorted { $0.1 != $1.1 ? $0.1 < $1.1 : $0.0.id < $1.0.id }
                .map(\.0)
        }
        return hits.sorted {
            let a = $0.elevationM ?? -.infinity
            let b = $1.elevationM ?? -.infinity
            return a != b ? a > b : $0.id < $1.id
        }
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

    /// そろえたあとの文字が、ひらがな(カタカナもひらがなにそろえてある)、長音記号、英数字(全角も半角にそろえてある)のどれか。
    private static func isKanaOrAlphanumeric(_ c: Unicode.Scalar) -> Bool {
        (0x3041...0x309F).contains(c.value) || c.value == 0x30FC
            || ("a"..."z").contains(c) || ("0"..."9").contains(c)
    }

    /// 小さいひらがな(ぁぃぅぇぉっゃゅょゎ)は大きいひらがなにする。
    private static func large(_ c: Unicode.Scalar) -> Unicode.Scalar {
        smallKana.contains(c) ? Unicode.Scalar(c.value + 1)! : c
    }

    private static let smallKana = Set("ぁぃぅぇぉっゃゅょゎ".unicodeScalars)
}
