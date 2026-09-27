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
                    mountains: peaks.filter { targets.contains(Tile.of($0.latitude, $0.longitude)) },
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
}
