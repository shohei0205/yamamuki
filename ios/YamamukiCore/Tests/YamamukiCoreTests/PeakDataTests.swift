import Foundation
import XCTest
@testable import YamamukiCore

private final class FakeSource: PeakDataSource, @unchecked Sendable {
    var manifestBody: Data
    var data: Data
    var etag: String? = "\"e1\""
    var sentEtags: [String?] = []
    var dataCalls = 0

    init(manifest: Data, data: Data) {
        manifestBody = manifest
        self.data = data
    }

    func fetchManifest(etag: String?) async throws -> ManifestResponse {
        sentEtags.append(etag)
        if let etag, etag == self.etag { return .notModified }
        return .fetched(body: manifestBody, etag: self.etag)
    }

    func fetchData(_ url: URL) async throws -> Data {
        dataCalls += 1
        return data
    }
}

private final class MemoryCache: MountainCache, @unchecked Sendable {
    var tiles: [Tile: Date] = [:]
    var mountains: [Int64: Mountain] = [:]

    func fetchedAt(_ tiles: [Tile]) async throws -> [Tile: Date] { self.tiles.filter { tiles.contains($0.key) } }

    func mountains(in box: BoundingBox) async throws -> [Mountain] {
        mountains.values.filter { box.contains($0.latitude, $0.longitude) }
    }

    func replaceTiles(_ tiles: [Tile], mountains: [Mountain], fetchedAt: Date) async throws {
        let targets = Set(tiles)
        self.mountains = self.mountains.filter { !targets.contains(Tile.of($0.value.latitude, $0.value.longitude)) }
        for m in mountains { self.mountains[m.osmId] = m }
        for t in tiles { self.tiles[t] = fetchedAt }
    }
}

private final class MemoryArchive: PeakDataArchive, @unchecked Sendable {
    var archived: ArchivedPeakData?

    init(_ archived: ArchivedPeakData? = nil) {
        self.archived = archived
    }

    func read() async -> ArchivedPeakData? { archived }
    func write(manifest: Data, data: Data) async { archived = ArchivedPeakData(manifest: manifest, data: data) }
    func delete() async { archived = nil }
}

final class PeakDataTests: XCTestCase {
    /// yamamuki-data の peaks/README.md の例と同じ形の 2 件(2 件目は標高もふりがなも無い)を、
    /// Python の gzip.compress(mtime=0) で圧縮したもの。Swift には gzip の圧縮が無いので、出来上がりを埋め込む。
    private let gzip = Data(base64Encoded:
        "H4sIAAAAAAACA2WQzUrDQBSF3+VCdmHyHzPZVajgwoWCq9LFQIY6mialSc1CupgqiosWRVC7UsGF4sYqSN9nGqVv4YRoShRmcefcO9+dc1pHECfdzQB8y9YtjHXb0VWISJeCD4v52WJ+vjye5G/voEJIUpYOAlrMIs8117BrSTWOOj+yYWGk64bnmI4KNKSH8kEcbcmGrTvILbk7lAQs6ki84K9idCX4teAfYjQW/FTwe8Ev5SoSMpLQBPwW5I/P+d0kn82grULGDliPBozs9kNJ2EvTXuJr2j5BVQfF/Y5W3DSl6SgNW2ngosAbitcsinVDHihRAUlLUjQIw6FaZWGYVQb5xfjr5EHwm8+n6fLlthaDbSGnHoBtIly3XpD/GC+llcV/vsqB2gcrq1mWod/Oyuq2AcP2N2Sakz/MAQAA"
    )!
    private let gzipSha256 = "32fd4afa156179dee321e90bebc045b99b4cca902bf8bbf43ce6fbd7cf2e4483"

    private func manifest(version: String = "v1", sha256: String? = nil, size: Int? = nil, schemaVersion: Int = 4,
                          downloadUrl: String = "https://example.com/japan-mountains.json.gz") -> Data {
        Data("""
        {"schemaVersion":\(schemaVersion),"version":"\(version)","downloadUrl":"\(downloadUrl)",
         "fileName":"japan-mountains.json.gz","sha256":"\((sha256 ?? gzipSha256).uppercased())","sizeBytes":\(size ?? gzip.count),
         "uncompressedSizeBytes":460,"mountainCount":2,"sourceTimestamp":"2026-09-30T20:21:22Z",
         "latestMountainTimestamp":"2026-09-29T08:06:38Z","sourceUrl":"https://download.geofabrik.de/asia/japan-260929.osm.pbf",
         "license":"ODbL-1.0","attribution":"© OpenStreetMap contributors"}
        """.utf8)
    }

    func testParsesManifest() throws {
        let m = try PeakData.parseManifest(manifest())
        XCTAssertEqual(m.version, "v1")
        XCTAssertEqual(m.downloadUrl.absoluteString, "https://example.com/japan-mountains.json.gz")
        XCTAssertEqual(m.sha256, gzipSha256, "16進数は小文字にそろえる")
        XCTAssertEqual(m.sizeBytes, gzip.count)
        XCTAssertEqual(m.mountainCount, 2)
        XCTAssertEqual(m.sourceTimestamp, "2026-09-30T20:21:22Z")
    }

    /// yamamuki-data の README.md の manifest 版 5 と同じ形。件数は pointCount、データ本体の版は dataSchemaVersion。
    private func manifestV5(dataSchemaVersion: String = "5", sourceTimestamp: String? = "2026-09-30T20:21:22Z") -> Data {
        let source = sourceTimestamp.map { #""sourceTimestamp":"\#($0)","# } ?? ""
        return Data("""
        {"schemaVersion":5,"dataSchemaVersion":\(dataSchemaVersion),"name":"山頂","version":"v1",
         "fileName":"osm-peaks.json.gz","downloadUrl":"https://example.com/osm-peaks.json.gz",
         "sha256":"\(gzipSha256)","sizeBytes":\(gzip.count),"uncompressedSizeBytes":460,"pointCount":2,\(source)
         "license":"ODbL-1.0","attribution":"© OpenStreetMap contributors"}
        """.utf8)
    }

    func testParsesManifestV5() throws {
        let m = try PeakData.parseManifest(manifestV5())
        XCTAssertEqual(m.schemaVersion, 5)
        XCTAssertEqual(m.downloadUrl.absoluteString, "https://example.com/osm-peaks.json.gz")
        XCTAssertEqual(m.mountainCount, 2)
        XCTAssertEqual(m.sourceTimestamp, "2026-09-30T20:21:22Z")
        // 版 5 では元データの日時を省略できる。
        XCTAssertEqual(try PeakData.parseManifest(manifestV5(sourceTimestamp: nil)).sourceTimestamp, "")
    }

    /// yamamuki-data の formats/manifest.md の manifest 版 6 と同じ形。件数と出典は metadata にある。
    private func manifestV6(data: Data? = nil, schemaFormat: String = "yamamuki-manifest",
                            dataSchemaFormat: String = "yamamuki-points", dataSchemaVersion: String = "6",
                            metadata: String = #"{"sources":[{"name":"日本百名山","latestTimestamp":"2026-01-01T00:00:00Z"},"#
                                + #"{"name":"osm-peaks","license":"ODbL-1.0","latestTimestamp":"2026-10-17T04:42:52Z"}],"pointCount":2}"#,
                            metadataKey: String = "metadata") -> Data {
        let data = data ?? gzip
        return Data("""
        {"schemaFormat":"\(schemaFormat)","schemaVersion":6,"dataSchemaVersion":\(dataSchemaVersion),"name":"山頂",
         "version":"20261018T115955Z-39125410649-1","fileName":"osm-peaks.json.gz",
         "downloadUrl":"https://example.com/osm-peaks.json.gz","sha256":"\(PeakData.sha256Hex(data))","sizeBytes":\(data.count),
         "uncompressedSizeBytes":100,"dataTimestamp":"2026-10-18T12:00:15Z","dataSchemaFormat":"\(dataSchemaFormat)",
         "\(metadataKey)":\(metadata)}
        """.utf8)
    }

    func testParsesManifestV6() throws {
        let m = try PeakData.parseManifest(manifestV6())
        XCTAssertEqual(m.schemaVersion, 6)
        XCTAssertEqual(m.version, "20261018T115955Z-39125410649-1")
        XCTAssertEqual(m.downloadUrl.absoluteString, "https://example.com/osm-peaks.json.gz")
        XCTAssertEqual(m.mountainCount, 2, "件数は metadata.pointCount から読む")
        XCTAssertEqual(m.sourceTimestamp, "2026-10-17T04:42:52Z", "出典の latestTimestamp を読む")
        // 出典の名前によらず、latestTimestamp のうち最も新しいものを読む(2026-10-20 の版から元データの出典の名前が変わったため)。
        let renamed = #"{"sources":[{"name":"日本百名山"},{"name":"OpenStreetMap（ロシア極東連邦管区・Geofabrik 配布）","latestTimestamp":"2026-10-16T21:00:00Z"},"#
            + #"{"name":"OpenStreetMap（日本・Geofabrik 配布）","latestTimestamp":"2026-10-17T04:42:52Z"}],"pointCount":2}"#
        XCTAssertEqual(try PeakData.parseManifest(manifestV6(metadata: renamed)).sourceTimestamp, "2026-10-17T04:42:52Z")
        // どの出典にも latestTimestamp が無いときは空文字。
        XCTAssertEqual(try PeakData.parseManifest(manifestV6(metadata: #"{"sources":[{"name":"osm-peaks"}],"pointCount":2}"#)).sourceTimestamp, "")
        XCTAssertEqual(try PeakData.parseManifest(manifestV6(metadata: #"{"pointCount":2}"#)).sourceTimestamp, "")
    }

    func testRejectsManifestV6OfOtherFormats() {
        // 形式の名前が違うものと、件数の無いものは読まない。
        XCTAssertThrowsError(try PeakData.parseManifest(manifestV6(schemaFormat: "other")))
        XCTAssertThrowsError(try PeakData.parseManifest(manifestV6(dataSchemaFormat: "other")))
        XCTAssertThrowsError(try PeakData.parseManifest(manifestV6(metadata: #"{"sources":[]}"#)))
        XCTAssertThrowsError(try PeakData.parseManifest(manifestV6(metadata: #"{"pointCount":"2"}"#)))
        XCTAssertThrowsError(try PeakData.parseManifest(manifestV6(metadataKey: "meta")))
        // 版 6 の manifest でも、データ本体の版が読めなければ断る。
        XCTAssertThrowsError(try PeakData.parseManifest(manifestV6(dataSchemaVersion: "7")))
    }

    func testRejectsManifestOfOtherDataset() throws {
        // 版 5 以降は、データ本体のファイル名が osm-peaks.json.gz でなければ読まない(gsi-peaks などを取り違えないため)。
        func replacing(_ data: Data, _ target: String, _ replacement: String) -> Data {
            Data(String(decoding: data, as: UTF8.self).replacingOccurrences(of: target, with: replacement).utf8)
        }
        XCTAssertThrowsError(try PeakData.parseManifest(replacing(manifestV6(), #""fileName":"osm-peaks.json.gz""#, #""fileName":"gsi-peaks.json.gz""#)))
        XCTAssertThrowsError(try PeakData.parseManifest(replacing(manifestV5(), #""fileName":"osm-peaks.json.gz","#, "")))
        // 版 4 までは、ファイル名を見ない(japan-mountains.json.gz だった)。
        XCTAssertEqual(try PeakData.parseManifest(manifest()).mountainCount, 2)
    }

    func testRejectsUnknownSchemaVersion() {
        XCTAssertThrowsError(try PeakData.parseManifest(manifest(schemaVersion: 7))) { error in
            XCTAssertTrue((error as? PeakDataError)?.message.contains("アプリを更新") == true)
        }
        XCTAssertThrowsError(try PeakData.parseManifest(manifest(schemaVersion: 3)))
    }

    func testRejectsUnknownDataSchemaVersion() {
        XCTAssertThrowsError(try PeakData.parseManifest(manifestV5(dataSchemaVersion: "7"))) { error in
            XCTAssertTrue((error as? PeakDataError)?.message.contains("アプリを更新") == true)
        }
        // データ本体の版が分からないときは、manifest の版から推測せずに断る。
        XCTAssertThrowsError(try PeakData.parseManifest(manifestV5(dataSchemaVersion: "null")))
    }

    func testParsesPointsV5() throws {
        // 地点データ版 5。osmId の無い地点と、山頂以外の種別の地点は飛ばす。種別の無い地点は山頂として読む。
        // 中身は Android の PeakDataTest.parsesPointsV5 と同じ 4 件を、Python の gzip.compress(mtime=0) で圧縮したもの。
        let v5 = Data(base64Encoded:
            "H4sIAAAAAAAC/4uuVspMUbJSUDJU0lFQKqksSAVxClITs0H8/OJcT5CsIZCdl5gLlnu6cePLBU0g2ZzEksyS0hSQqLGpngFIJD8vHSZkaGwBFkvNSS0DKszP8wUJGhiABUsS04uB3GilZ9OXPpuz5vnMfU8n9AJNVoqt1VGAOckI2QlGSE54vmLd046lT3b0PpvRh+kQQywOMUR4Lq80JwfJEmMUfycWZWfmpSPba4xk78vlE17s7nq6YAumpUZYLDVCssYEW/DCzPUP9lV43Lji5bRFLxsWP9k943HjxMeN6563LHzc2AIKFAzbjLHYZlwbCwDxOcQXzQEAAA=="
        )!
        XCTAssertEqual(try PeakData.parseMountains(v5), [
            Mountain(osmId: 1, name: "山頂", latitude: 35.0, longitude: 138.0, elevationM: 100.0, pointId: "1", tags: ["日本百名山"]),
            Mountain(osmId: 2, name: "種別不明", latitude: 35.1, longitude: 138.1, elevationM: nil, pointId: "2"),
        ])
    }

    func testParsesPointV5Fields() throws {
        // 地点データ版 5 で増えた項目と、読み仮名・別名・URL。形の合わない値は無いものとして扱う。
        // 中身は Android の PeakDataTest.parsesPointV5Fields と同じ 3 件を、Python の gzip.compress(mtime=0) で圧縮したもの。
        let v5 = Data(base64Encoded:
            "H4sIAAAAAAAC/5VRy0rDQBTd+xVh1nHSZmirQdy7cKHgqnQxNEMaO3nQpE2lZFF3SgtCES24E1cKWkQQF/5MmtrP8E5S7SMtIgQy99wz98w9p9xBpo40lEcy8s9cBkeX0TpUjmcdQCcvI5taAo+fe/H9YzwaQZNT3/SbOsCkgEkRAMc2Zkie7OCSKiPGWQtojn0IrFKpiHPyliQl044Z1U3bgKFS1H2LurdR9zo6H0gwmXKTesxDWhmN3/vjj14quCgOnzS9HE4HF/Hri7izQCSVRCQw66bLdJOeNDio1Hzf9TRFOaX4t4OdhqGISmnDCHHQqb/CD4IA/3Tm/COyqxIwTAj51EjeOrl5mNw9fQ0/46t++spVpCIjo0HdmllFWgd5LbH9HvyUfSB7VcqFdbgQyqhp120nsAWNAhaGoDSLSd0UkzqPKRtQLpPPmijsJucL9qflGh/BFtamlssZrjqWkvFOvDAxJeuJ0PzTg+2lhcmmhcl/F15STqXUMKx8A0BqkMUBAwAA"
        )!
        XCTAssertEqual(try PeakData.parseMountains(v5), [
            Mountain(osmId: 1, name: "富士山", latitude: 35.36, longitude: 138.72, elevationM: 3776.0,
                     pointId: "1", nameReading: "ふじさん", aliases: ["不二山", "芙蓉峰"],
                     wikipediaUrl: "https://ja.wikipedia.org/wiki/x", wikidataUrl: "https://www.wikidata.org/wiki/Q39231",
                     tags: ["日本百名山"], graphic: PointGraphic(svg: "<svg/>", scale: 1.5)),
            Mountain(osmId: 2, name: "山", latitude: 35.0, longitude: 138.0, elevationM: nil,
                     pointId: "2", graphic: PointGraphic(svg: "<svg/>", scale: 1.0)),
            Mountain(osmId: 3, name: "山", latitude: 35.0, longitude: 138.0, elevationM: nil, pointId: "3"),
        ])
    }

    /// yamamuki-data の points/formats/data.md の地点データ版 6 と同じ形。地点は points に入り、ID は osm:<ノード番号>。
    /// 中身は Android の PeakDataTest.pointsV6 と同じ 5 件を、Python の gzip.compress(mtime=0) で圧縮したもの。
    private let pointsV6 = Data(base64Encoded:
        "H4sIAAAAAAAC/3VTy04bMRTd8xWR13lMZngks63UXTet2k3Ewk2s4GZemkcKirLwgECCVIlEKUWN1CeKlPQBbRESCPox7mTgL3o9k4ZkQnb29fU5555jN5BTXiM6fmjaOnaRijawjnWvRjOWSQ3XQelRwzNiO9Q0kLqcXkghnbi4gl2M1AZyTM8uEweppQYysE4AxHT0jEVwzUHN1TSKkB6YngH4S2nk4upU8/DweNj9Gh5dB51XwekpMIqDxwRXqFGFc84GnF1yf5+zn9zf5KzNfcbZFmdvoAgMTaFoJFctLaRSDUQrsQpVWVmUVgoFSZYB192wBKFQNmKB3e2v4+HZ+f3EH7m/w9k2Z3sR34HgSyMNu9T1KnBZKWalYlGCkmlUR7X8opSVFqEIQlKIaKQO7abxCKmyrCyDG7ZZJwY2ykSYJ0x8EhkYCwb48ZVx3fEsSyM6AQObMSzWKHamPQ92LsLrq+BHK/g8uGeUHvfbnJ1x9hYwGrPD/y9O3ulzdggHKOztha9PRtCrsYKXtEYtUqH4qa1B85rrWo6ay73A2fFJ1rSrObHLrYMgsRDTRv2Gp2njlzDzAqJEJ1OcG95N/xtnfzhr/b1ocfYlOXcJpaAUxdcXC3/AN3dhgskIl7KJ/JSCqEwGF6u983xKX9WhmTopu6at4ufleUKDd1dB933QPQk727dHnzj7Hr+4KSH5GSH5aSF5SUpas56fRxke9IP2uUheBLmVJJNnyOQktjIBje2asPTu2/Q6N5e7wYffSVxlBleBL/oPAJhMN2gEAAA="
    )!

    func testParsesPointsV6() throws {
        // OSM 以外の ID と、形の違う ID の地点は飛ばす。別名は name を読み、読み仮名が配列なら最初の空でないものを読む。
        XCTAssertEqual(try PeakData.parseMountains(pointsV6), [
            Mountain(osmId: 3_740_788_022, name: "鳥海山", latitude: 39.0990, longitude: 140.0490, elevationM: 2236.0,
                     pointId: "osm:3740788022", nameReading: "ちょうかいさん", aliases: ["出羽富士", "秋田富士"],
                     wikipediaUrl: "https://ja.wikipedia.org/wiki/x", tags: ["日本百名山"]),
            Mountain(osmId: 2, name: "読みが二つ", latitude: 35.0, longitude: 138.0, elevationM: nil,
                     pointId: "osm:2", nameReading: "かな"),
        ])
    }

    func testRejectsDocumentWithoutPoints() {
        // {"schemaVersion":6} と "文字列" を gzip.compress(mtime=0) で圧縮したもの。
        XCTAssertThrowsError(try PeakData.parseMountains(Data(base64Encoded: "H4sIAAAAAAAC/6tWKk7OSM1NDEstKs7Mz1OyMqsFAMI9SxYTAAAA")!))
        XCTAssertThrowsError(try PeakData.parseMountains(Data(base64Encoded: "H4sIAAAAAAAC/1N6Nq396drpTzumKwEAMJshHQsAAAA=")!))
    }

    func testReplacesV5DataWithV6Data() async throws {
        // 版 5 の山データを取り込んだ端末に、版 6 の山データが届いたときに入れ替わる。山は同じノード番号で引き継ぐ。
        let source = FakeSource(manifest: manifestV5(), data: gzip)
        let cache = MemoryCache()
        let updater = PeakDataUpdater(source: source, cache: cache)
        let installed = try await updater.update(installed: nil).installed
        XCTAssertEqual(Set(cache.mountains.keys), [3_403_990_450, 12])

        source.manifestBody = manifestV6(data: pointsV6)
        source.data = pointsV6
        source.etag = "\"e2\""
        let result = try await updater.update(installed: installed)

        guard case .updated(let latest) = result else { return XCTFail("版 6 の山データを取り込むはず") }
        XCTAssertEqual(latest.version, "20261018T115955Z-39125410649-1")
        XCTAssertEqual(Set(cache.mountains.keys), [3_740_788_022, 2])
        XCTAssertEqual(latest.mountainCount, 2, "件数は実際に読めた山の数")
    }

    func testRejectsBrokenManifest() {
        XCTAssertThrowsError(try PeakData.parseManifest(Data(#"{"schemaVersion":4"#.utf8)))
        XCTAssertThrowsError(try PeakData.parseManifest(Data(#"{"schemaVersion":4,"version":"v1"}"#.utf8)))
        XCTAssertThrowsError(try PeakData.parseManifest(manifest(downloadUrl: "http://example.com/a.gz")))
    }

    func testVerifiesSizeAndSha256() throws {
        let m = try PeakData.parseManifest(manifest())
        XCTAssertNoThrow(try PeakData.verify(gzip, against: m))
        XCTAssertThrowsError(try PeakData.verify(gzip.dropLast(), against: m))
        var tampered = gzip
        tampered[tampered.count - 1] &+= 1
        XCTAssertThrowsError(try PeakData.verify(tampered, against: m))
    }

    func testParsesMountains() throws {
        XCTAssertEqual(try PeakData.parseMountains(gzip), [
            Mountain(osmId: 3_403_990_450, name: "万三郎岳", latitude: 34.8627963, longitude: 139.0018525, elevationM: 1405.6,
                     nameReading: "ばんざぶろうだけ", aliases: ["天城山"],
                     wikipediaUrl: "https://ja.wikipedia.org/wiki/%E5%A4%A9%E5%9F%8E%E5%B1%B1"),
            Mountain(osmId: 12, name: "名無し標高", latitude: 43.5, longitude: 142.9, elevationM: nil,
                     wikidataUrl: "https://www.wikidata.org/wiki/Q1"),
        ])
    }

    func testRejectsDataThatIsNotGzip() {
        XCTAssertThrowsError(try PeakData.parseMountains(Data("[]".utf8)))
    }

    func testTilesCoverSeaBetweenMountains() throws {
        let tiles = PeakData.tiles(of: try PeakData.parseMountains(gzip))
        // 北緯 20〜46°・東経 122〜154° の矩形。山のない海のタイルも含む。
        XCTAssertTrue(tiles.contains(Tile.of(40.0, 141.0)))
        XCTAssertTrue(tiles.contains(Tile.of(20.1, 153.9)))
        // 日本の外の陸地は含めない(「データがありません」と知らせるため)。
        XCTAssertFalse(tiles.contains(Tile.of(35.1, 129.05)), "釜山")
        XCTAssertFalse(tiles.contains(Tile.of(33.4, 126.5)), "済州島")
        XCTAssertFalse(tiles.contains(Tile.of(37.5, 127.0)), "ソウル")
        XCTAssertFalse(tiles.contains(Tile.of(43.1, 131.9)), "ウラジオストク")
        XCTAssertFalse(tiles.contains(Tile.of(45.8, 142.5)), "サハリンの南端")
        // 境目の近くにある日本の島や岬のタイルは含める。
        XCTAssertTrue(tiles.contains(Tile.of(34.4, 129.3)), "対馬")
        XCTAssertTrue(tiles.contains(Tile.of(45.52, 141.94)), "宗谷岬")
        XCTAssertTrue(tiles.contains(Tile.of(42.15, 139.45)), "奥尻島")
        XCTAssertTrue(tiles.contains(Tile.of(45.3, 148.5)), "択捉島")
        XCTAssertTrue(tiles.contains(Tile.of(32.7, 128.8)), "五島列島")
        // 範囲の外にある山も、その山のタイルまで広げて取り込む。
        let outside = Mountain(osmId: 1, name: "外", latitude: 50.2, longitude: 160.3, elevationM: nil)
        XCTAssertTrue(PeakData.tiles(of: [outside]).contains(Tile.of(50.2, 160.3)))
        // 日本の外の陸地でも、山のあるタイルは含める。
        let busan = Mountain(osmId: 1, name: "外", latitude: 35.1, longitude: 129.05, elevationM: nil)
        XCTAssertTrue(PeakData.tiles(of: [busan]).contains(Tile.of(35.1, 129.05)))
    }

    func testImportsIntoCacheAndSkipsWhenUnchanged() async throws {
        let source = FakeSource(manifest: manifest(), data: gzip)
        let cache = MemoryCache()
        let now = Date(timeIntervalSince1970: 1_000)
        let updater = PeakDataUpdater(source: source, cache: cache, clock: { now })

        let first = try await updater.update(installed: nil)
        guard case .updated(let installed) = first else { return XCTFail("取り込むはず") }
        XCTAssertEqual(installed.version, "v1")
        XCTAssertEqual(installed.mountainCount, 2)
        XCTAssertEqual(Set(cache.mountains.keys), [3_403_990_450, 12])
        XCTAssertEqual(cache.tiles[Tile.of(40.0, 141.0)], now)
        XCTAssertEqual(source.sentEtags, [nil], "初回は ETag を送らない")

        // ETag が同じなら manifest もデータも取り直さない。
        let second = try await updater.update(installed: installed)
        guard case .upToDate = second else { return XCTFail("最新のはず") }
        XCTAssertEqual(source.sentEtags.last, "\"e1\"")
        XCTAssertEqual(source.dataCalls, 1)

        // ETag が変わっても版が同じなら、データは取り直さない。
        source.etag = "\"e2\""
        let third = try await updater.update(installed: second.installed)
        guard case .upToDate(let latest) = third else { return XCTFail("最新のはず") }
        XCTAssertEqual(latest.manifestEtag, "\"e2\"")
        XCTAssertEqual(source.dataCalls, 1)
    }

    func testReleaseDateComesFromVersion() {
        func manifest(_ version: String) -> PeakManifest {
            PeakManifest(schemaVersion: 5, version: version, downloadUrl: URL(string: "https://example.com/a.gz")!,
                         sha256: "0", sizeBytes: 1, mountainCount: 1, sourceTimestamp: "")
        }
        XCTAssertEqual(manifest("20261007T071401Z-37585754546-1").releaseDate, "2026/10/07")
        XCTAssertNil(manifest("v1").releaseDate)
        let installed = InstalledPeakData(version: "20261007T071401Z-37585754546-1", sourceTimestamp: "", mountainCount: 1,
                                          manifestEtag: nil, installedAt: Date())
        XCTAssertEqual(installed.releaseDate, "2026/10/07")
    }

    func testAutoCheckIsDueOncePerMonth() {
        let day = PeakData.autoCheckInterval
        let now = Date(timeIntervalSince1970: 10 * day)
        // まだ確かめたことが無ければ確かめる。
        XCTAssertTrue(PeakData.autoCheckDue(lastCheckedAt: nil, now: now))
        // 前回から 30 日たつまでは確かめない。
        XCTAssertFalse(PeakData.autoCheckDue(lastCheckedAt: now, now: now))
        XCTAssertFalse(PeakData.autoCheckDue(lastCheckedAt: now.addingTimeInterval(-day + 1), now: now))
        XCTAssertTrue(PeakData.autoCheckDue(lastCheckedAt: now.addingTimeInterval(-day), now: now))
        // 端末の時計が戻って前回が未来になったら確かめる。
        XCTAssertTrue(PeakData.autoCheckDue(lastCheckedAt: now.addingTimeInterval(1), now: now))
    }

    func testCheckReportsSizeWithoutFetchingData() async throws {
        let source = FakeSource(manifest: manifest(), data: gzip)
        let cache = MemoryCache()
        let updater = PeakDataUpdater(source: source, cache: cache)

        // 確認では manifest だけを取り、データ本体は取らない。キャッシュにも触れない。
        guard case .available(let offer) = try await updater.check(installed: nil) else { return XCTFail("取り込める版があるはず") }
        XCTAssertEqual(offer.sizeBytes, gzip.count)
        XCTAssertEqual(offer.manifest.version, "v1")
        XCTAssertEqual(source.dataCalls, 0)
        XCTAssertTrue(cache.mountains.isEmpty)

        // 利用者が取得を選んだら、確認した版を取り込む。
        let installed = try await updater.install(offer)
        XCTAssertEqual(installed.version, "v1")
        XCTAssertEqual(installed.manifestEtag, "\"e1\"")
        XCTAssertEqual(source.dataCalls, 1)
        XCTAssertEqual(Set(cache.mountains.keys), [3_403_990_450, 12])

        // 取り込んだあとの確認は、最新として返す。
        guard case .upToDate = try await updater.check(installed: installed) else { return XCTFail("最新のはず") }
        XCTAssertEqual(source.dataCalls, 1)
    }

    func testBrokenDataLeavesCacheUntouched() async {
        let source = FakeSource(manifest: manifest(), data: gzip.dropLast())
        let cache = MemoryCache()
        do {
            _ = try await PeakDataUpdater(source: source, cache: cache).update(installed: nil)
            XCTFail("壊れたデータは取り込まない")
        } catch {
            XCTAssertTrue(cache.mountains.isEmpty)
            XCTAssertTrue(cache.tiles.isEmpty)
        }
    }

    func testNewVersionReplacesOldMountains() async throws {
        let source = FakeSource(manifest: manifest(), data: gzip)
        let cache = MemoryCache()
        let updater = PeakDataUpdater(source: source, cache: cache)
        let installed = try await updater.update(installed: nil).installed

        // 以前 Overpass で取った山がキャッシュにある状態で、版だけが新しい manifest を返す。
        let stale = Mountain(osmId: 99, name: "以前 Overpass で取った山", latitude: 36.0, longitude: 138.0, elevationM: nil)
        cache.mountains[stale.osmId] = stale
        source.manifestBody = manifest(version: "v2")
        source.etag = "\"e2\""
        let result = try await updater.update(installed: installed)

        guard case .updated = result else { return XCTFail("新しい版は取り込むはず") }
        XCTAssertNil(cache.mountains[99], "配信範囲の古い山はキャッシュから消す")
    }

    /// 取り込んだときの manifest と gz の組。
    private func kept() -> MemoryArchive { MemoryArchive(ArchivedPeakData(manifest: manifest(), data: gzip)) }

    private func sha256(_ data: Data) -> String { PeakData.sha256Hex(data) }

    func testKeepsImportedManifestAndGzAndRecordsReaderVersion() async throws {
        let archive = MemoryArchive()
        let installed = try await PeakDataUpdater(source: FakeSource(manifest: manifest(), data: gzip), cache: MemoryCache(), archive: archive)
            .update(installed: nil).installed
        XCTAssertEqual(archive.archived?.data, gzip, "読まない項目も含めて、受け取った gz をそのまま残す")
        XCTAssertEqual(archive.archived?.manifest, manifest(), "manifest も組で残す")
        XCTAssertEqual(installed.readerVersion, PeakData.readerVersion)
        XCTAssertEqual(installed.dataSha256, gzipSha256)
        XCTAssertEqual(installed.manifestSha256, sha256(manifest()))
    }

    func testBrokenDataKeepsPreviousGz() async {
        let previous = ArchivedPeakData(manifest: Data([9]), data: Data([1, 2, 3]))
        let archive = MemoryArchive(previous)
        let source = FakeSource(manifest: manifest(), data: gzip.dropLast())
        _ = try? await PeakDataUpdater(source: source, cache: MemoryCache(), archive: archive).update(installed: nil)
        XCTAssertEqual(archive.archived, previous)
    }

    /// 古い読み込み処理で manifest と gz を取り込んだときの記録。
    private func oldInstalled(sha256 dataSha: String?? = .none, manifestSha256: String?? = .none) -> InstalledPeakData {
        InstalledPeakData(version: "v1", sourceTimestamp: "", mountainCount: 1, manifestEtag: "\"e1\"",
                          installedAt: Date(timeIntervalSince1970: 0), readerVersion: PeakData.readerVersion - 1,
                          dataSha256: dataSha ?? gzipSha256, manifestSha256: manifestSha256 ?? sha256(manifest()))
    }

    func testRebuildsFromKeptGzWithoutNetwork() async throws {
        let source = FakeSource(manifest: manifest(), data: gzip)
        let cache = MemoryCache()
        let now = Date(timeIntervalSince1970: 2_000)
        let updater = PeakDataUpdater(source: source, cache: cache, archive: kept(), clock: { now })

        let rebuilt = try await updater.rebuild(installed: oldInstalled())
        var expected = oldInstalled()
        expected.mountainCount = 2
        expected.readerVersion = PeakData.readerVersion
        XCTAssertEqual(rebuilt, expected)
        XCTAssertEqual(Set(cache.mountains.keys), [3_403_990_450, 12])
        XCTAssertEqual(cache.tiles[Tile.of(40.0, 141.0)], now)
        XCTAssertTrue(source.sentEtags.isEmpty && source.dataCalls == 0, "通信しない")

        // 作り直したあとは、同じ版なら取り直さない。
        let again = try await updater.rebuild(installed: rebuilt)
        XCTAssertNil(again)
        guard case .upToDate = try await updater.update(installed: rebuilt) else { return XCTFail("最新のはず") }
        XCTAssertEqual(source.dataCalls, 0)
    }

    func testRefetchesSameVersionWhenKeptGzIsMissing() async throws {
        // gz か manifest が無い、SHA-256 が合わない、組が食い違う(gz が manifest の SHA-256 と合わない)。
        let other = Data([1, 2, 3])
        let zero = String(repeating: "0", count: 64)
        let cases: [(MemoryArchive, InstalledPeakData)] = [
            (MemoryArchive(), oldInstalled()),
            (kept(), oldInstalled(sha256: zero)),
            (kept(), oldInstalled(sha256: .some(nil))),
            (kept(), oldInstalled(manifestSha256: zero)),
            (kept(), oldInstalled(manifestSha256: .some(nil))),
            (MemoryArchive(ArchivedPeakData(manifest: manifest(), data: other)), oldInstalled(sha256: sha256(other))),
        ]
        for (archive, installed) in cases {
            let source = FakeSource(manifest: manifest(), data: gzip)
            let cache = MemoryCache()
            let updater = PeakDataUpdater(source: source, cache: cache, archive: archive)
            let rebuilt = try await updater.rebuild(installed: installed)
            XCTAssertNil(rebuilt)
            XCTAssertTrue(cache.mountains.isEmpty)
            // 作り直せなかったら、データの版が同じでも ETag を送らずに取り直す。
            guard case .updated(let result) = try await updater.update(installed: installed) else { return XCTFail("取り直すはず") }
            XCTAssertEqual(source.sentEtags, [nil])
            XCTAssertEqual(source.dataCalls, 1)
            XCTAssertEqual(result.readerVersion, PeakData.readerVersion)
        }
    }

    func testFileArchiveReplacesAndDeletesGz() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let folder = dir.appendingPathComponent("data", isDirectory: true)
        let archive = FilePeakDataArchive(directory: folder)
        let empty = await archive.read()
        XCTAssertNil(empty)
        await archive.write(manifest: Data([1]), data: Data([1, 2]))
        await archive.write(manifest: manifest(), data: gzip)
        let kept = await archive.read()
        XCTAssertEqual(kept, ArchivedPeakData(manifest: manifest(), data: gzip), "前の組は置き換える")
        let files = try FileManager.default.contentsOfDirectory(atPath: folder.path)
        XCTAssertEqual(Set(files), [FilePeakDataArchive.manifestName, FilePeakDataArchive.dataName], "一時ファイルを残さない")
        // manifest だけなら gz が無くても読める。
        try FileManager.default.removeItem(at: folder.appendingPathComponent(FilePeakDataArchive.dataName))
        let manifestOnly = await archive.readManifest()
        XCTAssertEqual(manifestOnly, manifest())
        // 組は片方だけでは読まない。
        let noGz = await archive.read()
        XCTAssertNil(noGz)
        try FileManager.default.removeItem(at: folder.appendingPathComponent(FilePeakDataArchive.manifestName))
        let noManifest = await archive.readManifest()
        XCTAssertNil(noManifest)
        await archive.write(manifest: manifest(), data: gzip)
        try FileManager.default.removeItem(at: folder.appendingPathComponent(FilePeakDataArchive.manifestName))
        let half = await archive.read()
        XCTAssertNil(half)
        await archive.delete()
        let deleted = await archive.read()
        XCTAssertNil(deleted)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: folder.path), [])
    }

    func testDecodesInstalledRecordWithoutReaderVersion() throws {
        // 読み込み処理の版を記録していない、古い版のアプリが保存した記録。
        let old = InstalledPeakData(version: "v1", sourceTimestamp: "", mountainCount: 1, manifestEtag: nil,
                                    installedAt: Date(timeIntervalSince1970: 0))
        var json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(old)) as! [String: Any]
        json.removeValue(forKey: "readerVersion")
        json.removeValue(forKey: "dataSha256")
        json.removeValue(forKey: "manifestSha256")
        let decoded = try JSONDecoder().decode(InstalledPeakData.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(decoded, old)
        XCTAssertEqual(decoded.readerVersion, 0)
    }
}

/// 確認の結果と、取り込んだかどうか。テストで `update` の結果を見分けるのに使う。
private enum UpdateResult: Equatable {
    case upToDate(InstalledPeakData)
    case updated(InstalledPeakData)

    var installed: InstalledPeakData {
        switch self {
        case .upToDate(let installed), .updated(let installed): return installed
        }
    }
}

private extension PeakDataUpdater {
    /// 確認して、取り込める版があれば続けて取り込む(アプリでは、利用者に大きさを見せて確かめてから取り込む)。
    func update(installed: InstalledPeakData?) async throws -> UpdateResult {
        switch try await check(installed: installed) {
        case .upToDate(let installed): return .upToDate(installed)
        case .available(let offer): return .updated(try await install(offer))
        }
    }
}
