import Foundation

/// 山データのローカルキャッシュ。アプリでは [FileMountainCache] を使う。
public protocol MountainCache: Sendable {
    /// 指定タイルのうち取得済みのものと、その取得時刻。
    func fetchedAt(_ tiles: [Tile]) async throws -> [Tile: Date]

    func mountains(in box: BoundingBox) async throws -> [Mountain]

    /// 指定タイルの内容を mountains で置き換え、取得時刻を記録する。
    func replaceTiles(_ tiles: [Tile], mountains: [Mountain], fetchedAt: Date) async throws
}

public struct MountainQueryResult {
    /// 距離の近い順。
    public let mountains: [NearbyMountain]
    /// 範囲内に一度も取得できていないタイルがある(オフラインで未取得の地域など)。
    public let incomplete: Bool
    /// 今回の通信で失敗した場合の原因。キャッシュで表示できていても設定される。
    public let error: Error?
    /// 取り直すべきタイルがあったが、allowNetwork = false のため通信しなかった。
    public let networkSkipped: Bool
}

/// 事前ダウンロードの進み具合(タイル数)。
public struct DownloadProgress: Equatable, Sendable {
    public let doneTiles: Int
    public let totalTiles: Int
    /// 問い合わせに失敗して取り直している回数。0 なら取り直していない。
    public let retry: Int

    public init(doneTiles: Int, totalTiles: Int, retry: Int = 0) {
        self.doneTiles = doneTiles
        self.totalTiles = totalTiles
        self.retry = retry
    }

    public var fraction: Double { totalTiles == 0 ? 1 : Double(doneTiles) / Double(totalTiles) }
}

/// 現在地周辺の山を返す。キャッシュを優先し、未取得または古いタイルだけ Overpass に問い合わせる。
/// 通信に失敗してもキャッシュにあるデータで結果を返す。
public final class MountainRepository: Sendable {
    public static let defaultMaxAge: TimeInterval = 30 * 24 * 60 * 60

    private let remote: MountainRemoteSource
    private let cache: MountainCache
    private let maxAge: TimeInterval
    private let clock: @Sendable () -> Date

    public init(
        remote: MountainRemoteSource,
        cache: MountainCache,
        maxAge: TimeInterval = MountainRepository.defaultMaxAge,
        clock: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.remote = remote
        self.cache = cache
        self.maxAge = maxAge
        self.clock = clock
    }

    /// - Parameters:
    ///   - allowNetwork: false ならキャッシュだけで返す(手動取得モードや、初回の同意前)。
    ///   - maxAge: これより古いタイルは取り直す。nil なら初期化時の値。
    public func mountainsAround(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        forceRefresh: Bool = false,
        allowNetwork: Bool = true,
        maxAge: TimeInterval? = nil
    ) async throws -> MountainQueryResult {
        let maxAge = maxAge ?? self.maxAge
        let box = BoundingBox.around(latitude, longitude, radiusKm: radiusKm)
        let tiles = Tile.covering(box)
        let now = clock()
        let fetched = try await cache.fetchedAt(tiles)
        let toFetch = tiles.filter { tile in
            guard let at = fetched[tile] else { return true }
            return forceRefresh || now.timeIntervalSince(at) > maxAge
        }

        var error: Error?
        var missing = tiles.filter { fetched[$0] == nil }
        let networkSkipped = !toFetch.isEmpty && !allowNetwork
        if !toFetch.isEmpty && allowNetwork {
            do {
                let peaks = try await remote.fetchPeaks(Tile.union(toFetch))
                let targets = Set(toFetch)
                try await cache.replaceTiles(
                    toFetch,
                    mountains: (peaks ?? []).filter { targets.contains(Tile.of($0.latitude, $0.longitude)) },
                    fetchedAt: now
                )
                missing = []
            } catch is CancellationError {
                throw CancellationError()
            } catch let e {
                error = e
            }
        }

        let nearby = try await cache.mountains(in: box)
            .map { $0.seen(fromLatitude: latitude, longitude: longitude) }
            .filter { $0.distanceKm <= radiusKm }
            .sorted { $0.distanceKm < $1.distanceKm }

        return MountainQueryResult(mountains: nearby, incomplete: !missing.isEmpty, error: error, networkSkipped: networkSkipped)
    }

    /// 事前ダウンロードで問い合わせが失敗したときに、取り直すまで待つ時間(回数分)。
    public static let downloadRetryDelays: [TimeInterval] = [5, 15, 30]

    /// 事前ダウンロードで 1 回に問い合わせるタイルの縦横の数。1°四方なら混み合っていても応答が返りやすい。
    public static let downloadChunkTiles = 2

    /// 目的地など、現在地から離れた地域のタイルを前もって取得する(圏外に備えた事前ダウンロード)。
    /// 数タイルずつ Overpass に問い合わせ、終わるたびに保存して onProgress を呼ぶ。
    /// Overpass は混み合うと 504 やタイムアウトを返すので、問い合わせが失敗したら retryDelays の間隔で取り直す。
    /// 途中で失敗・中断しても取得済みのタイルは残り、もう一度呼べば残りだけを取得する。
    /// - Parameters:
    ///   - forceRefresh: true なら取得済みのタイルも取り直す(保存済みの地域の更新)。
    ///   - maxAge: これより古いタイルは取り直す。nil なら初期化時の値。
    /// - Returns: 対象タイルにある山の数。
    /// - Throws: 通信に失敗した、または中断された(CancellationError)。それまでに取得したタイルは保存済み。
    @discardableResult
    public func downloadTiles(
        _ tiles: [Tile],
        forceRefresh: Bool = false,
        maxAge: TimeInterval? = nil,
        retryDelays: [TimeInterval] = MountainRepository.downloadRetryDelays,
        onProgress: @Sendable (DownloadProgress) async -> Void = { _ in }
    ) async throws -> Int {
        let maxAge = maxAge ?? self.maxAge
        var seen = Set<Tile>()
        let all = tiles.filter { seen.insert($0).inserted }
        let now = clock()
        let fetched = try await cache.fetchedAt(all)
        let toFetch = all.filter { tile in
            guard let at = fetched[tile] else { return true }
            return forceRefresh || now.timeIntervalSince(at) > maxAge
        }
        var done = all.count - toFetch.count
        await onProgress(DownloadProgress(doneTiles: done, totalTiles: all.count))
        for chunk in Self.downloadChunks(toFetch) {
            try Task.checkCancellation()
            var attempt = 0
            var peaks: [Mountain]?
            while peaks == nil {
                do {
                    peaks = try await remote.fetchPeaks(Tile.union(chunk))
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    guard attempt < retryDelays.count else { throw error }
                    let wait = retryDelays[attempt]
                    attempt += 1
                    await onProgress(DownloadProgress(doneTiles: done, totalTiles: all.count, retry: attempt))
                    try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
                }
            }
            try Task.checkCancellation()
            let targets = Set(chunk)
            try await cache.replaceTiles(
                chunk,
                mountains: (peaks ?? []).filter { targets.contains(Tile.of($0.latitude, $0.longitude)) },
                fetchedAt: clock()
            )
            done += chunk.count
            await onProgress(DownloadProgress(doneTiles: done, totalTiles: all.count))
        }
        guard !all.isEmpty else { return 0 }
        try Task.checkCancellation()
        let targets = Set(all)
        return try await cache.mountains(in: Tile.union(all))
            .filter { targets.contains(Tile.of($0.latitude, $0.longitude)) }
            .count
    }

    /// タイルを downloadChunkTiles 四方ごとにまとめる。北西から順に並べる。
    public static func downloadChunks(_ tiles: [Tile]) -> [[Tile]] {
        func floorDiv(_ a: Int, _ b: Int) -> Int { Int(floor(Double(a) / Double(b))) }
        struct Key: Hashable { let lat: Int; let lon: Int }
        return Dictionary(grouping: tiles) { Key(lat: floorDiv($0.latIndex, downloadChunkTiles), lon: floorDiv($0.lonIndex, downloadChunkTiles)) }
            .sorted { $0.key.lat != $1.key.lat ? $0.key.lat > $1.key.lat : $0.key.lon < $1.key.lon }
            .map(\.value)
    }
}
