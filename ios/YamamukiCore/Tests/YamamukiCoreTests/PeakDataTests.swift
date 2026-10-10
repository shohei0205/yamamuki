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

    func testRejectsUnknownSchemaVersion() {
        XCTAssertThrowsError(try PeakData.parseManifest(manifest(schemaVersion: 6))) { error in
            XCTAssertTrue((error as? PeakDataError)?.message.contains("アプリを更新") == true)
        }
        XCTAssertThrowsError(try PeakData.parseManifest(manifest(schemaVersion: 3)))
    }

    func testRejectsUnknownDataSchemaVersion() {
        XCTAssertThrowsError(try PeakData.parseManifest(manifestV5(dataSchemaVersion: "6"))) { error in
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
            Mountain(osmId: 1, name: "山頂", latitude: 35.0, longitude: 138.0, elevationM: 100.0),
            Mountain(osmId: 2, name: "種別不明", latitude: 35.1, longitude: 138.1, elevationM: nil),
        ])
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
            Mountain(osmId: 3_403_990_450, name: "万三郎岳", latitude: 34.8627963, longitude: 139.0018525, elevationM: 1405.6),
            Mountain(osmId: 12, name: "名無し標高", latitude: 43.5, longitude: 142.9, elevationM: nil),
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
}
