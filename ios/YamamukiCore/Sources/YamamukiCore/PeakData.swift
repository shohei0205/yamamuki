import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(CryptoKit)
import CryptoKit
#endif
#if canImport(Compression)
import Compression
#endif

/// このアプリが読める山データの種類(カタログの datasets のキー)。地点の ID の形や出典が種類ごとに違うので、
/// カタログに知らない種類があっても読まない。並びは山データの選択画面の並び。
public enum PeakDataset: String, CaseIterable, Codable, Sendable {
    /// OpenStreetMap の山頂(ODbL)。0.7.0 まではこれだけを読んでいた。
    case osmPeaks = "osm-peaks"
    /// 国土地理院の山頂(PDL 1.0)。
    case gsiPeaks = "gsi-peaks"

    /// 設定に何も無いときに使う種類。0.7.0 から上げた利用者の見え方を変えない。
    public static let `default` = PeakDataset.osmPeaks

    /// カタログでの名前(datasets のキー)。
    public var id: String { rawValue }

    /// データ本体のファイル名(manifest の fileName)。
    public var fileName: String { "\(id).json.gz" }

    /// カタログが指してよい manifest の置き場所。Release の manifest を書き換えずにコピーしたもの。
    public var manifestUrlPrefix: String { "\(PeakData.siteDataUrl)\(id)/manifests/" }
}

/// yamamuki-data が配る全国の山頂データの、最新版の目印(manifest.json)。
/// 形式は yamamuki-data の README.md の「manifest.json」と、points/README.md にある。
public struct PeakManifest: Equatable, Sendable {
    public let schemaVersion: Int
    /// データの版。生成時の UTC 日時と Actions の実行 ID をつないだ文字列。
    public let version: String
    /// データ本体(gzip で圧縮した JSON 配列)の URL。
    public let downloadUrl: URL
    /// データ本体の SHA-256(小文字の16進数)。
    public let sha256: String
    /// データ本体のバイト数。
    public let sizeBytes: Int
    /// 収録した山の数。版 4 は mountainCount、版 5 は pointCount、版 6 は metadata.pointCount。
    public let mountainCount: Int
    /// 元にした OSM データの基準日時(UTC、例: 2026-09-30T20:21:22Z)。版 6 は metadata.sources の latestTimestamp のうち
    /// 最も新しいもの。版 5 で省略されたときと、版 6 でどの出典にも latestTimestamp が無いときは空文字。
    public let sourceTimestamp: String
    /// 試験版のデータ(版 6 の experimental が true)。選択画面で「試験版」と出す。
    public var experimental: Bool = false

    /// データを作った日(UTC)。「2026/10/07」の形。`version` から読めなければ nil。
    public var releaseDate: String? { PeakData.releaseDate(version) }
}

/// 取り込み済みの配信データ。設定画面に出し、次の確認で同じ版なら取り直さない。
public struct InstalledPeakData: Codable, Equatable, Sendable {
    public var version: String
    public var sourceTimestamp: String
    public var mountainCount: Int
    /// manifest を取ったときの目印。次の確認で送り、変わっていなければ manifest も取り直さない。
    /// カタログ(0.7.0 までは参照先ファイル)から読んだときは manifest の SHA-256、manifest を直接読んだときは HTTP の ETag。
    public var manifestEtag: String?
    public var installedAt: Date
    /// 保存データを作った読み込み処理の版(`PeakData.readerVersion`)。記録が無い古い版のアプリで取り込んだときは 0。
    public var readerVersion: Int
    /// 取り込んだデータ本体(gz)の SHA-256。端末に残した gz がこの版のものかを確かめる。
    public var dataSha256: String?
    /// 取り込んだときの manifest の SHA-256。端末に残した manifest がこの版のものかを確かめる。
    public var manifestSha256: String?
    /// 試験版のデータ(`PeakManifest.experimental`)。記録が無い古い版のアプリで取り込んだときは false。
    public var experimental: Bool

    /// データを作った日(UTC)。「2026/10/07」の形。`version` から読めなければ nil。
    public var releaseDate: String? { PeakData.releaseDate(version) }

    public init(version: String, sourceTimestamp: String, mountainCount: Int, manifestEtag: String?, installedAt: Date,
                readerVersion: Int = 0, dataSha256: String? = nil, manifestSha256: String? = nil, experimental: Bool = false) {
        self.version = version
        self.sourceTimestamp = sourceTimestamp
        self.mountainCount = mountainCount
        self.manifestEtag = manifestEtag
        self.installedAt = installedAt
        self.readerVersion = readerVersion
        self.dataSha256 = dataSha256
        self.manifestSha256 = manifestSha256
        self.experimental = experimental
    }

    private enum CodingKeys: String, CodingKey {
        case version, sourceTimestamp, mountainCount, manifestEtag, installedAt, readerVersion, dataSha256, manifestSha256
        case experimental
    }

    /// 読み込み処理の版と SHA-256 を記録していない、古い版のアプリが保存した記録も読む。
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decode(String.self, forKey: .version)
        sourceTimestamp = try c.decode(String.self, forKey: .sourceTimestamp)
        mountainCount = try c.decode(Int.self, forKey: .mountainCount)
        manifestEtag = try c.decodeIfPresent(String.self, forKey: .manifestEtag)
        installedAt = try c.decode(Date.self, forKey: .installedAt)
        readerVersion = try c.decodeIfPresent(Int.self, forKey: .readerVersion) ?? 0
        dataSha256 = try c.decodeIfPresent(String.self, forKey: .dataSha256)
        manifestSha256 = try c.decodeIfPresent(String.self, forKey: .manifestSha256)
        experimental = try c.decodeIfPresent(Bool.self, forKey: .experimental) ?? false
    }
}

/// 確認した manifest のうち、まだ取り込んでいない版。データ本体を取る前に、大きさを利用者に見せるのに使う。
/// `PeakDataUpdater.install` に渡すと、この manifest のとおりに取り込む。
public struct PeakDataOffer: Equatable, Sendable {
    public let manifest: PeakManifest
    /// 取ったときの manifest の中身。取り込んだら端末に残す。
    let manifestBody: Data
    let etag: String?

    /// ダウンロードするデータ本体のバイト数。
    public var sizeBytes: Int { manifest.sizeBytes }
}

/// 配信データが壊れている、または読めない形式だった。
public struct PeakDataError: Error, CustomStringConvertible, Equatable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

/// manifest の取得結果。
public enum ManifestResponse: Sendable {
    /// 送った ETag と同じで、前回から変わっていない(HTTP 304)。
    case notModified
    case fetched(body: Data, etag: String?)
}

/// 山データをどこから読むか。ビルドの種類と、開発用のビルドの取得先の設定で決まる。
public enum PeakDataChannel: Sendable {
    /// 配布用のビルド: yamamuki のカタログ(catalog.json)の、その種類の stable が指す manifest。
    case stable
    /// 開発用のビルドの既定: カタログの、その種類の dev が指す manifest。dev が無ければ stable。
    case dev
    /// 開発用のビルドで取得先を dev にしたとき: osm-peaks は yamamuki-data の dev が公開する最新の manifest を直接。
    /// ほかの種類は直接読む置き場所が無いので、`dev` と同じくカタログの dev を読む。
    case dataDev
}

/// カタログ(catalog.json)に書いた、読む manifest の場所と SHA-256。
public struct PeakDataPointer: Equatable, Sendable {
    public let manifestUrl: URL
    public let manifestSha256: String
}

/// 配信データの取得元。テストではフェイクに差し替える。
public protocol PeakDataSource: Sendable {
    /// - Parameter etag: 前回の ETag。nil なら必ず取得する。
    func fetchManifest(etag: String?) async throws -> ManifestResponse
    func fetchData(_ url: URL) async throws -> Data
}

public enum PeakData {
    /// 山データのカタログ。読ませる manifest を yamamuki 側で決めるため、yamamuki の Pages に置いている(0.8.0 から)。
    /// データの種類(datasets の osm-peaks・gsi-peaks)ごとに、stable(配布用)と dev(開発用、省略可能)の
    /// manifest の URL と SHA-256 が書いてある。このアプリが読むのは `PeakDataset` の種類だけ。
    /// 書き換えは「山データの差し替え PR を作る」ワークフローの PR で行う。
    /// 0.7.0 までは site/data/osm-peaks/current.json を読んでいた(0.9.0 で廃止する予定)。
    public static let catalogUrl = URL(string: "https://shohei0205.github.io/yamamuki/data/catalog.json")!

    /// カタログと manifest のコピーを置く場所(yamamuki の Pages の data/)。
    public static let siteDataUrl = "https://shohei0205.github.io/yamamuki/data/"

    /// 読めるカタログの形式の版。
    public static let catalogSchemaVersion = 1

    /// osm-peaks の開発版の最新版の manifest の URL。開発用のビルド(Android の debug、iOS の Debug)で、取得先を dev にしたときに直接読む
    /// (`PeakDataChannel.dataDev`)。開発版が取れないときに正式版へ自動で切り替えることはしない(yamamuki-data の方針)。
    /// yamamuki-data の dev が公開する置き場所(points/osm-peaks-dev/)。manifest の版は、yamamuki-data の dev に合わせて変わる
    /// (2026-10-18 から版 6)。
    public static let devManifestUrl = URL(string: "https://shohei0205.github.io/yamamuki-data/points/osm-peaks-dev/manifest.json")!

    /// `channel` から `dataset` の山データを読む取得元。
    public static func source(_ channel: PeakDataChannel, dataset: PeakDataset, session: URLSession = .shared,
                              userAgent: String) -> PeakDataSource {
        switch channel {
        case .stable: return CatalogPeakDataSource(session: session, dataset: dataset, useDev: false, userAgent: userAgent)
        case .dataDev where dataset == .osmPeaks:
            return HTTPPeakDataSource(session: session, manifestUrl: devManifestUrl, userAgent: userAgent)
        case .dev, .dataDev: return CatalogPeakDataSource(session: session, dataset: dataset, useDev: true, userAgent: userAgent)
        }
    }

    /// カタログを読み、`dataset` の読む manifest の場所を返す。`useDev` なら dev を、dev が無ければ stable を読む。
    /// ほかの種類のデータは見ない。よその URL を指していたり、SHA-256 の形がおかしかったりしたら読まない。
    public static func parseCatalog(_ body: Data, useDev: Bool, dataset: PeakDataset = .osmPeaks) throws -> PeakDataPointer {
        guard let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
            throw PeakDataError("山データのカタログを読めません")
        }
        // JSONSerialization は true / false も NSNumber で返すので、整数だけを通す。
        let number = root["schemaVersion"] as? NSNumber
        let schemaVersion = number.flatMap { CFGetTypeID($0) == CFBooleanGetTypeID() ? nil : $0 }
            .flatMap { $0.doubleValue == $0.doubleValue.rounded() ? $0.intValue : nil }
        guard schemaVersion == catalogSchemaVersion else {
            throw PeakDataError("このアプリが読めない形式の山データのカタログです(形式の版 \(schemaVersion.map(String.init) ?? "不明"))。アプリを更新してください。")
        }
        guard let datasets = root["datasets"] as? [String: Any] else {
            throw PeakDataError("山データのカタログに datasets がありません")
        }
        let id = dataset.id
        guard let channels = datasets[id] as? [String: Any] else {
            throw PeakDataError("山データのカタログに \(id) がありません")
        }
        let channel = useDev && channels["dev"] != nil && !(channels["dev"] is NSNull) ? "dev" : "stable"
        guard let entry = channels[channel] as? [String: Any] else {
            throw PeakDataError("山データのカタログの \(id) に \(channel) がありません")
        }
        let prefix = dataset.manifestUrlPrefix
        guard let url = entry["manifestUrl"] as? String, url.hasPrefix(prefix),
              isManifestName(String(url.dropFirst(prefix.count))), let manifestUrl = URL(string: url) else {
            throw PeakDataError("山データのカタログの \(id) の \(channel) が、決まった置き場所の manifest を指していません")
        }
        guard let sha256 = entry["manifestSha256"] as? String, sha256.count == 64,
              sha256.allSatisfy({ "0123456789abcdef".contains($0) }) else {
            throw PeakDataError("山データのカタログの \(id) の \(channel) の manifestSha256 が SHA-256 ではありません")
        }
        return PeakDataPointer(manifestUrl: manifestUrl, manifestSha256: sha256)
    }

    /// カタログが指してよい manifest のファイル名(Release のタグ + .json)か。
    static func isManifestName(_ name: String) -> Bool {
        guard name.hasSuffix(".json"), let first = name.first, first.isASCII, first.isLetter || first.isNumber else { return false }
        return name.dropLast(".json".count).allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || "._-".contains($0)) }
    }

    /// 読める manifest の形式の版。版 4 で downloadUrl が入った。版 5 で件数が pointCount になり、
    /// データ本体の版(dataSchemaVersion)が別に書かれるようになった。版 6 で件数と出典が metadata にまとまり、
    /// 形式の名前(schemaFormat・dataSchemaFormat)が入った。
    public static let supportedSchemaVersions: Set<Int> = [4, 5, 6]

    /// 読めるデータ本体の版(manifest 版 5 以降の dataSchemaVersion)。地点データ版 5 は、山ごとの項目(osmId・name・latitude・
    /// longitude・elevationM)が版 4 までと同じで、ID・種別・タグ・画像などの項目が増えただけなので、そのまま読める。
    /// 版 6 は地点の配列が文書の points に入り、osmId の代わりに id(osm:<ノード番号>)で OSM の地点を表す。
    /// gsi-peaks は版 6 からで、id は gsi-vector:… などの文字列。
    public static let supportedDataSchemaVersions: Set<Int> = [5, 6]

    /// manifest 版 6 の形式の名前(schemaFormat)。
    public static let manifestSchemaFormat = "yamamuki-manifest"

    /// 読める地点データの形式の名前(manifest 版 6 の dataSchemaFormat)。
    public static let dataSchemaFormat = "yamamuki-points"

    /// 地点データ版 6 の、OSM のノードを表す ID の頭。後ろはノード番号。
    static let osmIdPrefix = "osm:"

    /// 地点データ版 5 の、山頂の種別。
    static let peakType = "peak"

    /// 保存データを作る読み込み処理の版。`parseMountains` で読む項目を増やしたり、保存の形を変えたりしたら上げる。
    /// 上がると、端末に残した gz から保存データを作り直す(`PeakDataUpdater.rebuild`)。gz が無ければ、次の確認で取り直す。
    public static let readerVersion = 2

    /// これより大きいデータは受け取らない。今は約 0.45 MB で、yamamuki-data も 5 MB を超えたら公開を止める。
    public static let maxSizeBytes = 20_000_000

    /// 配信データが対象にする範囲。日本の端の島(沖ノ鳥島・南鳥島・与那国島・択捉島)まで入る矩形。
    /// この範囲のタイル(`foreignAreas` を除く)はすべて取り込んだデータで置き換えるので、新しい版で消えた山や、以前 Overpass で取った山は残らない。
    public static let coverage = BoundingBox(south: 20.0, west: 122.0, north: 46.0, east: 154.0)

    /// `coverage` のうち日本の外の陸地(配信データに山が無い所)。ここのタイルは取得済みにしないので、
    /// 「データがありません」と知らせる。0.5° のタイルの境目にそろえ、日本の島(対馬・宗谷岬・択捉島など)のタイルは含めない。
    public static let foreignAreas = [
        BoundingBox(south: 34.0, west: 122.0, north: 43.0, east: 129.0), // 朝鮮半島・中国の遼東半島と山東半島
        BoundingBox(south: 35.0, west: 129.0, north: 43.0, east: 130.0), // 朝鮮半島の東岸(釜山から北)
        BoundingBox(south: 33.0, west: 125.0, north: 34.0, east: 127.0), // 済州島と朝鮮半島の南西の島
        BoundingBox(south: 37.0, west: 130.5, north: 38.0, east: 131.0), // 鬱陵島
        BoundingBox(south: 29.0, west: 122.0, north: 31.5, east: 123.0), // 中国の舟山群島
        BoundingBox(south: 42.0, west: 129.0, north: 46.0, east: 139.0), // ロシアの沿海地方
        BoundingBox(south: 45.5, west: 142.0, north: 46.0, east: 144.0), // サハリンの南端
        BoundingBox(south: 45.5, west: 149.0, north: 46.0, east: 154.0), // 得撫島から北の千島列島
        BoundingBox(south: 20.0, west: 144.5, north: 21.0, east: 146.0), // 北マリアナ諸島の北端
    ]

    /// `dataset` の manifest を読む。
    public static func parseManifest(_ body: Data, dataset: PeakDataset = .osmPeaks) throws -> PeakManifest {
        guard let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
            throw PeakDataError("manifest を読めません")
        }
        func string(_ key: String) throws -> String {
            guard let value = root[key] as? String else { throw PeakDataError("manifest に \(key) がありません") }
            return value
        }
        /// `object`(省略時は manifest 全体)の `key` の整数。エラーの文には `label`(省略時は `key`)を出す。
        func int(_ key: String, in object: [String: Any]? = nil, label: String? = nil) throws -> Int {
            // JSONSerialization は true / false も NSNumber で返すので、整数だけを通す。
            guard let number = (object ?? root)[key] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                  number.doubleValue == number.doubleValue.rounded() else {
                throw PeakDataError("manifest の \(label ?? key) が整数ではありません")
            }
            return number.intValue
        }

        let schemaVersion = try int("schemaVersion")
        guard supportedSchemaVersions.contains(schemaVersion) else {
            throw PeakDataError("このアプリが読めない形式の山データです(形式の版 \(schemaVersion))。アプリを更新してください。")
        }
        // 版 5 は、データ本体の版が分からなければ読めないものとして扱う(manifest の版から推測しない)。
        if schemaVersion >= 5 {
            let dataSchemaVersion = root["dataSchemaVersion"] == nil ? nil : try? int("dataSchemaVersion")
            guard let dataSchemaVersion, supportedDataSchemaVersions.contains(dataSchemaVersion) else {
                throw PeakDataError("このアプリが読めない形式の山データです(データの版 \(dataSchemaVersion.map(String.init) ?? "不明"))。アプリを更新してください。")
            }
        }
        // 版 6 は、形式の名前が違う(別の種類のデータの)manifest を読まない。
        if schemaVersion >= 6 {
            guard root["schemaFormat"] as? String == manifestSchemaFormat else {
                throw PeakDataError("manifest の schemaFormat が \(manifestSchemaFormat) ではありません")
            }
            guard root["dataSchemaFormat"] as? String == dataSchemaFormat else {
                throw PeakDataError("manifest の dataSchemaFormat が \(dataSchemaFormat) ではありません")
            }
        }
        // 版 5 以降は、データの種類をデータ本体のファイル名で確かめる。ほかの種類の manifest を取り違えて読むと、
        // 地点の ID の形が違うので 0 件になったり、ほかの種類の山が混ざったりするため。
        // 版 5 までは osm-peaks しか無かったので、ほかの種類では読まない。
        if schemaVersion < 6, dataset != .osmPeaks {
            throw PeakDataError("\(dataset.id) の manifest の形式の版 \(schemaVersion) は読めません")
        }
        if schemaVersion >= 5, root["fileName"] as? String != dataset.fileName {
            throw PeakDataError("manifest の fileName が \(dataset.fileName) ではありません(\(root["fileName"].map { "\($0)" } ?? "なし"))")
        }
        let mountainCount: Int
        if schemaVersion >= 6 {
            mountainCount = try int("pointCount", in: root["metadata"] as? [String: Any] ?? [:], label: "metadata.pointCount")
        } else {
            mountainCount = try int(schemaVersion == 5 ? "pointCount" : "mountainCount")
        }
        let downloadText = try string("downloadUrl")
        guard downloadText.hasPrefix("https://"), let downloadUrl = URL(string: downloadText) else {
            throw PeakDataError("manifest の downloadUrl が https ではありません")
        }
        let manifest = PeakManifest(
            schemaVersion: schemaVersion,
            version: try string("version"),
            downloadUrl: downloadUrl,
            sha256: try string("sha256").lowercased(),
            sizeBytes: try int("sizeBytes"),
            mountainCount: mountainCount,
            sourceTimestamp: schemaVersion >= 6 ? latestTimestamp(root)
                : schemaVersion == 5 ? root["sourceTimestamp"] as? String ?? "" : try string("sourceTimestamp"),
            experimental: root["experimental"] as? Bool ?? false
        )
        guard (1...maxSizeBytes).contains(manifest.sizeBytes) else {
            throw PeakDataError("manifest のサイズ \(manifest.sizeBytes) バイトは受け取れません")
        }
        return manifest
    }

    /// manifest 版 6 の、元データの基準日時。metadata.sources の latestTimestamp のうち最も新しいもの(UTC の決まった書式なので、文字列で比べる)。
    /// 出典の並びに元データと補足の区別は無く、出典の名前も版ごとに変わる(2026-10-20 の版から「OpenStreetMap（日本・Geofabrik 配布）」など)ので、
    /// 名前では選ばない。どの出典にも無ければ空文字。
    private static func latestTimestamp(_ root: [String: Any]) -> String {
        ((root["metadata"] as? [String: Any])?["sources"] as? [Any] ?? [])
            .compactMap { (($0 as? [String: Any])?["latestTimestamp"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .max() ?? ""
    }

    /// 受け取ったデータ本体が、manifest のサイズと SHA-256 に合うか確かめる。
    public static func verify(_ data: Data, against manifest: PeakManifest) throws {
        guard data.count == manifest.sizeBytes else {
            throw PeakDataError("山データのサイズが合いません(\(data.count) バイト、manifest では \(manifest.sizeBytes) バイト)")
        }
        guard sha256Hex(data) == manifest.sha256 else { throw PeakDataError("山データの SHA-256 が合いません") }
    }

    /// データを作った日(UTC)を「2026/10/07」の形で返す。データの版の先頭の日時(例: 20261007T071401Z-…)から読む。
    /// 版がこの形でなければ nil。
    public static func releaseDate(_ version: String) -> String? {
        let chars = Array(version)
        guard chars.count >= 16, chars[8] == "T", chars[15] == "Z",
              (chars[0..<8] + chars[9..<15]).allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return "\(String(chars[0..<4]))/\(String(chars[4..<6]))/\(String(chars[6..<8]))"
    }

    /// 設定で自動の確認をオンにしたとき、新しい山データがあるかを確かめる間隔(30 日)。
    /// 読むのはカタログ(1 KB ほど)で、山データはそれほど頻繁に更新しないので、月に 1 回ほどで足りる。
    public static let autoCheckInterval: TimeInterval = 30 * 24 * 60 * 60

    /// 自動の確認をする時期か。まだ確かめたことが無い(`lastCheckedAt` が nil)か、前回から
    /// `autoCheckInterval` 以上たったら確かめる。端末の時計が戻って前回が未来になったときも確かめる
    /// (そのままだと、時計が追いつくまで確かめなくなるため)。
    public static func autoCheckDue(lastCheckedAt: Date?, now: Date) -> Bool {
        guard let lastCheckedAt, lastCheckedAt <= now else { return true }
        return now.timeIntervalSince(lastCheckedAt) >= autoCheckInterval
    }

    static func sha256Hex(_ data: Data) -> String {
        #if canImport(CryptoKit)
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        #else
        fatalError("SHA-256 の計算には CryptoKit が必要です")
        #endif
    }

    /// gzip を展開し、`dataset` の山を読む。版 5 までは JSON 配列、版 6 は文書の points の配列を読む。
    /// 名前・座標・地点の ID のない項目と、山頂以外の種別(type)の項目は飛ばす。地点の ID は、osm-peaks では
    /// OSM のノード番号(版 5 までは osmId、版 6 は id の osm:<番号>)から「osm:<番号>」を作り、ほかの種類では id をそのまま使う。
    /// 種別の無い項目(版 4 までと、版 5 で種別不明のもの)は山頂として読む。
    /// 読み仮名・別名・Wikipedia と Wikidata の URL・タグ・画像も読む。形の合わない値は、無いものとして扱う。
    /// 読み仮名が配列(版 6)なら先頭を、別名が {name, nameReading} の配列(版 6)なら name を読む。
    /// それ以外の知らない項目は読まない(端末に残した gz には残る)。
    public static func parseMountains(_ gzip: Data, dataset: PeakDataset = .osmPeaks) throws -> [Mountain] {
        let json = try gunzip(gzip)
        let root = try? JSONSerialization.jsonObject(with: json)
        guard let items = root as? [Any] ?? (root as? [String: Any])?["points"] as? [Any] else {
            throw PeakDataError("山データを読めません")
        }
        var seen = Set<String>()
        return items.compactMap { item -> Mountain? in
            guard let m = item as? [String: Any],
                  (m["type"] as? String ?? peakType) == peakType,
                  let id = pointId(of: m, dataset: dataset),
                  let name = (m["name"] as? String)?.trimmingCharacters(in: .whitespaces), !name.isEmpty,
                  let latitude = (m["latitude"] as? NSNumber)?.doubleValue,
                  let longitude = (m["longitude"] as? NSNumber)?.doubleValue,
                  seen.insert(id).inserted
            else { return nil }
            return Mountain(id: id, name: name, latitude: latitude, longitude: longitude,
                            elevationM: (m["elevationM"] as? NSNumber)?.doubleValue,
                            nameReading: trimmed(m["nameReading"]) ?? (m["nameReading"] as? [Any])?.lazy.compactMap(trimmed).first,
                            aliases: names(m["aliases"]).filter { $0 != name },
                            wikipediaUrl: httpsUrl(m["wikipediaUrl"]),
                            wikidataUrl: httpsUrl(m["wikidataUrl"]),
                            tags: names(m["tags"]),
                            graphic: graphic(m["graphic"]))
        }
    }

    /// 前後の空白を除いた文字列。文字列でないか空なら nil。
    private static func trimmed(_ value: Any?) -> String? {
        guard let text = (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        return text
    }

    /// 地点の ID。osm-peaks では OSM のノード番号から「osm:<番号>」を作る(版 5 までの id は形が決まっていないので使わない)。
    /// ほかの種類では id をそのまま使う。読めなければ nil。
    private static func pointId(of m: [String: Any], dataset: PeakDataset) -> String? {
        guard dataset == .osmPeaks else { return trimmed(m["id"]) }
        return ((m["osmId"] as? NSNumber)?.int64Value ?? osmIdOf(m["id"])).map { "\(osmIdPrefix)\($0)" }
    }

    /// 地点データ版 6 の id(osm:<ノード番号>)から、OSM のノード番号を読む。OSM 以外の地点や形の違う ID は nil。
    private static func osmIdOf(_ value: Any?) -> Int64? {
        guard let id = value as? String, id.hasPrefix(osmIdPrefix) else { return nil }
        let number = id.dropFirst(osmIdPrefix.count)
        guard !number.isEmpty, number.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return Int64(number)
    }

    /// 別名やタグの配列。文字列でない・空の要素と重複を除く。配列でなければ空。
    /// 要素が {name, ...} のオブジェクト(版 6 の別名)なら name を読む。
    private static func names(_ value: Any?) -> [String] {
        var seen = Set<String>()
        return ((value as? [Any]) ?? []).compactMap { trimmed($0) ?? trimmed(($0 as? [String: Any])?["name"]) }
            .filter { seen.insert($0).inserted }
    }

    /// https の URL だけを受け取る。
    private static func httpsUrl(_ value: Any?) -> String? {
        trimmed(value).flatMap { $0.hasPrefix("https://") ? $0 : nil }
    }

    /// 地点の画像。SVG が無いか大きすぎるときは画像なし、倍率が正の有限の数でないときは 1 倍とする。
    private static func graphic(_ value: Any?) -> PointGraphic? {
        guard let g = value as? [String: Any], let svg = g["svg"] as? String,
              !svg.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, svg.utf8.count <= maxSvgBytes else { return nil }
        var scale = 1.0
        // JSONSerialization は true / false も NSNumber で返すので、数だけを通す。
        if let number = g["scale"] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
           number.doubleValue.isFinite, number.doubleValue > 0 {
            scale = number.doubleValue
        }
        return PointGraphic(svg: svg, scale: scale)
    }

    /// 地点の画像の SVG の大きさの上限。yamamuki-data の決まり(100,000 バイト以内)と同じ。
    private static let maxSvgBytes = 100_000

    /// gzip(RFC 1952)を展開する。Foundation には gzip の展開が無いので、ヘッダーを読み飛ばして中身の deflate を
    /// Compression で展開する(COMPRESSION_ZLIB は zlib ヘッダーの無い deflate を読む)。
    static func gunzip(_ data: Data) throws -> Data {
        let bytes = [UInt8](data)
        guard bytes.count >= 18, bytes[0] == 0x1f, bytes[1] == 0x8b, bytes[2] == 8 else {
            throw PeakDataError("山データを展開できません(gzip ではありません)")
        }
        let flags = bytes[3]
        var pos = 10
        if flags & 0x04 != 0 { // FEXTRA
            guard pos + 2 <= bytes.count else { throw PeakDataError("山データを展開できません") }
            pos += 2 + Int(bytes[pos]) + Int(bytes[pos + 1]) << 8
        }
        for flag in [UInt8(0x08), 0x10] where flags & flag != 0 { // FNAME, FCOMMENT は 0 で終わる文字列
            while pos < bytes.count && bytes[pos] != 0 { pos += 1 }
            pos += 1
        }
        if flags & 0x02 != 0 { pos += 2 } // FHCRC
        guard pos < bytes.count - 8 else { throw PeakDataError("山データを展開できません") }
        // 末尾 4 バイトは展開後のサイズ(リトルエンディアン、4 GB で一周する)。
        let n = bytes.count
        let expected = Int(bytes[n - 4]) | Int(bytes[n - 3]) << 8 | Int(bytes[n - 2]) << 16 | Int(bytes[n - 1]) << 24
        guard expected > 0, expected <= maxSizeBytes * 20 else { throw PeakDataError("山データを展開できません") }

        #if canImport(Compression)
        let deflate = Array(bytes[pos..<(n - 8)])
        var output = [UInt8](repeating: 0, count: expected + 1)
        let written = deflate.withUnsafeBufferPointer { src in
            output.withUnsafeMutableBufferPointer { dst in
                compression_decode_buffer(dst.baseAddress!, dst.count, src.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written == expected else { throw PeakDataError("山データを展開できません") }
        return Data(output[0..<written])
        #else
        throw PeakDataError("この環境では gzip を展開できません")
        #endif
    }

    /// 取り込むタイル。`coverage` と山のある範囲を合わせた矩形のタイルを、`foreignAreas` を除いてすべて取得済みにする。
    /// 山が 0 件の海のタイルも含めないと、海に近い場所で「一部の山データがありません」と出てしまう。
    /// 山のあるタイルは、`foreignAreas` の中でも含める。
    public static func tiles(of mountains: [Mountain]) -> [Tile] {
        guard !mountains.isEmpty else { return [] }
        let withMountains = Set(mountains.map { Tile.of($0.latitude, $0.longitude) })
        return Tile.covering(BoundingBox(
            south: min(coverage.south, mountains.map(\.latitude).min()!),
            west: min(coverage.west, mountains.map(\.longitude).min()!),
            north: max(coverage.north, mountains.map(\.latitude).max()!),
            east: max(coverage.east, mountains.map(\.longitude).max()!)
        )).filter { withMountains.contains($0) || !isForeign($0) }
    }

    /// 方位盤で、取得していなくても欠けたものとして数えないタイル。現在地が日本側なら、`foreignAreas` のタイルは
    /// 配信データに無いのが当たり前なので数えない(国境の近くで「一部の山データがありません」が出続けないように)。
    /// 現在地が `foreignAreas` の中なら、どのタイルも数えて「データがありません」と知らせる。
    public static func ignoresMissing(latitude: Double, longitude: Double) -> @Sendable (Tile) -> Bool {
        if isForeign(Tile.of(latitude, longitude)) { return { _ in false } }
        return { PeakData.isForeign($0) }
    }

    /// タイルの中心が `foreignAreas` に入るか。
    public static func isForeign(_ tile: Tile) -> Bool {
        let b = tile.bounds
        let lat = (b.south + b.north) / 2
        let lon = (b.west + b.east) / 2
        return foreignAreas.contains { $0.contains(lat, lon) }
    }
}

/// 配信データの最新版を確かめ、新しければ取得・検証してキャッシュに取り込む。
/// 取り込む前に失敗したら、キャッシュの内容はそのまま残る。
/// 取り込んだ manifest とデータ本体は `archive` に組で残し、読み込み処理の版が上がったら、そこから作り直す。
public struct PeakDataUpdater: Sendable {
    private let source: PeakDataSource
    private let cache: MountainCache
    private let archive: PeakDataArchive?
    private let clock: @Sendable () -> Date
    /// 読む山データの種類。manifest の fileName と地点の ID の読み方が変わる。
    private let dataset: PeakDataset

    public init(source: PeakDataSource, cache: MountainCache, archive: PeakDataArchive? = nil,
                clock: @escaping @Sendable () -> Date = { Date() }, dataset: PeakDataset = .osmPeaks) {
        self.source = source
        self.cache = cache
        self.archive = archive
        self.clock = clock
        self.dataset = dataset
    }

    /// 確認の結果。
    public enum Check: Equatable, Sendable {
        /// 取り込み済みの版が最新だった。
        case upToDate(InstalledPeakData)
        /// 取り込める版がある。データ本体はまだ取っていない。
        case available(PeakDataOffer)
    }

    /// manifest だけを取って、取り込める版があるかを確かめる。データ本体は取りに行かないので、
    /// 大きさ(`PeakDataOffer.sizeBytes`)を利用者に見せてから `install` で取り込める。
    /// - Parameter installed: 取り込み済みの版。nil なら manifest の版によらず取り込める版として返す。
    ///   古い読み込み処理で作った保存データ(`rebuild` で作り直せなかったもの)も、版によらず取り込める版として返す。
    public func check(installed: InstalledPeakData?) async throws -> Check {
        let installed = installed.flatMap { $0.readerVersion >= PeakData.readerVersion ? $0 : nil }
        let response = try await source.fetchManifest(etag: installed?.manifestEtag)
        let body: Data
        let etag: String?
        switch response {
        case .notModified:
            guard let installed else { throw PeakDataError("manifest が返ってきませんでした") }
            return .upToDate(installed)
        case .fetched(let fetchedBody, let fetchedEtag):
            body = fetchedBody
            etag = fetchedEtag
        }
        let manifest = try PeakData.parseManifest(body, dataset: dataset)
        if var installed, installed.version == manifest.version {
            installed.manifestEtag = etag
            return .upToDate(installed)
        }
        return .available(PeakDataOffer(manifest: manifest, manifestBody: body, etag: etag))
    }

    /// `check` で見つけた版のデータ本体を取得・検証して取り込む。
    public func install(_ offer: PeakDataOffer) async throws -> InstalledPeakData {
        let manifest = offer.manifest
        let data = try await source.fetchData(manifest.downloadUrl)
        try PeakData.verify(data, against: manifest)
        let mountains = try PeakData.parseMountains(data, dataset: dataset)
        guard !mountains.isEmpty else { throw PeakDataError("山データが空です") }
        try await cache.replaceTiles(PeakData.tiles(of: mountains), mountains: mountains, fetchedAt: clock())
        // 保存データを作り直せてから置き換える。途中で失敗したら、前の版の組が残る。
        await archive?.write(manifest: offer.manifestBody, data: data)
        return InstalledPeakData(
            version: manifest.version,
            sourceTimestamp: manifest.sourceTimestamp,
            mountainCount: mountains.count,
            manifestEtag: offer.etag,
            installedAt: clock(),
            readerVersion: PeakData.readerVersion,
            dataSha256: manifest.sha256,
            manifestSha256: PeakData.sha256Hex(offer.manifestBody),
            experimental: manifest.experimental
        )
    }

    /// `installed` が古い読み込み処理で作った保存データなら、端末に残した manifest と gz から通信せずに作り直す。
    /// 作り直したら新しい記録を、作り直す必要が無いか、manifest か gz が無い・SHA-256 が合わない・読めないときは nil を返す。
    /// nil のときに作り直しが必要なら、次の `check` で取り込める版として返す。
    public func rebuild(installed: InstalledPeakData?) async throws -> InstalledPeakData? {
        guard var installed, installed.readerVersion < PeakData.readerVersion,
              let archived = await archive?.read(),
              let manifestSha256 = installed.manifestSha256, PeakData.sha256Hex(archived.manifest) == manifestSha256,
              let sha256 = installed.dataSha256, PeakData.sha256Hex(archived.data) == sha256,
              let manifest = try? PeakData.parseManifest(archived.manifest, dataset: dataset),
              (try? PeakData.verify(archived.data, against: manifest)) != nil,
              let mountains = try? PeakData.parseMountains(archived.data, dataset: dataset), !mountains.isEmpty else { return nil }
        try await cache.replaceTiles(PeakData.tiles(of: mountains), mountains: mountains, fetchedAt: clock())
        installed.mountainCount = mountains.count
        installed.readerVersion = PeakData.readerVersion
        return installed
    }
}

/// yamamuki のカタログ(catalog.json)から `dataset` の manifest をたどって、配信データを取得する。
/// カタログは毎回取り直し(1 KB ほど)、指す manifest の SHA-256 が前回と同じなら manifest は取り直さない。
/// manifest のコピーは書き換えない決まりなので、SHA-256 を ETag の代わりに使う(0.7.0 までの current.json と同じ扱いなので、
/// 同じ manifest を指している間は、0.7.0 から上げても取り直さない)。
public struct CatalogPeakDataSource: PeakDataSource {
    private let session: URLSession
    private let dataset: PeakDataset
    private let useDev: Bool
    private let catalogUrl: URL
    private let userAgent: String

    public init(session: URLSession = .shared, dataset: PeakDataset = .osmPeaks, useDev: Bool, catalogUrl: URL = PeakData.catalogUrl,
                userAgent: String = "yamamuki-ios") {
        self.session = session
        self.dataset = dataset
        self.useDev = useDev
        self.catalogUrl = catalogUrl
        self.userAgent = userAgent
    }

    public func fetchManifest(etag: String?) async throws -> ManifestResponse {
        let pointer = try PeakData.parseCatalog(try await get(catalogUrl), useDev: useDev, dataset: dataset)
        if pointer.manifestSha256 == etag { return .notModified }
        let manifest = try await get(pointer.manifestUrl)
        guard PeakData.sha256Hex(manifest) == pointer.manifestSha256 else {
            throw PeakDataError("山データの manifest の SHA-256 がカタログと合いません")
        }
        return .fetched(body: manifest, etag: pointer.manifestSha256)
    }

    public func fetchData(_ url: URL) async throws -> Data { try await get(url) }

    private func get(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        // 差し替えたばかりのカタログを古いまま読まないよう、端末の HTTP キャッシュは使わない。
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await session.data(for: request)
        let http = response as? HTTPURLResponse
        guard let status = http?.statusCode, (200..<300).contains(status) else {
            throw PeakDataError("サーバーが HTTP \(http.map { "\($0.statusCode)" } ?? "-") を返しました(\(url.lastPathComponent))。")
        }
        return data
    }
}

/// HTTP で manifest を直接読んで、配信データを取得する。
public struct HTTPPeakDataSource: PeakDataSource {
    private let session: URLSession
    private let manifestUrl: URL
    private let userAgent: String

    public init(session: URLSession = .shared, manifestUrl: URL, userAgent: String = "yamamuki-ios") {
        self.session = session
        self.manifestUrl = manifestUrl
        self.userAgent = userAgent
    }

    public func fetchManifest(etag: String?) async throws -> ManifestResponse {
        var request = URLRequest(url: manifestUrl)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        // URLSession の HTTP キャッシュが 304 を 200 に置き換えないよう、自分で ETag を送るときはキャッシュを使わない。
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (data, response) = try await session.data(for: request)
        let http = response as? HTTPURLResponse
        if http?.statusCode == 304 { return .notModified }
        try check(http, manifestUrl)
        return .fetched(body: data, etag: http?.value(forHTTPHeaderField: "ETag"))
    }

    public func fetchData(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        try check(response as? HTTPURLResponse, url)
        return data
    }

    private func check(_ response: HTTPURLResponse?, _ url: URL) throws {
        guard let status = response?.statusCode, (200..<300).contains(status) else {
            throw PeakDataError("サーバーが HTTP \(response.map { "\($0.statusCode)" } ?? "-") を返しました(\(url.lastPathComponent))。")
        }
    }
}
