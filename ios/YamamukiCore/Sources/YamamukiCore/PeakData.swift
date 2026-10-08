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
    /// 収録した山の数。版 4 は mountainCount、版 5 は pointCount。
    public let mountainCount: Int
    /// 元にした OSM データの基準日時(UTC、例: 2026-09-30T20:21:22Z)。版 5 で省略されたときは空文字。
    public let sourceTimestamp: String
}

/// 取り込み済みの配信データ。設定画面に出し、次の確認で同じ版なら取り直さない。
public struct InstalledPeakData: Codable, Equatable, Sendable {
    public var version: String
    public var sourceTimestamp: String
    public var mountainCount: Int
    /// manifest を取ったときの ETag。次の確認で送り、変わっていなければ manifest も取り直さない。
    public var manifestEtag: String?
    public var installedAt: Date

    public init(version: String, sourceTimestamp: String, mountainCount: Int, manifestEtag: String?, installedAt: Date) {
        self.version = version
        self.sourceTimestamp = sourceTimestamp
        self.mountainCount = mountainCount
        self.manifestEtag = manifestEtag
        self.installedAt = installedAt
    }
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

/// 配信データの取得元。テストではフェイクに差し替える。
public protocol PeakDataSource: Sendable {
    /// - Parameter etag: 前回の ETag。nil なら必ず取得する。
    func fetchManifest(etag: String?) async throws -> ManifestResponse
    func fetchData(_ url: URL) async throws -> Data
}

public enum PeakData {
    /// 開発版の最新版の manifest の URL。開発用のビルド(Android の debug、iOS の Debug)で、取得先に開発版を選んだときに読む。
    /// 開発版が取れないときに正式版へ自動で切り替えることはしない(yamamuki-data の方針)。
    /// yamamuki-data の新しい置き場所(points/osm-peaks-dev/)で、manifest は版 5。
    public static let devManifestUrl = URL(string: "https://shohei0205.github.io/yamamuki-data/points/osm-peaks-dev/manifest.json")!

    /// 正式版の最新版の manifest の URL。配布用のビルド(Release)と、取得先を選ばなかった開発用のビルドはこれを読む。
    /// 配布済みのアプリ(0.5.0 まで)のために yamamuki-data が残している置き場所で、manifest は版 4。
    public static let stableManifestUrl = URL(string: "https://shohei0205.github.io/yamamuki-data/peaks/manifest.json")!

    /// ビルドの種類に合う manifest の URL。
    public static func manifestUrl(dev: Bool) -> URL { dev ? devManifestUrl : stableManifestUrl }

    /// 読める manifest の形式の版。版 4 で downloadUrl が入った。版 5 で件数が pointCount になり、
    /// データ本体の版(dataSchemaVersion)が別に書かれるようになった。
    public static let supportedSchemaVersions: Set<Int> = [4, 5]

    /// 読めるデータ本体の版(manifest 版 5 の dataSchemaVersion)。地点データ版 5 は、山ごとの項目(osmId・name・latitude・
    /// longitude・elevationM)が版 4 までと同じで、読み仮名などの項目が増えただけなので、そのまま読める。
    public static let supportedDataSchemaVersions: Set<Int> = [5]

    /// 地点データ版 5 の、山頂の種別。
    static let peakType = "peak"

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

    public static func parseManifest(_ body: Data) throws -> PeakManifest {
        guard let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
            throw PeakDataError("manifest を読めません")
        }
        func string(_ key: String) throws -> String {
            guard let value = root[key] as? String else { throw PeakDataError("manifest に \(key) がありません") }
            return value
        }
        func int(_ key: String) throws -> Int {
            // JSONSerialization は true / false も NSNumber で返すので、整数だけを通す。
            guard let number = root[key] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                  number.doubleValue == number.doubleValue.rounded() else {
                throw PeakDataError("manifest の \(key) が整数ではありません")
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
            mountainCount: try int(schemaVersion >= 5 ? "pointCount" : "mountainCount"),
            sourceTimestamp: schemaVersion >= 5 ? root["sourceTimestamp"] as? String ?? "" : try string("sourceTimestamp")
        )
        guard (1...maxSizeBytes).contains(manifest.sizeBytes) else {
            throw PeakDataError("manifest のサイズ \(manifest.sizeBytes) バイトは受け取れません")
        }
        return manifest
    }

    /// 受け取ったデータ本体が、manifest のサイズと SHA-256 に合うか確かめる。
    public static func verify(_ data: Data, against manifest: PeakManifest) throws {
        guard data.count == manifest.sizeBytes else {
            throw PeakDataError("山データのサイズが合いません(\(data.count) バイト、manifest では \(manifest.sizeBytes) バイト)")
        }
        guard sha256Hex(data) == manifest.sha256 else { throw PeakDataError("山データの SHA-256 が合いません") }
    }

    static func sha256Hex(_ data: Data) -> String {
        #if canImport(CryptoKit)
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        #else
        fatalError("SHA-256 の計算には CryptoKit が必要です")
        #endif
    }

    /// gzip を展開し、JSON 配列の山を読む。名前・座標・osmId のない項目と、山頂以外の種別(type)の項目は飛ばす。
    /// 種別の無い項目(版 4 までと、版 5 で種別不明のもの)は山頂として読む。
    public static func parseMountains(_ gzip: Data) throws -> [Mountain] {
        let json = try gunzip(gzip)
        guard let items = (try? JSONSerialization.jsonObject(with: json)) as? [Any] else {
            throw PeakDataError("山データを読めません")
        }
        var seen = Set<Int64>()
        return items.compactMap { item -> Mountain? in
            guard let m = item as? [String: Any],
                  (m["type"] as? String ?? peakType) == peakType,
                  let osmId = (m["osmId"] as? NSNumber)?.int64Value,
                  let name = (m["name"] as? String)?.trimmingCharacters(in: .whitespaces), !name.isEmpty,
                  let latitude = (m["latitude"] as? NSNumber)?.doubleValue,
                  let longitude = (m["longitude"] as? NSNumber)?.doubleValue,
                  seen.insert(osmId).inserted
            else { return nil }
            return Mountain(osmId: osmId, name: name, latitude: latitude, longitude: longitude,
                            elevationM: (m["elevationM"] as? NSNumber)?.doubleValue)
        }
    }

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
public struct PeakDataUpdater: Sendable {
    public enum Result: Equatable, Sendable {
        /// 取り込み済みの版が最新だった。
        case upToDate(InstalledPeakData)
        case updated(InstalledPeakData)

        public var installed: InstalledPeakData {
            switch self {
            case .upToDate(let installed), .updated(let installed): return installed
            }
        }
    }

    private let source: PeakDataSource
    private let cache: MountainCache
    private let clock: @Sendable () -> Date

    public init(source: PeakDataSource, cache: MountainCache, clock: @escaping @Sendable () -> Date = { Date() }) {
        self.source = source
        self.cache = cache
        self.clock = clock
    }

    /// - Parameter installed: 取り込み済みの版。nil なら manifest の版によらず取得する。
    public func update(installed: InstalledPeakData?) async throws -> Result {
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
        let manifest = try PeakData.parseManifest(body)
        if var installed, installed.version == manifest.version {
            installed.manifestEtag = etag
            return .upToDate(installed)
        }

        let data = try await source.fetchData(manifest.downloadUrl)
        try PeakData.verify(data, against: manifest)
        let mountains = try PeakData.parseMountains(data)
        guard !mountains.isEmpty else { throw PeakDataError("山データが空です") }
        try await cache.replaceTiles(PeakData.tiles(of: mountains), mountains: mountains, fetchedAt: clock())
        return .updated(InstalledPeakData(
            version: manifest.version,
            sourceTimestamp: manifest.sourceTimestamp,
            mountainCount: mountains.count,
            manifestEtag: etag,
            installedAt: clock()
        ))
    }
}

/// HTTP で配信データを取得する。
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
