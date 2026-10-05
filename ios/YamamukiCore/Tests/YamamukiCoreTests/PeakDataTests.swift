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

    func testManifestUrlFollowsBuildType() {
        XCTAssertEqual(PeakData.manifestUrl(dev: true).absoluteString, "https://shohei0205.github.io/yamamuki-data/peaks-dev/manifest.json")
        XCTAssertEqual(PeakData.manifestUrl(dev: false).absoluteString, "https://shohei0205.github.io/yamamuki-data/peaks/manifest.json")
    }

    func testRejectsUnknownSchemaVersion() {
        XCTAssertThrowsError(try PeakData.parseManifest(manifest(schemaVersion: 5))) { error in
            XCTAssertTrue((error as? PeakDataError)?.message.contains("アプリを更新") == true)
        }
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
        // 北緯 20〜46°・東経 122〜154° の矩形。山のない海のタイルも含む(Tile.covering は北端・東端のタイルも含む)。
        XCTAssertEqual(tiles.count, 53 * 65)
        XCTAssertTrue(tiles.contains(Tile.of(40.0, 141.0)))
        XCTAssertTrue(tiles.contains(Tile.of(20.1, 153.9)))
        // 範囲の外にある山も、その山のタイルまで広げて取り込む。
        let outside = Mountain(osmId: 1, name: "外", latitude: 50.2, longitude: 160.3, elevationM: nil)
        XCTAssertTrue(PeakData.tiles(of: [outside]).contains(Tile.of(50.2, 160.3)))
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
