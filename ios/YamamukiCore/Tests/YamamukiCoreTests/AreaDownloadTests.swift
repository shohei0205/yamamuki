import Foundation
import XCTest
@testable import YamamukiCore

private struct Offline: Error {}

private final class FakeRemote: MountainRemoteSource, @unchecked Sendable {
    let peaks: [Mountain]
    var calls: [BoundingBox] = []
    /// この回数目(1 始まり)の問い合わせで失敗する。
    var failAt: Set<Int> = []

    init(_ peaks: [Mountain]) { self.peaks = peaks }

    func fetchPeaks(_ box: BoundingBox) async throws -> [Mountain] {
        calls.append(box)
        if failAt.contains(calls.count) { throw Offline() }
        return peaks.filter { box.contains($0.latitude, $0.longitude) }
    }
}

private final class InMemoryCache: MountainCache, @unchecked Sendable {
    var tiles: [Tile: Date] = [:]
    var mountains: [Int64: Mountain] = [:]

    func fetchedAt(_ tiles: [Tile]) async throws -> [Tile: Date] {
        self.tiles.filter { tiles.contains($0.key) }
    }

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

private final class ProgressLog: @unchecked Sendable {
    var items: [DownloadProgress] = []
}

final class AreaDownloadTests: XCTestCase {
    private let fuji = Mountain(osmId: 1, name: "富士山", latitude: 35.3606, longitude: 138.7274, elevationM: 3776)
    private let yari = Mountain(osmId: 2, name: "槍ヶ岳", latitude: 36.3420, longitude: 137.6476, elevationM: 3180)
    private let outside = Mountain(osmId: 3, name: "範囲外の山", latitude: 40.0, longitude: 140.0, elevationM: 1000)
    private let nagano = Prefecture.byCode(20)!

    private func repo(_ remote: FakeRemote, _ cache: MountainCache) -> MountainRepository {
        MountainRepository(remote: remote, cache: cache, maxAge: 1000, clock: { Date(timeIntervalSince1970: 1_000) })
    }

    func testDownloadsAllTilesInChunksAndReportsProgress() async throws {
        let remote = FakeRemote([fuji, yari, outside])
        let cache = InMemoryCache()
        let log = ProgressLog()

        let count = try await repo(remote, cache).downloadTiles(nagano.tiles) { log.items.append($0) }

        let total = nagano.tiles.count
        XCTAssertEqual(remote.calls.count, MountainRepository.downloadChunks(nagano.tiles).count)
        XCTAssertLessThan(remote.calls.count, total, "数タイルずつまとめて問い合わせる")
        XCTAssertEqual(log.items.first, DownloadProgress(doneTiles: 0, totalTiles: total))
        XCTAssertEqual(log.items.last, DownloadProgress(doneTiles: total, totalTiles: total))
        XCTAssertEqual(Set(cache.tiles.keys), Set(nagano.tiles))
        XCTAssertEqual(count, 2, "タイル単位で取るので、長野県の外の富士山も入る(範囲外の山は入らない)")
    }

    func testResumesAfterFailureWithoutRefetchingDoneTiles() async throws {
        let remote = FakeRemote([yari])
        remote.failAt = [3]
        let cache = InMemoryCache()
        let repo = repo(remote, cache)

        do {
            try await repo.downloadTiles(nagano.tiles, retryDelays: [])
            XCTFail("3 回目の問い合わせで失敗するはず")
        } catch is Offline {}
        let doneBefore = cache.tiles.count
        XCTAssertGreaterThan(doneBefore, 0, "失敗する前に取得したタイルは残る")

        let log = ProgressLog()
        try await repo.downloadTiles(nagano.tiles) { log.items.append($0) }
        XCTAssertEqual(log.items.first?.doneTiles, doneBefore, "取得済みの分は最初から済みとして数える")
        XCTAssertEqual(cache.tiles.count, nagano.tiles.count)
        XCTAssertEqual(remote.calls.count, MountainRepository.downloadChunks(nagano.tiles).count + 1, "失敗した 1 回を除き、同じ範囲を二度問い合わせない")
    }

    func testRetriesFailedChunkBeforeGivingUp() async throws {
        // 3 回目の問い合わせ(= 3 つ目の区画)が 2 回続けて失敗しても、取り直して最後まで取得する。
        let remote = FakeRemote([yari])
        remote.failAt = [3, 4]
        let cache = InMemoryCache()
        let log = ProgressLog()

        try await repo(remote, cache).downloadTiles(nagano.tiles, retryDelays: [0, 0, 0]) { log.items.append($0) }

        XCTAssertEqual(cache.tiles.count, nagano.tiles.count)
        XCTAssertEqual(remote.calls.count, MountainRepository.downloadChunks(nagano.tiles).count + 2)
        XCTAssertEqual(log.items.map(\.retry).filter { $0 > 0 }, [1, 2], "取り直していることを知らせる")
        XCTAssertEqual(log.items.last?.retry, 0)

        // 取り直す回数を使い切ったら失敗として返す。
        let failing = FakeRemote([yari])
        failing.failAt = Set(1...10)
        do {
            try await repo(failing, InMemoryCache()).downloadTiles(nagano.tiles, retryDelays: [0, 0])
            XCTFail("取り直しても失敗するはず")
        } catch is Offline {}
        XCTAssertEqual(failing.calls.count, 3, "最初の 1 回 + 取り直し 2 回")
    }

    func testRefreshRefetchesEvenFreshTiles() async throws {
        let remote = FakeRemote([yari])
        let repo = repo(remote, InMemoryCache())
        try await repo.downloadTiles(nagano.tiles)
        let first = remote.calls.count

        try await repo.downloadTiles(nagano.tiles)
        XCTAssertEqual(remote.calls.count, first, "新しいタイルは取り直さない")
        try await repo.downloadTiles(nagano.tiles, forceRefresh: true)
        XCTAssertEqual(remote.calls.count, first * 2)
    }

    func testChunksGroupTwoByTwoTiles() {
        let tiles = (0...2).flatMap { la in (0...2).map { lo in Tile(latIndex: la, lonIndex: lo) } }
        let chunks = MountainRepository.downloadChunks(tiles)
        XCTAssertEqual(chunks.map(\.count), [2, 1, 4, 2], "北の行から、西から順に並ぶ")
        XCTAssertEqual(Set(chunks.flatMap { $0 }), Set(tiles))
    }

    func testPrefecturesCoverJapanWithReasonableTileCounts() {
        XCTAssertEqual(Prefecture.all.map(\.code), Array(1...47))
        for p in Prefecture.all {
            XCTAssertTrue((1...150).contains(p.tiles.count), "\(p.name): \(p.tiles.count) タイル")
            for box in p.areas {
                XCTAssertTrue(box.south < box.north && box.west < box.east, p.name)
                XCTAssertTrue((24.0...46.0).contains(box.south) && (122.0...146.0).contains(box.west), p.name)
            }
        }
        XCTAssertTrue(nagano.areas[0].contains(yari.latitude, yari.longitude))
        XCTAssertTrue(Prefecture.byCode(46)!.areas.contains { $0.contains(30.3358, 130.5047) }, "鹿児島県に屋久島(宮之浦岳)が入る")
    }

    func testFileCacheClearKeepsSavedTilesAndRemovesSelected() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let cache = FileMountainCache(directory: dir)
        let yariTile = Tile.of(yari.latitude, yari.longitude)
        let fujiTile = Tile.of(fuji.latitude, fuji.longitude)
        try await cache.replaceTiles([yariTile, fujiTile], mountains: [yari, fuji], fetchedAt: Date())

        await cache.clear(keeping: [yariTile])
        let afterClear = try await cache.fetchedAt([yariTile, fujiTile])
        XCTAssertEqual(Set(afterClear.keys), [yariTile])

        await cache.remove([yariTile])
        let afterRemove = try await cache.fetchedAt([yariTile])
        XCTAssertTrue(afterRemove.isEmpty)
        let reopened = try await FileMountainCache(directory: dir).fetchedAt([yariTile, fujiTile])
        XCTAssertTrue(reopened.isEmpty, "ファイルからも消えている")
    }
}
