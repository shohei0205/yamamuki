// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import YamamukiCore

/// 決まった URL に決まった応答を返す、テスト用の通信。
private final class SurfaceStubProtocol: URLProtocol {
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

/// 呼ばれた値を覚える、テスト用の入れ物。
private final class Recorder<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [Value] = []
    func add(_ value: Value) { lock.lock(); stored.append(value); lock.unlock() }
    var values: [Value] { lock.lock(); defer { lock.unlock() }; return stored }
}

/// 決まった manifest とデータ本体を返す取得元。
private final class FakeSurfaceSource: SurfaceDataSource, @unchecked Sendable {
    var manifest: Data?
    var body: Data
    var downloads = 0

    init(manifest: Data?, body: Data) {
        self.manifest = manifest
        self.body = body
    }

    func fetchManifest(etag: String?) async throws -> ManifestResponse? {
        guard let manifest else { return nil }
        let current = PeakData.sha256Hex(manifest)
        return etag == current ? .notModified : .fetched(body: manifest, etag: current)
    }

    func download(_ url: URL, to: URL, maxBytes: Int, onProgress: @escaping @Sendable (Int) -> Void) async throws {
        downloads += 1
        try body.write(to: to)
        onProgress(body.count / 2)
        onProgress(body.count)
    }
}

final class SurfaceDataUpdaterTests: XCTestCase {
    private let dataset = SurfaceDataset.esaWorldcoverZ9
    private var directory: URL!
    private var files: SurfaceDataFiles!

    /// データ本体の代わりの小さなバイト列(SQLite としては読めない)。
    private let data = Data((0..<200_000).map { UInt8($0 % 251) })
    private let dataUrl = URL(string: "https://github.com/shohei0205/yamamuki-data/releases/download/esa-worldcover-1/esa-worldcover-z9.sqlite3")!
    private var manifestUrl: URL { URL(string: dataset.manifestUrlPrefix + "esa-worldcover-1.json")! }

    override func setUp() {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("surface-data-\(UUID().uuidString)")
        files = SurfaceDataFiles(directory: directory, dataset: dataset)
        SurfaceStubProtocol.responses = [:]
        SurfaceStubProtocol.requests = []
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
    }

    private func manifest(version: String = "20261112T164326Z-43261736372-1", bytes: Data? = nil) -> Data {
        let bytes = bytes ?? data
        return Data("""
        {"dataSchemaFormat":"yamamuki-sqlite","dataSchemaVersion":1,
         "datasets":[{"formatVersion":1,"id":"esa-worldcover-japan","kind":"surface"}],
         "downloadUrl":"\(dataUrl.absoluteString)","fileName":"esa-worldcover-z9.sqlite3",
         "schemaFormat":"yamamuki-manifest","schemaVersion":6,
         "sha256":"\(PeakData.sha256Hex(bytes))","sizeBytes":\(bytes.count),"version":"\(version)"}
        """.utf8)
    }

    private func catalog(_ entries: String...) -> Data {
        Data(#"{"schemaVersion":1,"datasets":{"osm-peaks":{"stable":{"manifestUrl":"x","manifestSha256":"y"}}\#(entries.map { ",\($0)" }.joined())}}"#.utf8)
    }

    private func entry(_ manifest: Data, channel: String = "stable", url: String? = nil) -> String {
        #""\#(dataset.id)":{"\#(channel)":{"manifestUrl":"\#(url ?? manifestUrl.absoluteString)","manifestSha256":"\#(PeakData.sha256Hex(manifest))"}}"#
    }

    private func updater(_ source: SurfaceDataSource,
                         validate: @escaping @Sendable (URL, SurfaceManifest) async throws -> Void = { _, _ in }) -> SurfaceDataUpdater {
        SurfaceDataUpdater(source: source, files: files, dataset: dataset, validate: validate, clock: { Date(timeIntervalSince1970: 1) })
    }

    private func offer(_ updater: SurfaceDataUpdater, installed: InstalledSurfaceData? = nil) async throws -> SurfaceDataOffer {
        guard case let .available(offer) = try await updater.check(installed: installed) else {
            throw SurfaceDataError("取り込める版がありません")
        }
        return offer
    }

    private func assertThrowsSurfaceError(_ body: () async throws -> Void, file: StaticString = #filePath, line: UInt = #line) async {
        do {
            try await body()
            XCTFail("エラーになりませんでした", file: file, line: line)
        } catch {
            XCTAssertTrue(error is SurfaceDataError, "\(error)", file: file, line: line)
        }
    }

    func testInstallsTheDataAndKeepsTheManifest() async throws {
        let source = FakeSurfaceSource(manifest: manifest(), body: data)
        let updater = updater(source)
        let offer = try await offer(updater)
        XCTAssertEqual(offer.manifest.sizeBytes, data.count)
        XCTAssertEqual(source.downloads, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: files.database.path))

        let progress = Recorder<Double>()
        let installed = try await updater.install(offer) { progress.add($0) }
        XCTAssertEqual(installed.version, "20261112T164326Z-43261736372-1")
        XCTAssertEqual(installed.releaseDate, "2026/11/12")
        XCTAssertEqual(installed.sizeBytes, data.count)
        XCTAssertEqual(installed.datasetId, "esa-worldcover-japan")
        XCTAssertEqual(installed.installedAt, Date(timeIntervalSince1970: 1))
        XCTAssertEqual(installed.manifestSha256, PeakData.sha256Hex(manifest()))
        XCTAssertEqual(try Data(contentsOf: files.database), data)
        XCTAssertEqual(files.readManifest(), manifest())
        XCTAssertFalse(FileManager.default.fileExists(atPath: files.partial.path))
        XCTAssertEqual(progress.values, [0.5, 1])

        // 同じ版なら取り直さない。
        guard case let .upToDate(same) = try await updater.check(installed: installed) else { return XCTFail("最新のはず") }
        XCTAssertEqual(same, installed)
        // 新しい版が出たら、取り込める版として返す。
        source.manifest = manifest(version: "20261201T000000Z-1-1")
        let next = try await self.offer(updater, installed: installed)
        XCTAssertEqual(next.manifest.version, "20261201T000000Z-1-1")
    }

    func testSameVersionWithAnotherManifestIsUpToDate() async throws {
        let source = FakeSurfaceSource(manifest: manifest(), body: data)
        let updater = updater(source)
        let installed = try await updater.install(try await offer(updater))
        // manifest のコピーだけが変わった(版は同じ)ときは、目印だけを新しくする。
        source.manifest = manifest() + Data("\n".utf8)
        guard case let .upToDate(checked) = try await updater.check(installed: installed) else { return XCTFail("最新のはず") }
        XCTAssertEqual(checked.manifestEtag, PeakData.sha256Hex(source.manifest!))
        XCTAssertEqual(checked.version, installed.version)
    }

    func testMissingInCatalogIsUnavailable() async throws {
        guard case .unavailable = try await updater(FakeSurfaceSource(manifest: nil, body: data)).check(installed: nil) else {
            return XCTFail("カタログに無いはず")
        }
    }

    func testBrokenDataKeepsThePreviousVersion() async throws {
        let source = FakeSurfaceSource(manifest: manifest(), body: data)
        let updater = updater(source)
        _ = try await updater.install(try await offer(updater))

        // SHA-256 が合わない。
        source.manifest = manifest(version: "20261201T000000Z-1-1")
        let offer = try await offer(updater)
        var broken = data
        broken[0] = 1
        source.body = broken
        await assertThrowsSurfaceError { _ = try await updater.install(offer) }
        // サイズが合わない。
        source.body = data.prefix(100)
        await assertThrowsSurfaceError { _ = try await updater.install(offer) }
        // 山むきの SQLite として読めない。
        source.body = data
        let rejecting = self.updater(source) { _, _ in throw SurfaceDataError("読めません") }
        await assertThrowsSurfaceError { _ = try await rejecting.install(offer) }

        XCTAssertEqual(try Data(contentsOf: files.database), data)
        XCTAssertEqual(files.readManifest(), manifest())
        XCTAssertFalse(FileManager.default.fileExists(atPath: files.partial.path))
    }

    func testValidatorSeesTheDownloadedFileBeforeItIsPlaced() async throws {
        let seen = Recorder<String>()
        let database = files.database
        let updater = updater(FakeSurfaceSource(manifest: manifest(), body: data)) { file, manifest in
            seen.add("\(FileManager.default.fileExists(atPath: database.path)):\(file.lastPathComponent):\(manifest.datasetId)")
        }
        _ = try await updater.install(try await offer(updater))
        XCTAssertEqual(seen.values, ["false:esa-worldcover-z9.sqlite3.part:esa-worldcover-japan"])
    }

    func testDeleteRemovesTheFiles() async throws {
        let updater = updater(FakeSurfaceSource(manifest: manifest(), body: data))
        _ = try await updater.install(try await offer(updater))
        files.delete()
        XCTAssertFalse(FileManager.default.fileExists(atPath: files.database.path))
        XCTAssertNil(files.readManifest())
    }

    // MARK: - カタログからの取得

    private func source(catalog: Data, manifest: Data? = nil, useDev: Bool = false) -> CatalogSurfaceDataSource {
        SurfaceStubProtocol.responses = [
            PeakData.catalogUrl: (200, catalog),
            manifestUrl: (200, manifest ?? self.manifest()),
            dataUrl: (200, data),
        ]
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SurfaceStubProtocol.self]
        return CatalogSurfaceDataSource(session: URLSession(configuration: configuration), dataset: dataset, useDev: useDev)
    }

    func testCatalogSourceFollowsThePointer() async throws {
        let source = source(catalog: catalog(entry(manifest())))
        guard case let .fetched(body, etag)? = try await source.fetchManifest(etag: nil) else { return XCTFail("manifest が返るはず") }
        XCTAssertEqual(body, manifest())
        // 同じ manifest を指しているあいだは、manifest を取り直さない。
        guard case .notModified? = try await source.fetchManifest(etag: etag) else { return XCTFail("変わっていないはず") }
        XCTAssertEqual(SurfaceStubProtocol.requests, [PeakData.catalogUrl, manifestUrl, PeakData.catalogUrl])

        let to = directory.appendingPathComponent("download")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let progress = Recorder<Int>()
        try await source.download(dataUrl, to: to, maxBytes: data.count) { progress.add($0) }
        XCTAssertEqual(try Data(contentsOf: to), data)
        XCTAssertEqual(progress.values.last, data.count)
        XCTAssertEqual(progress.values, progress.values.sorted())
    }

    func testCatalogSourceReadsDevOnlyInDevBuilds() async throws {
        let devOnly = catalog(entry(manifest(), channel: "dev"))
        guard case .fetched? = try await source(catalog: devOnly, useDev: true).fetchManifest(etag: nil) else { return XCTFail("dev を読むはず") }
        // 配布用のビルドは stable だけを読む。
        let stable = source(catalog: devOnly, useDev: false)
        await assertThrowsSurfaceError { _ = try await stable.fetchManifest(etag: nil) }
    }

    func testCatalogSourceWithoutTheDatasetReturnsNil() async throws {
        let response = try await source(catalog: catalog(), useDev: true).fetchManifest(etag: nil)
        XCTAssertNil(response)
    }

    func testCatalogSourceRejectsBadPointersAndData() async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // よその URL を指している。
        let elsewhere = source(catalog: catalog(entry(manifest(), url: "https://example.com/esa-worldcover-1.json")))
        await assertThrowsSurfaceError { _ = try await elsewhere.fetchManifest(etag: nil) }
        // manifest の SHA-256 がカタログと合わない。
        let source = source(catalog: catalog(entry(manifest())), manifest: manifest() + Data(" ".utf8))
        await assertThrowsSurfaceError { _ = try await source.fetchManifest(etag: nil) }
        // manifest のサイズより大きいデータは途中で打ち切る。
        let to = directory.appendingPathComponent("download")
        await assertThrowsSurfaceError { try await source.download(self.dataUrl, to: to, maxBytes: 1_000) { _ in } }
        await assertThrowsSurfaceError { try await source.download(URL(string: "https://example.com/none")!, to: to, maxBytes: 1_000) { _ in } }
    }
}
