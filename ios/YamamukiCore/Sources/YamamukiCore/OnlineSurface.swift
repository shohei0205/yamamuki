// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// 方位盤の背景に描く、通信して取る地図(国土地理院の地理院タイル)の種類。並びは選択画面の並び。
/// 地表データ(`SurfaceDataset`)と違ってダウンロードせず、通信できるときに表示している周りのタイルだけを取る。端末には保存しない。
/// タイルは Web メルカトルの XYZ タイル(一辺 256 画素)。`maxZoom` はタイルがある最も細かいズーム。
public enum OnlineSurface: String, CaseIterable, Codable, Sendable {
    /// 淡色地図。道路・川・等高線などを淡い色で描いた地図。
    case gsiPale = "gsi-pale"
    /// 標準地図。
    case gsiStd = "gsi-std"
    /// 陰影起伏図。
    case gsiHillshade = "gsi-hillshade"
    /// 色別標高図。標高を色で塗り分け、陰影を重ねた図。
    case gsiRelief = "gsi-relief"
    /// 写真(全国最新写真・シームレス)。
    case gsiPhoto = "gsi-photo"

    /// タイルの一辺の画素数。
    public static let tilePixels = 256

    /// 出典に添える、地理院タイルの一覧のページ。利用規約で、出典と一緒に示すよう求められている。
    public static let creditsUrl = "https://maps.gsi.go.jp/development/ichiran.html"

    private static let baseUrl = "https://cyberjapandata.gsi.go.jp/xyz"

    public var id: String { rawValue }

    private var path: String {
        switch self {
        case .gsiPale: "pale"
        case .gsiStd: "std"
        case .gsiHillshade: "hillshademap"
        case .gsiRelief: "relief"
        case .gsiPhoto: "seamlessphoto"
        }
    }

    private var fileExtension: String { self == .gsiPhoto ? "jpg" : "png" }

    public var minZoom: Int {
        switch self {
        case .gsiHillshade, .gsiPhoto: 2
        default: 5
        }
    }

    public var maxZoom: Int {
        switch self {
        case .gsiPale, .gsiStd, .gsiPhoto: 18
        case .gsiHillshade: 16
        case .gsiRelief: 15
        }
    }

    /// 読めるズームの段。
    public var levels: [SurfaceLevel] { (minZoom...maxZoom).map { SurfaceLevel(z: $0, tilePixels: Self.tilePixels) } }

    /// `tile` の画像の URL。
    public func url(_ tile: SurfaceTile) -> URL {
        URL(string: "\(Self.baseUrl)/\(path)/\(tile.z)/\(tile.x)/\(tile.y).\(fileExtension)")!
    }
}

/// `OnlineSurface` のタイルを取る。取ったタイルは、アプリを開いているあいだだけメモリに覚えておき、拡縮や少しの移動で
/// 取り直さないようにする。ファイルには保存しない(オフライン用のキャッシュは持たない)。
public actor OnlineSurfaceClient {
    /// 取った結果。`tiles` はタイルごとの画像(タイルが無い場所は入らない)、`failed` は通信に失敗して取れなかった枚数。
    public struct Result: Sendable {
        public let tiles: [SurfaceTile: Data]
        public let failed: Int
    }

    private let session: URLSession
    private let userAgent: String
    private let maxCachedTiles: Int
    private let parallelism: Int
    /// 取ったタイルの画像。タイルが無かった(404)ときは空の Data。
    private var cache: [URL: Data] = [:]
    /// `cache` の URL を、最後に使った順に並べたもの。古く使ったものから捨てる。
    private var recent: [URL] = []

    /// - Parameter session: ファイルに残さないよう、既定はディスクのキャッシュを持たない通信を使う。
    /// - Parameter maxCachedTiles: メモリに覚えておくタイルの枚数の上限。
    public init(session: URLSession = OnlineSurfaceClient.makeSession(), userAgent: String = "yamamuki-ios",
                maxCachedTiles: Int = 256, parallelism: Int = 6) {
        self.session = session
        self.userAgent = userAgent
        self.maxCachedTiles = maxCachedTiles
        self.parallelism = parallelism
    }

    /// ディスクにもメモリにも、通信の仕組みのキャッシュを持たない通信。
    public static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 20
        return URLSession(configuration: config)
    }

    /// `surface` の `tiles` を、まとめて取る。取れたタイルはすぐメモリに覚えるので、途中で取り消されても次に取り直さない。
    /// 通信に失敗したタイルは `Result.failed` に数え、ほかのタイルは返す。
    public func fetch(_ surface: OnlineSurface, tiles: [SurfaceTile]) async throws -> Result {
        var found: [SurfaceTile: Data] = [:]
        var failed = 0
        var pending: [(SurfaceTile, URL)] = []
        for tile in tiles {
            let url = surface.url(tile)
            if let known = cached(url) {
                if !known.isEmpty { found[tile] = known }
            } else {
                pending.append((tile, url))
            }
        }
        let session = session
        let userAgent = userAgent
        try await withThrowingTaskGroup(of: (SurfaceTile, URL, Data?).self) { group in
            var next = 0
            func addNext() {
                guard next < pending.count else { return }
                let (tile, url) = pending[next]
                next += 1
                group.addTask { (tile, url, await Self.fetchOne(session, url, userAgent: userAgent)) }
            }
            for _ in 0..<parallelism { addNext() }
            while let (tile, url, data) = try await group.next() {
                try Task.checkCancellation()
                if let data {
                    store(url, data)
                    if !data.isEmpty { found[tile] = data }
                } else {
                    failed += 1
                }
                addNext()
            }
        }
        return Result(tiles: found, failed: failed)
    }

    /// 1 枚を取る。タイルが無い(404)ときは空の Data。通信に失敗したら nil。
    private static func fetchOne(_ session: URLSession, _ url: URL, userAgent: String) async -> Data? {
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await session.data(for: request), let http = response as? HTTPURLResponse else { return nil }
        if (200..<300).contains(http.statusCode) { return data }
        if http.statusCode == 404 { return Data() }
        return nil
    }

    private func cached(_ url: URL) -> Data? {
        guard let data = cache[url] else { return nil }
        touch(url)
        return data
    }

    private func store(_ url: URL, _ data: Data) {
        cache[url] = data
        touch(url)
        while recent.count > maxCachedTiles {
            cache[recent.removeFirst()] = nil
        }
    }

    private func touch(_ url: URL) {
        recent.removeAll { $0 == url }
        recent.append(url)
    }
}
