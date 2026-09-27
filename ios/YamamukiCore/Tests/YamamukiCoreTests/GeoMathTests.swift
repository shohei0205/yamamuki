import XCTest
@testable import YamamukiCore

final class GeoMathTests: XCTestCase {
    func testDistanceTokyoToFuji() {
        // 東京駅 → 富士山剣ヶ峰 はおよそ 100km
        XCTAssertEqual(GeoMath.distanceKm(35.6812, 139.7671, 35.3606, 138.7274), 100.8, accuracy: 1.5)
    }

    func testBearingCardinalDirections() {
        XCTAssertEqual(GeoMath.bearingDeg(35, 138, 36, 138), 0, accuracy: 0.01)
        XCTAssertEqual(GeoMath.bearingDeg(0, 138, 0, 139), 90, accuracy: 0.01)
        XCTAssertEqual(GeoMath.bearingDeg(36, 138, 35, 138), 180, accuracy: 0.01)
        XCTAssertEqual(GeoMath.bearingDeg(0, 139, 0, 138), 270, accuracy: 0.01)
    }

    func testBoundingBoxContainsCircle() {
        let box = BoundingBox.around(35.68, 139.77, radiusKm: 50)
        XCTAssertGreaterThanOrEqual(GeoMath.distanceKm(35.68, 139.77, box.north, 139.77), 49.9)
        XCTAssertGreaterThanOrEqual(GeoMath.distanceKm(35.68, 139.77, 35.68, box.east), 49.9)
    }

    func testTilesCoverBox() {
        let tiles = Tile.covering(BoundingBox(south: 35.1, west: 138.2, north: 35.9, east: 138.6))
        XCTAssertEqual(
            Set(tiles),
            [Tile(latIndex: 70, lonIndex: 276), Tile(latIndex: 70, lonIndex: 277), Tile(latIndex: 71, lonIndex: 276), Tile(latIndex: 71, lonIndex: 277)]
        )
        XCTAssertEqual(Tile.union(tiles), BoundingBox(south: 35, west: 138, north: 36, east: 139))
    }
}
