import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import YamamukiCore

/// 決まった URL に決まった応答を返す、テスト用の通信。
private final class StubProtocol: URLProtocol {
    static var responses: [URL: (status: Int, body: Data)] = [:]
    static var requests: [URL] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        Self.requests.append(url)
        let (status, body) = Self.responses[url] ?? (404, Data())
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class PeakDataCatalogTests: XCTestCase {
    private let stableUrl = PeakData.catalogManifestUrlPrefix + "osm-peaks-20261007T071401Z-37585754546-1.json"
    private let devUrl = PeakData.catalogManifestUrlPrefix + "osm-peaks-dev-20261010T022253Z-38016527834-1.json"
    private let gsiUrl = "https://shohei0205.github.io/yamamuki/data/gsi-peaks/manifests/gsi-peaks-dev-20261018T115950Z-39125416076-1.json"

    private func entry(_ url: String, _ sha256: String) -> String { #"{"manifestUrl":"\#(url)","manifestSha256":"\#(sha256)"}"# }

    private func channels(stable: String?, dev: String? = nil) -> String {
        "{" + [stable.map { #""stable":\#($0)"# }, dev.map { #""dev":\#($0)"# }].compactMap { $0 }.joined(separator: ",") + "}"
    }

    /// カタログ。osm-peaks のほかに、アプリが読まない gsi-peaks も入れておく。
    private func catalog(stable: String?, dev: String? = nil, schemaVersion: Int = 1, gsi: String? = "default") -> Data {
        let gsiChannels = gsi == "default" ? channels(stable: nil, dev: entry(gsiUrl, String(repeating: "c", count: 64))) : gsi
        let datasets = [#""osm-peaks":\#(channels(stable: stable, dev: dev))"#, gsiChannels.map { #""gsi-peaks":\#($0)"# }]
            .compactMap { $0 }.joined(separator: ",")
        return Data(#"{"schemaVersion":\#(schemaVersion),"datasets":{\#(datasets)}}"#.utf8)
    }

    private var a64: String { String(repeating: "a", count: 64) }
    private var b64: String { String(repeating: "b", count: 64) }

    override func tearDown() {
        StubProtocol.responses = [:]
        StubProtocol.requests = []
        super.tearDown()
    }

    private func stubSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: configuration)
    }

    func testPicksStableOrDevOfOsmPeaks() throws {
        let withDev = catalog(stable: entry(stableUrl, a64), dev: entry(devUrl, b64))
        XCTAssertEqual(try PeakData.parseCatalog(withDev, useDev: false), PeakDataPointer(manifestUrl: URL(string: stableUrl)!, manifestSha256: a64))
        XCTAssertEqual(try PeakData.parseCatalog(withDev, useDev: true), PeakDataPointer(manifestUrl: URL(string: devUrl)!, manifestSha256: b64))
        // dev が無ければ、開発用のビルドも stable を読む。gsi-peaks の dev があっても読まない。
        XCTAssertEqual(try PeakData.parseCatalog(catalog(stable: entry(stableUrl, a64)), useDev: true).manifestUrl.absoluteString, stableUrl)
        // 知らない種類のデータがあっても、osm-peaks だけを読む。
        let other = catalog(stable: entry(stableUrl, a64), gsi: #"{"stable":{"manifestUrl":"x"},"beta":1}"#)
        XCTAssertEqual(try PeakData.parseCatalog(other, useDev: false).manifestUrl.absoluteString, stableUrl)
    }

    func testRejectsUnexpectedContent() {
        let bad = [
            catalog(stable: entry(stableUrl, a64), schemaVersion: 2),
            catalog(stable: nil),
            catalog(stable: nil, dev: entry(devUrl, b64)),
            catalog(stable: entry("https://shohei0205.github.io/yamamuki-data/peaks/manifest.json", a64)),
            // gsi-peaks の置き場所の manifest を osm-peaks として読まない。
            catalog(stable: entry(gsiUrl, a64)),
            catalog(stable: entry(PeakData.catalogManifestUrlPrefix + "sub/x.json", a64)),
            catalog(stable: entry(PeakData.catalogManifestUrlPrefix + "x.txt", a64)),
            catalog(stable: entry(stableUrl, String(repeating: "A", count: 64))),
            catalog(stable: entry(stableUrl, String(repeating: "a", count: 63))),
            Data(#"{"schemaVersion":1,"datasets":{"gsi-peaks":\#(channels(stable: entry(gsiUrl, a64)))}}"#.utf8),
            Data(#"{"schemaVersion":1,"stable":\#(entry(stableUrl, a64))}"#.utf8),
            Data("[]".utf8),
            Data("{".utf8),
        ]
        for body in bad {
            XCTAssertThrowsError(try PeakData.parseCatalog(body, useDev: false), String(decoding: body, as: UTF8.self))
        }
        // 開発用のビルドは、dev が壊れていても stable に切り替えない。
        XCTAssertThrowsError(try PeakData.parseCatalog(catalog(stable: entry(stableUrl, a64), dev: entry(devUrl, "x")), useDev: true))
        XCTAssertThrowsError(try PeakData.parseCatalog(catalog(stable: entry(stableUrl, a64), schemaVersion: 2), useDev: false)) { error in
            XCTAssertTrue((error as? PeakDataError)?.message.contains("アプリを更新") == true)
        }
    }

    func testSourceFollowsCatalogAndSkipsSameManifest() async throws {
        let manifest = Data(#"{"schemaVersion":5}"#.utf8)
        let sha256 = PeakData.sha256Hex(manifest)
        StubProtocol.responses = [
            PeakData.catalogUrl: (200, catalog(stable: entry(stableUrl, sha256))),
            URL(string: stableUrl)!: (200, manifest),
        ]
        let source = PeakData.source(.stable, session: stubSession(), userAgent: "test")

        guard case .fetched(let body, let etag) = try await source.fetchManifest(etag: nil) else {
            return XCTFail("manifest が返ってきませんでした")
        }
        XCTAssertEqual(body, manifest)
        XCTAssertEqual(etag, sha256)
        // 指す manifest が前回と同じなら、manifest は取り直さない。0.7.0 の current.json で取り込んだ端末も、
        // 同じ manifest の SHA-256 を覚えているので取り直さない。
        guard case .notModified = try await source.fetchManifest(etag: sha256) else { return XCTFail("取り直しました") }
        XCTAssertEqual(StubProtocol.requests, [PeakData.catalogUrl, URL(string: stableUrl)!, PeakData.catalogUrl])
    }

    func testSourceRejectsManifestWithWrongSha256() async {
        StubProtocol.responses = [
            PeakData.catalogUrl: (200, catalog(stable: entry(stableUrl, a64))),
            URL(string: stableUrl)!: (200, Data("{}".utf8)),
        ]
        let source = CatalogPeakDataSource(session: stubSession(), useDev: false)
        do {
            _ = try await source.fetchManifest(etag: nil)
            XCTFail("SHA-256 の違う manifest を受け取りました")
        } catch {}
    }

    func testSourceReportsHttpError() async {
        let source = CatalogPeakDataSource(session: stubSession(), useDev: true)
        do {
            _ = try await source.fetchManifest(etag: nil)
            XCTFail("HTTP 404 を受け取りました")
        } catch {
            XCTAssertTrue("\(error)".contains("404"))
        }
    }

    /// yamamuki の Pages に置いたカタログ(site/data/catalog.json)の osm-peaks が指す manifest を、このアプリで読めるか。
    /// Android の SitePeakDataTest と同じ確認。
    func testSiteCatalogIsReadableByThisApp() throws {
        let site = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("site/data")
        let body = try Data(contentsOf: site.appendingPathComponent("catalog.json"))
        for useDev in [false, true] {
            let pointer = try PeakData.parseCatalog(body, useDev: useDev)
            let manifest = try Data(contentsOf: site.appendingPathComponent(PeakData.dataset).appendingPathComponent("manifests")
                .appendingPathComponent(pointer.manifestUrl.lastPathComponent))
            XCTAssertEqual(PeakData.sha256Hex(manifest), pointer.manifestSha256)
            XCTAssertGreaterThan(try PeakData.parseManifest(manifest).mountainCount, 0)
        }
    }
}
