import Foundation

/// 取り込み済みの山データの出典とライセンス。設定の「データの出典とライセンス」に出す。
/// 取り込んだときに端末へ残した manifest から読むので、オフラインでも読め、地点データと別の版の出典が混ざらない。
/// 値は manifest の文字列をそのまま持つ(説明文や複数の URL が入ることがある)。空の項目は nil。
public struct PeakDataCredits: Equatable, Sendable {
    /// データを作った日(UTC)。「2026/10/07」の形。
    public let releaseDate: String?
    /// manifest の license(例: ODbL-1.0)。版 6 は nil(出典ごとの license は `supplements` にある)。
    public let license: String?
    /// manifest の attribution(例: © OpenStreetMap contributors)。版 6 は nil。
    public let attribution: String?
    /// manifest の sourceUrl。元にした OSM データの URL。版 6 は nil。
    public let sourceUrl: String?
    /// タグ・よみがななどの補足に使った資料(manifest の supplementSources)。出典 ID の文字コード順。
    /// 版 6 は metadata.sources のすべての出典(元データも含む、`listsAllSources`)で、manifest の並び順のまま。
    public let supplements: [SupplementSource]
    /// `supplements` が補足の資料だけでなく、元データを含むすべての出典か(manifest 版 6)。
    /// 画面はこのとき見出しを「出典」にする。版 6 の出典の並びには元データと補足の区別が無いため。
    public let listsAllSources: Bool

    public init(releaseDate: String?, license: String?, attribution: String?, sourceUrl: String?, supplements: [SupplementSource],
                listsAllSources: Bool = false) {
        self.releaseDate = releaseDate
        self.license = license
        self.attribution = attribution
        self.sourceUrl = sourceUrl
        self.supplements = supplements
        self.listsAllSources = listsAllSources
    }
}

/// 補足に使った資料 1 件。出典 ID はタグ名とは関係なく、見出しとして出すだけ。
public struct SupplementSource: Equatable, Sendable {
    public let id: String
    /// 資料名・参照 URL。
    public let source: String?
    /// ライセンス名・版・説明ページの URL。
    public let license: String?
    /// 著作者名・必要なクレジット。
    public let attribution: String?
    /// 資料から行った加工・変更。
    public let changes: String?

    public init(id: String, source: String?, license: String?, attribution: String?, changes: String?) {
        self.id = id
        self.source = source
        self.license = license
        self.attribution = attribution
        self.changes = changes
    }
}

public enum PeakDataCreditsReader {
    /// 端末に残した manifest から、取り込み済みの山データの出典を読む。
    /// 取り込み済みの記録が無い、manifest が残っていない(出典を残す前の版のアプリで取り込んだ)、
    /// 記録の SHA-256 と合わない、読めないときは nil。
    public static func read(installed: InstalledPeakData?, manifest: Data?) -> PeakDataCredits? {
        guard let sha256 = installed?.manifestSha256, let manifest,
              PeakData.sha256Hex(manifest) == sha256 else { return nil }
        return parse(manifest)
    }

    /// manifest の本文から出典を読む。JSON のオブジェクトでなければ nil。
    /// supplementSources が無い・空・オブジェクトでない古いデータでは、補足は空になる。
    /// 出典の値がオブジェクトでないものは飛ばし、属性がどれも無い出典は ID だけを出す。
    /// 版 6 は metadata.sources を読む(`parseSources`)。名前のある出典が 1 つも無ければ nil。
    public static func parse(_ body: Data) -> PeakDataCredits? {
        guard let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else { return nil }
        if let metadata = root["metadata"] as? [String: Any] { return parseSources(root, metadata: metadata) }
        let sources = (root["supplementSources"] as? [String: Any]) ?? [:]
        let supplements = sources.compactMap { key, value -> SupplementSource? in
            guard let id = text(key), let item = value as? [String: Any] else { return nil }
            return SupplementSource(
                id: id,
                source: text(item["source"]),
                license: text(item["license"]),
                attribution: text(item["attribution"]),
                changes: text(item["changes"])
            )
        }.sorted { $0.id < $1.id }
        return PeakDataCredits(
            releaseDate: (root["version"] as? String).flatMap(PeakData.releaseDate),
            license: text(root["license"]),
            attribution: text(root["attribution"]),
            sourceUrl: text(root["sourceUrl"]),
            supplements: supplements
        )
    }

    /// manifest 版 6 の metadata.sources から出典を読む。元データと補足を分けず、すべての出典を manifest の並び順のまま出す
    /// (出典の並びに区別の項目が無く、名前も版ごとに変わるため。2026-10-20 の版から「OpenStreetMap（日本・Geofabrik 配布）」など)。
    /// 並び順は yamamuki-data が決めたものとして、並べ替えない。
    /// name の無い出典は飛ばす。名前のある出典が 1 つも無いときは nil を返し、
    /// 画面はアプリが持つ OpenStreetMap の表示に戻す(ライセンスの表示を消さないため)。
    private static func parseSources(_ root: [String: Any], metadata: [String: Any]) -> PeakDataCredits? {
        let sources = ((metadata["sources"] as? [Any]) ?? []).compactMap { value -> SupplementSource? in
            guard let item = value as? [String: Any], let name = text(item["name"]) else { return nil }
            return SupplementSource(
                id: name,
                source: text(item["source"]),
                license: text(item["license"]),
                attribution: text(item["attribution"]),
                changes: text(item["changes"])
            )
        }
        guard !sources.isEmpty else { return nil }
        return PeakDataCredits(
            releaseDate: (root["version"] as? String).flatMap(PeakData.releaseDate),
            license: nil,
            attribution: nil,
            sourceUrl: nil,
            supplements: sources,
            listsAllSources: true
        )
    }

    private static func text(_ value: Any?) -> String? {
        guard let text = (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        return text
    }
}

/// 文章の中の URL を、リンクにできるよう切り分ける。
public enum LinkText {
    /// 文章の一部。`isUrl` が true なら、`text` は開ける URL。
    public struct Part: Equatable, Sendable {
        public let text: String
        public let isUrl: Bool

        public init(_ text: String, isUrl: Bool) {
            self.text = text
            self.isUrl = isUrl
        }
    }

    /// `text` を、URL とそれ以外に分ける。URL は http:// か https:// で始まり、空白か、
    /// 日本語の括弧・句読点の手前までとする(「title=日本百名山」のように URL の中の日本語は含める)。
    /// 末尾の「.」「,」「;」などと、対応する「(」の無い「)」は URL に含めない。
    public static func split(_ text: String) -> [Part] {
        let ns = text as NSString
        var parts: [Part] = []
        var last = 0
        for match in urlPattern.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            let url = trimEnd(ns.substring(with: match.range))
            // 「https://」だけのものはリンクにしない。
            if url.hasSuffix("://") { continue }
            let start = match.range.location
            if start > last { parts.append(Part(ns.substring(with: NSRange(location: last, length: start - last)), isUrl: false)) }
            parts.append(Part(url, isUrl: true))
            last = start + (url as NSString).length
        }
        if last < ns.length { parts.append(Part(ns.substring(from: last), isUrl: false)) }
        return parts
    }

    private static func trimEnd(_ url: String) -> String {
        var result = url
        while let c = result.last {
            let unbalanced = c == ")" && result.filter { $0 == "(" }.count < result.filter { $0 == ")" }.count
            if trailing.contains(c) || unbalanced { result.removeLast() } else { break }
        }
        return result
    }

    private static let urlPattern = try! NSRegularExpression(pattern: "https?://[^\\s<>\"（）「」『』【】、。，；]+")
    private static let trailing: Set<Character> = [".", ",", ";", ":", "!", "?", "'"]
}
