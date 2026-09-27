import Foundation
import XCTest
@testable import YamamukiCore

private struct Offline: Error {}

private final class FakeRemote: MountainRemoteSource, @unchecked Sendable {
    var peaks: [Mountain]
    var calls: [BoundingBox] = []
    var fail = false

    init(_ peaks: [Mountain]) { self.peaks = peaks }

    func fetchPeaks(_ box: BoundingBox) async throws -> [Mountain] {
        calls.append(box)
        if fail { throw Offline() }
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

private final class Clock: @unchecked Sendable {
    var now = Date(timeIntervalSince1970: 1_000)
}

final class MountainRepositoryTests: XCTestCase {
    // 大月駅付近から富士山(約 34km)と、遠くの山(範囲外)を返すフェイク
    private let lat = 35.61
    private let lon = 138.94
    private let fuji = Mountain(osmId: 1, name: "富士山", latitude: 35.3606, longitude: 138.7274, elevationM: 3776)
    private let near = Mountain(osmId: 2, name: "岩殿山", latitude: 35.62, longitude: 138.96, elevationM: 634)
    private let far = Mountain(osmId: 3, name: "遠い山", latitude: 36.4, longitude: 138.94, elevationM: 2000)
    private let clock = Clock()

    private func repo(_ remote: FakeRemote, _ cache: MountainCache) -> MountainRepository {
        let clock = self.clock
        return MountainRepository(remote: remote, cache: cache, maxAge: 1, clock: { clock.now })
    }

    func testFetchesThenServesFromCache() async throws {
        let remote = FakeRemote([fuji, near, far])
        let repo = repo(remote, InMemoryCache())

        let first = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)
        XCTAssertEqual(first.mountains.map(\.mountain.name), ["岩殿山", "富士山"])
        XCTAssertFalse(first.incomplete)
        XCTAssertNil(first.error)
        XCTAssertEqual(remote.calls.count, 1)

        let second = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)
        XCTAssertEqual(second.mountains, first.mountains)
        XCTAssertEqual(remote.calls.count, 1, "キャッシュ済みなら再取得しない")
    }

    func testNearbyHasDistanceAndBearing() async throws {
        let result = try await repo(FakeRemote([fuji]), InMemoryCache()).mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)
        let m = try XCTUnwrap(result.mountains.first)
        XCTAssertEqual(m.distanceKm, 33.8, accuracy: 1.0)
        XCTAssertTrue((200.0...240.0).contains(m.bearingDeg), "富士山は南西方向: \(m.bearingDeg)")
    }

    func testOfflineUsesCacheAndReportsError() async throws {
        let remote = FakeRemote([fuji, near])
        let repo = repo(remote, InMemoryCache())
        _ = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)

        remote.fail = true
        clock.now += 5 // キャッシュを古くして再取得を試みさせる
        let result = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)

        XCTAssertEqual(result.mountains.count, 2)
        XCTAssertFalse(result.incomplete)
        XCTAssertNotNil(result.error)
    }

    func testOfflineWithoutCacheIsIncomplete() async throws {
        let remote = FakeRemote([fuji])
        remote.fail = true
        let result = try await repo(remote, InMemoryCache()).mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)
        XCTAssertTrue(result.mountains.isEmpty)
        XCTAssertTrue(result.incomplete)
        XCTAssertNotNil(result.error)
    }

    func testOnlyMissingTilesAreFetched() async throws {
        let remote = FakeRemote([fuji, near])
        let cache = InMemoryCache()
        let repo = repo(remote, cache)
        _ = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 10)
        let cachedTiles = cache.tiles

        _ = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)

        XCTAssertFalse(cachedTiles.isEmpty)
        XCTAssertEqual(remote.calls.count, 2)
        // 2回目は拡大分だけを記録し、既存タイルの取得時刻は変えない
        for (tile, at) in cachedTiles { XCTAssertEqual(cache.tiles[tile], at) }
        XCTAssertGreaterThan(cache.tiles.count, cachedTiles.count)
    }

    func testSkipsNetworkWhenNotAllowed() async throws {
        let remote = FakeRemote([fuji, near])
        let repo = repo(remote, InMemoryCache())

        let result = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50, allowNetwork: false)
        XCTAssertEqual(remote.calls.count, 0)
        XCTAssertTrue(result.networkSkipped)
        XCTAssertTrue(result.incomplete)
        XCTAssertNil(result.error)

        // 取得済みなら通信を控えていても表示でき、控えた扱いにもならない。
        _ = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)
        let cached = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50, allowNetwork: false)
        XCTAssertEqual(cached.mountains.map(\.mountain.name), ["岩殿山", "富士山"])
        XCTAssertFalse(cached.networkSkipped)
    }

    func testMaxAgeCanBeGivenPerCall() async throws {
        let remote = FakeRemote([fuji])
        let repo = repo(remote, InMemoryCache())
        _ = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)
        clock.now += 5 // 既定の 1 秒は過ぎているが、10 秒以内
        _ = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50, maxAge: 10)
        XCTAssertEqual(remote.calls.count, 1)
        _ = try await repo.mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)
        XCTAssertEqual(remote.calls.count, 2)
    }

    func testFileCacheRoundTrip() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("yamamuki-test-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let remote = FakeRemote([fuji, near])
        _ = try await repo(remote, FileMountainCache(directory: dir)).mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)

        // 新しいインスタンス(アプリの再起動)でもファイルから読める。
        let reopened = FileMountainCache(directory: dir)
        remote.fail = true
        let result = try await repo(remote, reopened).mountainsAround(latitude: lat, longitude: lon, radiusKm: 50, allowNetwork: false)
        XCTAssertEqual(result.mountains.map(\.mountain.name), ["岩殿山", "富士山"])
        XCTAssertFalse(result.incomplete)

        let info = await reopened.info()
        XCTAssertEqual(info.mountainCount, 2)
        XCTAssertGreaterThan(info.tileCount, 0)
        XCTAssertGreaterThan(info.sizeBytes, 0)

        await reopened.clear()
        let cleared = await reopened.info()
        XCTAssertEqual(cleared.tileCount, 0)
        let empty = try await reopened.mountains(in: BoundingBox.around(lat, lon, radiusKm: 50))
        XCTAssertTrue(empty.isEmpty)
    }

    func testFileCacheIsExcludedFromBackup() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("yamamuki-test-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = try await repo(FakeRemote([fuji]), FileMountainCache(directory: dir)).mountainsAround(latitude: lat, longitude: lon, radiusKm: 50)

        let values = try dir.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }
}
