// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import YamamukiCore

/// URL の末尾で応答を決める、テスト用の通信。
private final class OnlineStubProtocol: URLProtocol {
    static var requests: [URL] = []
    private static let lock = NSLock()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        Self.lock.lock()
        Self.requests.append(url)
        Self.lock.unlock()
        let (status, body): (Int, Data) = switch url.lastPathComponent {
        case "1.png": (200, Data([1, 2, 3]))
        case "2.png": (404, Data())
        default: (500, Data())
        }
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class OnlineSurfaceTests: XCTestCase {
    private let fujiLat = 35.3606
    private let fujiLon = 138.7274

    func testUrlAndLevelsFollowTheGsiTiles() {
        XCTAssertEqual(OnlineSurface.gsiPale.url(SurfaceTile(z: 15, x: 29099, y: 12902)).absoluteString,
                       "https://cyberjapandata.gsi.go.jp/xyz/pale/15/29099/12902.png")
        XCTAssertEqual(OnlineSurface.gsiPhoto.url(SurfaceTile(z: 18, x: 1, y: 2)).absoluteString,
                       "https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/18/1/2.jpg")
        XCTAssertEqual(OnlineSurface.gsiRelief.levels.last?.z, 15)
        XCTAssertTrue(OnlineSurface.allCases.flatMap(\.levels).allSatisfy { $0.tilePixels == 256 })
        XCTAssertEqual(OnlineSurface(rawValue: "gsi-hillshade"), .gsiHillshade)
        XCTAssertNil(OnlineSurface(rawValue: "esa-worldcover-z9"))
        // 地表データの種類と ID がかぶらない(設定に並べて覚えるため)。
        XCTAssertTrue(OnlineSurface.allCases.allSatisfy { o in !SurfaceDataset.allCases.contains { $0.id == o.id } })
    }

    func testImageTilesCoverEveryTileTheImageReads() throws {
        for radiusKm in [2.0, 8.0, 45.0] {
            let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: radiusKm)
            let level = try XCTUnwrap(SurfaceRaster.chooseLevel(OnlineSurface.gsiPale.levels, frame: frame, size: 256))
            let planned = Set(SurfaceRaster.imageTiles(frame, level: level, size: 256))
            var read = Set<SurfaceTile>()
            _ = try SurfaceRaster.renderImage(frame, level: level, size: 256) { tile in
                read.insert(tile)
                return nil
            }
            XCTAssertTrue(read.isSubset(of: planned), "半径 \(radiusKm) km で読むタイルが足りません")
            // 画像の範囲より大きくは取らない(周りに 1 枚ずつまで)。
            let side = Double(read.count).squareRoot() + 2
            XCTAssertLessThanOrEqual(Double(planned.count), side * side, "半径 \(radiusKm) km で \(planned.count) 枚")
        }
    }

    func testRenderImageCopiesOpaqueTilesAndLeavesMissingTilesTransparent() throws {
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 2)
        let level = SurfaceLevel(z: 15, tilePixels: 256)
        let color: UInt32 = 0xFF33_6699
        let opaque = try SurfaceRaster.renderImage(frame, level: level, size: 64) { _ in [UInt32](repeating: color, count: 256 * 256) }
        XCTAssertTrue(opaque.allSatisfy { $0 == color })
        let missing = try SurfaceRaster.renderImage(frame, level: level, size: 64) { _ in nil }
        XCTAssertTrue(missing.allSatisfy { $0 == 0 })
    }

    func testRenderImageDoesNotDarkenEdgesOfTransparentPixels() throws {
        // 左半分が透明な黒、右半分が不透明な白のタイル。境目の半透明な画素も白のまま(黒が混ざらない)。
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 2)
        let level = SurfaceLevel(z: 12, tilePixels: 256)
        let tile = (0..<(256 * 256)).map { $0 % 256 < 128 ? UInt32(0) : 0xFFFF_FFFF }
        let out = try SurfaceRaster.renderImage(frame, level: level, size: 256) { _ in tile }
        let visible = out.filter { $0 >> 24 > 0 }
        XCTAssertFalse(visible.isEmpty)
        XCTAssertTrue(visible.allSatisfy { $0 & 0xFF_FFFF == 0xFF_FFFF })
        XCTAssertTrue(out.contains { (1...254).contains($0 >> 24) }, "境目の半透明な画素がありません")
    }

    func testClientKeepsFetchedTilesInMemoryAndCountsFailures() async throws {
        OnlineStubProtocol.requests = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [OnlineStubProtocol.self]
        let client = OnlineSurfaceClient(session: URLSession(configuration: config), userAgent: "test")
        let tiles = [SurfaceTile(z: 15, x: 0, y: 1), SurfaceTile(z: 15, x: 0, y: 2), SurfaceTile(z: 15, x: 0, y: 3)]
        let first = try await client.fetch(.gsiPale, tiles: tiles)
        XCTAssertEqual(Set(first.tiles.keys), [SurfaceTile(z: 15, x: 0, y: 1)])
        XCTAssertEqual(first.tiles[SurfaceTile(z: 15, x: 0, y: 1)], Data([1, 2, 3]))
        // 404 はタイルが無いだけなので失敗に数えない。
        XCTAssertEqual(first.failed, 1)
        XCTAssertEqual(OnlineStubProtocol.requests.count, 3)

        // 取れたタイルと 404 だったタイルは取り直さない。失敗したタイルだけ取り直す。
        let second = try await client.fetch(.gsiPale, tiles: tiles)
        XCTAssertEqual(Set(second.tiles.keys), Set(first.tiles.keys))
        XCTAssertEqual(OnlineStubProtocol.requests.dropFirst(3).map(\.path), ["/xyz/pale/15/0/3.png"])
    }
}
