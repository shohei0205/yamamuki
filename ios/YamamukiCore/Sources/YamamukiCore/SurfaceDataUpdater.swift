// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(CryptoKit)
import CryptoKit
#endif

/// 取り込み済みの地表データ。選択画面に出し、次の確認で同じ版なら取り直さない。
public struct InstalledSurfaceData: Codable, Equatable, Sendable {
    public var version: String
    /// データ本体(SQLite)のバイト数。
    public var sizeBytes: Int
    /// SQLite の中の、地表のデータセットの ID。
    public var datasetId: String
    /// manifest を取ったときの目印(カタログに書かれた manifest の SHA-256)。変わっていなければ manifest を取り直さない。
    public var manifestEtag: String?
    public var installedAt: Date
    /// 取り込んだときの manifest の SHA-256。端末に残した manifest がこの版のものかを確かめる。
    public var manifestSha256: String
    public var experimental: Bool

    public init(version: String, sizeBytes: Int, datasetId: String, manifestEtag: String?, installedAt: Date,
                manifestSha256: String, experimental: Bool = false) {
        self.version = version
        self.sizeBytes = sizeBytes
        self.datasetId = datasetId
        self.manifestEtag = manifestEtag
        self.installedAt = installedAt
        self.manifestSha256 = manifestSha256
        self.experimental = experimental
    }

    /// データを作った日(UTC)。「2026/10/07」の形。`version` から読めなければ nil。
    public var releaseDate: String? { PeakData.releaseDate(version) }
}

/// 確認した manifest のうち、まだ取り込んでいない版。データ本体を取る前に、大きさを利用者に見せるのに使う。
/// `SurfaceDataUpdater.install` に渡すと、この manifest のとおりに取り込む。
public struct SurfaceDataOffer: Sendable {
    public let manifest: SurfaceManifest
    let manifestBody: Data
    let etag: String?
}

/// 地表データの取得元。テストではフェイクに差し替える。
public protocol SurfaceDataSource: Sendable {
    /// - Parameter etag: 前回の目印。nil なら必ず取得する。
    /// - Returns: カタログにこの種類が無いときは nil。
    func fetchManifest(etag: String?) async throws -> ManifestResponse?

    /// データ本体を `to` に書く。山データと違って大きい(17 MB 以上)ので、メモリに載せずにファイルへ流す。
    /// `maxBytes` を超えたら打ち切る。`onProgress` には受け取ったバイト数を渡す。
    func download(_ url: URL, to: URL, maxBytes: Int, onProgress: @escaping @Sendable (Int) -> Void) async throws
}

/// 端末に置く地表データのファイル。SQLite はそのまま置いて読む。取り込んだ manifest も残し、出典の表示に使う。
/// `directory` は自動バックアップに入らない場所にする。
public struct SurfaceDataFiles: Sendable {
    public static let manifestName = "manifest.json"

    public let directory: URL
    /// データ本体(SQLite)。
    public let database: URL
    /// 取り込んだときの manifest。
    public let manifest: URL
    /// ダウンロード中のファイル。確かめ終えてから `database` に置き換える。
    public let partial: URL

    public init(directory: URL, dataset: SurfaceDataset) {
        self.directory = directory
        database = directory.appendingPathComponent(dataset.fileName)
        manifest = directory.appendingPathComponent(Self.manifestName)
        partial = directory.appendingPathComponent(dataset.fileName + ".part")
    }

    /// 残した manifest。無いか読めなければ nil。
    public func readManifest() -> Data? { try? Data(contentsOf: manifest) }

    public func delete() {
        for url in [database, manifest, partial] { try? FileManager.default.removeItem(at: url) }
    }
}

/// 地表データの最新版を確かめ、新しければ取得・検証して端末に置く。
/// 確かめ終えるまでは別のファイルに書くので、途中で失敗しても前の版はそのまま残る。
public struct SurfaceDataUpdater: Sendable {
    /// 確認の結果。
    public enum Check: Sendable {
        /// 取り込み済みの版が最新だった。
        case upToDate(InstalledSurfaceData)
        /// 取り込める版がある。データ本体はまだ取っていない。
        case available(SurfaceDataOffer)
        /// カタログにこの種類が無い(まだ配っていない)。取り込み済みの版があれば、そのまま使える。
        case unavailable
    }

    private let source: SurfaceDataSource
    private let files: SurfaceDataFiles
    private let dataset: SurfaceDataset
    private let validate: @Sendable (URL, SurfaceManifest) async throws -> Void
    private let clock: @Sendable () -> Date

    /// - Parameter validate: 受け取った SQLite が、manifest に書かれた地表のデータセットを収録した山むきの SQLite コンテナかを確かめる。
    ///   SQLite を開くのはアプリ側なので、ここでは呼ぶだけにする。読めなければ `SurfaceDataError` を投げる。
    public init(source: SurfaceDataSource, files: SurfaceDataFiles, dataset: SurfaceDataset,
                validate: @escaping @Sendable (URL, SurfaceManifest) async throws -> Void = { _, _ in },
                clock: @escaping @Sendable () -> Date = { Date() }) {
        self.source = source
        self.files = files
        self.dataset = dataset
        self.validate = validate
        self.clock = clock
    }

    /// manifest だけを取って、取り込める版があるかを確かめる。データ本体は取りに行かないので、
    /// 大きさを利用者に見せてから `install` で取り込める。
    /// - Parameter installed: 取り込み済みの版。nil なら manifest の版によらず取り込める版として返す。
    public func check(installed: InstalledSurfaceData?) async throws -> Check {
        guard let response = try await source.fetchManifest(etag: installed?.manifestEtag) else { return .unavailable }
        guard case let .fetched(body, etag) = response else {
            guard let installed else { throw SurfaceDataError("manifest が返ってきませんでした") }
            return .upToDate(installed)
        }
        let manifest = try SurfaceData.parseManifest(body, dataset: dataset)
        if var installed, installed.version == manifest.version {
            installed.manifestEtag = etag
            return .upToDate(installed)
        }
        return .available(SurfaceDataOffer(manifest: manifest, manifestBody: body, etag: etag))
    }

    /// `check` で見つけた版のデータ本体を取得・検証して端末に置く。
    /// - Parameter onProgress: 進み具合(0〜1)。
    public func install(_ offer: SurfaceDataOffer, onProgress: @escaping @Sendable (Double) -> Void = { _ in }) async throws -> InstalledSurfaceData {
        let manifest = offer.manifest
        let fileManager = FileManager.default
        defer { try? fileManager.removeItem(at: files.partial) }
        do {
            try fileManager.createDirectory(at: files.directory, withIntermediateDirectories: true)
            try? fileManager.removeItem(at: files.partial)
            let size = manifest.sizeBytes
            try await source.download(manifest.downloadUrl, to: files.partial, maxBytes: size) { received in
                onProgress(min(max(Double(received) / Double(size), 0), 1))
            }
            try SurfaceData.verify(files.partial, against: manifest)
            try await validate(files.partial, manifest)
            if fileManager.fileExists(atPath: files.database.path) {
                _ = try fileManager.replaceItemAt(files.database, withItemAt: files.partial)
            } else {
                try fileManager.moveItem(at: files.partial, to: files.database)
            }
            try offer.manifestBody.write(to: files.manifest, options: .atomic)
        } catch let error as SurfaceDataError {
            throw error
        } catch let error as CocoaError {
            throw SurfaceDataError("地表データを保存できませんでした(\(error.localizedDescription))")
        }
        return InstalledSurfaceData(
            version: manifest.version,
            sizeBytes: manifest.sizeBytes,
            datasetId: manifest.datasetId,
            manifestEtag: offer.etag,
            installedAt: clock(),
            manifestSha256: PeakData.sha256Hex(offer.manifestBody),
            experimental: manifest.experimental
        )
    }
}

/// yamamuki のカタログ(catalog.json)から `dataset` の manifest をたどって、地表データを取得する。
/// 山データ(`CatalogPeakDataSource`)と同じカタログを読み、指す manifest の SHA-256 が前回と同じなら manifest は取り直さない。
public struct CatalogSurfaceDataSource: SurfaceDataSource {
    private let session: URLSession
    private let dataset: SurfaceDataset
    private let useDev: Bool
    private let catalogUrl: URL
    private let userAgent: String
    /// 山データの取得元と、取ったカタログを使い回す。nil なら毎回取る。
    private let catalogCache: PeakCatalogCache?

    public init(session: URLSession = .shared, dataset: SurfaceDataset, useDev: Bool, catalogUrl: URL = PeakData.catalogUrl,
                userAgent: String = "yamamuki-ios", catalogCache: PeakCatalogCache? = nil) {
        self.session = session
        self.dataset = dataset
        self.useDev = useDev
        self.catalogUrl = catalogUrl
        self.userAgent = userAgent
        self.catalogCache = catalogCache
    }

    public func fetchManifest(etag: String?) async throws -> ManifestResponse? {
        do {
            let body: Data
            if let catalogCache {
                body = try await catalogCache.get(catalogUrl) { try await get(catalogUrl) }
            } else {
                body = try await get(catalogUrl)
            }
            guard let pointer = try PeakData.catalogPointer(body, id: dataset.id, manifestUrlPrefix: dataset.manifestUrlPrefix, useDev: useDev) else {
                return nil
            }
            if pointer.manifestSha256 == etag { return .notModified }
            let manifest = try await get(pointer.manifestUrl)
            guard PeakData.sha256Hex(manifest) == pointer.manifestSha256 else {
                throw SurfaceDataError("地表データの manifest の SHA-256 がカタログと合いません")
            }
            return .fetched(body: manifest, etag: pointer.manifestSha256)
        } catch let error as PeakDataError {
            throw SurfaceDataError(error.message)
        }
    }

    public func download(_ url: URL, to: URL, maxBytes: Int, onProgress: @escaping @Sendable (Int) -> Void) async throws {
        let (bytes, response) = try await session.bytes(for: request(url))
        do {
            try PeakData.requireSuccess(response as? HTTPURLResponse, url)
        } catch let error as PeakDataError {
            throw SurfaceDataError(error.message)
        }
        FileManager.default.createFile(atPath: to.path, contents: nil)
        let handle = try FileHandle(forWritingTo: to)
        defer { try? handle.close() }
        var buffer = Data()
        buffer.reserveCapacity(Self.bufferSize)
        var received = 0
        for try await byte in bytes {
            buffer.append(byte)
            received += 1
            if received > maxBytes { throw SurfaceDataError("地表データが manifest のサイズ(\(maxBytes) バイト)より大きいです") }
            if buffer.count >= Self.bufferSize {
                try handle.write(contentsOf: buffer)
                buffer.removeAll(keepingCapacity: true)
                onProgress(received)
            }
        }
        if !buffer.isEmpty {
            try handle.write(contentsOf: buffer)
            onProgress(received)
        }
    }

    private static let bufferSize = 64 * 1024

    private func request(_ url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        // 差し替えたばかりのカタログを古いまま読まないよう、端末の HTTP キャッシュは使わない。
        request.cachePolicy = .reloadIgnoringLocalCacheData
        return request
    }

    private func get(_ url: URL) async throws -> Data {
        let (data, response) = try await session.data(for: request(url))
        try PeakData.requireSuccess(response as? HTTPURLResponse, url)
        return data
    }
}

extension SurfaceDataset {
    /// カタログが指してよい manifest の置き場所。Release の manifest を書き換えずにコピーしたもの。
    public var manifestUrlPrefix: String { "\(PeakData.siteDataUrl)\(id)/manifests/" }
}

extension SurfaceData {
    /// 受け取ったデータ本体が、manifest のサイズと SHA-256 に合うか確かめる。ファイルを少しずつ読み、メモリに載せない。
    public static func verify(_ file: URL, against manifest: SurfaceManifest) throws {
        let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int) ?? -1
        guard size == manifest.sizeBytes else {
            throw SurfaceDataError("地表データのサイズが合いません(\(size) バイト、manifest では \(manifest.sizeBytes) バイト)")
        }
        #if canImport(CryptoKit)
        guard let handle = try? FileHandle(forReadingFrom: file) else { throw SurfaceDataError("地表データを読めません") }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try? handle.read(upToCount: 64 * 1024), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        let sha256 = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard sha256 == manifest.sha256 else { throw SurfaceDataError("地表データの SHA-256 が合いません") }
        #else
        fatalError("SHA-256 の計算には CryptoKit が必要です")
        #endif
    }
}
