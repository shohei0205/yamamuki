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

final class PeakDataPointerTests: XCTestCase {
    private let stableUrl = PeakData.pointerManifestUrlPrefix + "osm-peaks-20261007T071401Z-37585754546-1.json"
    private let devUrl = PeakData.pointerManifestUrlPrefix + "osm-peaks-dev-20261010T022253Z-38016527834-1.json"

    private func entry(_ url: String, _ sha256: String) -> String { #"{"manifestUrl":"\#(url)","manifestSha256":"\#(sha256)"}"# }

    private func pointer(stable: String?, dev: String? = nil, schemaVersion: Int = 1) -> Data {
        let fields = [#""schemaVersion":\#(schemaVersion)"#, stable.map { #""stable":\#($0)"# }, dev.map { #""dev":\#($0)"# }]
        return Data(("{" + fields.compactMap { $0 }.joined(separator: ",") + "}").utf8)
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

    func testPicksStableOrDev() throws {
        let withDev = pointer(stable: entry(stableUrl, a64), dev: entry(devUrl, b64))
        XCTAssertEqual(try PeakData.parsePointer(withDev, useDev: false), PeakDataPointer(manifestUrl: URL(string: stableUrl)!, manifestSha256: a64))
        XCTAssertEqual(try PeakData.parsePointer(withDev, useDev: true), PeakDataPointer(manifestUrl: URL(string: devUrl)!, manifestSha256: b64))
        // dev が無ければ、開発用のビルドも stable を読む。
        XCTAssertEqual(try PeakData.parsePointer(pointer(stable: entry(stableUrl, a64)), useDev: true).manifestUrl.absoluteString, stableUrl)
    }

    func testRejectsUnexpectedContent() {
        let bad = [
            pointer(stable: entry(stableUrl, a64), schemaVersion: 2),
            pointer(stable: nil),
            pointer(stable: nil, dev: entry(devUrl, b64)),
            pointer(stable: entry("https://shohei0205.github.io/yamamuki-data/peaks/manifest.json", a64)),
            pointer(stable: entry(PeakData.pointerManifestUrlPrefix + "sub/x.json", a64)),
            pointer(stable: entry(PeakData.pointerManifestUrlPrefix + "x.txt", a64)),
            pointer(stable: entry(stableUrl, String(repeating: "A", count: 64))),
            pointer(stable: entry(stableUrl, String(repeating: "a", count: 63))),
            Data("[]".utf8),
            Data("{".utf8),
        ]
        for body in bad {
            XCTAssertThrowsError(try PeakData.parsePointer(body, useDev: false), String(decoding: body, as: UTF8.self))
        }
        // 開発用のビルドは、dev が壊れていても stable に切り替えない。
        XCTAssertThrowsError(try PeakData.parsePointer(pointer(stable: entry(stableUrl, a64), dev: entry(devUrl, "x")), useDev: true))
    }

    func testSourceFollowsPointerAndSkipsSameManifest() async throws {
        let manifest = Data(#"{"schemaVersion":5}"#.utf8)
        let sha256 = PeakData.sha256Hex(manifest)
        StubProtocol.responses = [
            PeakData.pointerUrl: (200, pointer(stable: entry(stableUrl, sha256))),
            URL(string: stableUrl)!: (200, manifest),
        ]
        let source = PeakData.source(.stable, session: stubSession(), userAgent: "test")

        guard case .fetched(let body, let etag) = try await source.fetchManifest(etag: nil) else {
            return XCTFail("manifest が返ってきませんでした")
        }
        XCTAssertEqual(body, manifest)
        XCTAssertEqual(etag, sha256)
        // 指す manifest が前回と同じなら、manifest は取り直さない。
        guard case .notModified = try await source.fetchManifest(etag: sha256) else { return XCTFail("取り直しました") }
        XCTAssertEqual(StubProtocol.requests, [PeakData.pointerUrl, URL(string: stableUrl)!, PeakData.pointerUrl])
    }

    func testSourceRejectsManifestWithWrongSha256() async {
        StubProtocol.responses = [
            PeakData.pointerUrl: (200, pointer(stable: entry(stableUrl, a64))),
            URL(string: stableUrl)!: (200, Data("{}".utf8)),
        ]
        let source = PointerPeakDataSource(session: stubSession(), useDev: false)
        do {
            _ = try await source.fetchManifest(etag: nil)
            XCTFail("SHA-256 の違う manifest を受け取りました")
        } catch {}
    }

    func testSourceReportsHttpError() async {
        let source = PointerPeakDataSource(session: stubSession(), useDev: true)
        do {
            _ = try await source.fetchManifest(etag: nil)
            XCTFail("HTTP 404 を受け取りました")
        } catch {
            XCTAssertTrue("\(error)".contains("404"))
        }
    }

    /// yamamuki の Pages に置いた参照先ファイル(site/data/osm-peaks/current.json)が指す manifest を、このアプリで読めるか。
    /// Android の SitePeakDataTest と同じ確認。
    func testSitePointerIsReadableByThisApp() throws {
        let site = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("site/data/osm-peaks")
        let body = try Data(contentsOf: site.appendingPathComponent("current.json"))
        for useDev in [false, true] {
            let pointer = try PeakData.parsePointer(body, useDev: useDev)
            let manifest = try Data(contentsOf: site.appendingPathComponent("manifests").appendingPathComponent(pointer.manifestUrl.lastPathComponent))
            XCTAssertEqual(PeakData.sha256Hex(manifest), pointer.manifestSha256)
            XCTAssertGreaterThan(try PeakData.parseManifest(manifest).mountainCount, 0)
        }
    }
}
