// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import XCTest
@testable import YamamukiCore

final class SurfaceRasterTests: XCTestCase {
    private let fujiLat = 35.3606
    private let fujiLon = 138.7274
    private let z9Levels = (5...9).map { SurfaceLevel(z: $0, tilePixels: 512) }

    func testDestinationIsTheInverseOfDistanceAndBearing() {
        for (distance, bearing) in [(0.5, 0.0), (20.0, 90.0), (80.0, 217.0), (250.0, 315.0)] {
            let p = GeoMath.destination(fujiLat, fujiLon, distanceKm: distance, bearingDeg: bearing)
            XCTAssertEqual(GeoMath.distanceKm(fujiLat, fujiLon, p.lat, p.lon), distance, accuracy: 1e-6)
            XCTAssertEqual(GeoMath.bearingDeg(fujiLat, fujiLon, p.lat, p.lon), bearing, accuracy: 1e-6)
        }
        let same = GeoMath.destination(fujiLat, fujiLon, distanceKm: 0, bearingDeg: 123)
        XCTAssertEqual(same.lat, fujiLat, accuracy: 1e-9)
        XCTAssertEqual(same.lon, fujiLon, accuracy: 1e-9)
        // 経度は -180〜180° に収める。
        XCTAssertLessThan(GeoMath.destination(0, 179.9, distanceKm: 100, bearingDeg: 90).lon, -179)
    }

    func testRadiusCoversTheScreenWithRoomToMove() {
        for range in [2.0, 5.0, 15.0, 37.0, 80.0] {
            let radius = SurfaceRaster.radiusKm(range)
            // 画面に要る半径の 1.5 倍以上で、その √2 倍より狭い。
            XCTAssertGreaterThanOrEqual(radius, range * SurfaceRaster.reach * 1.5)
            XCTAssertTrue(radius < range * SurfaceRaster.reach * 1.5 * 1.4143 || radius == 2)
        }
        // 少しの拡縮では変わらない。
        XCTAssertEqual(SurfaceRaster.radiusKm(15), SurfaceRaster.radiusKm(15.5))
    }

    func testRendersAgainOnlyWhenTheScreenLeavesTheImageOrItIsTooCoarse() {
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: SurfaceRaster.radiusKm(15))
        func needs(_ lat: Double, _ lon: Double, _ range: Double) -> Bool {
            SurfaceRaster.needsRender(frame, centerLat: lat, centerLon: lon, rangeKm: range)
        }
        XCTAssertTrue(SurfaceRaster.needsRender(nil, centerLat: fujiLat, centerLon: fujiLon, rangeKm: 15))
        XCTAssertFalse(needs(fujiLat, fujiLon, 15))
        // 少し歩いても、少し拡縮しても作り直さない。
        let near = GeoMath.destination(fujiLat, fujiLon, distanceKm: 3, bearingDeg: 45)
        XCTAssertFalse(needs(near.lat, near.lon, 15))
        XCTAssertFalse(needs(fujiLat, fujiLon, 18))
        XCTAssertFalse(needs(fujiLat, fujiLon, 8))
        // 画面が画像からはみ出すほど動かしたり、遠くまで表示したりしたら作り直す。
        let far = GeoMath.destination(fujiLat, fujiLon, distanceKm: 20, bearingDeg: 45)
        XCTAssertTrue(needs(far.lat, far.lon, 15))
        XCTAssertTrue(needs(fujiLat, fujiLon, 40))
        // 大きく拡大したら、粗すぎるので作り直す。
        XCTAssertTrue(needs(fujiLat, fujiLon, 3))
    }

    func testLevelIsTheCoarsestOneFinerThanTheImage() {
        func z(_ range: Double) -> Int? {
            SurfaceRaster.chooseLevel(z9Levels, frame: SurfaceFrame(centerLat: 36, centerLon: 138, radiusKm: SurfaceRaster.radiusKm(range)))?.z
        }
        // 表示範囲 15km の画像は 1 画素が約 50m。ズーム 9(約 124m)でも粗いので、最も細かい段を使う。
        XCTAssertEqual(z(15), 9)
        XCTAssertEqual(z(2), 9)
        // 表示範囲 80km の画像は 1 画素が約 280m。ズーム 8(約 247m)で足りる。
        XCTAssertEqual(z(80), 8)
        XCTAssertNil(SurfaceRaster.chooseLevel([], frame: SurfaceFrame(centerLat: 36, centerLon: 138, radiusKm: 20)))
    }

    func testTilesCoverTheCornersOfTheImage() {
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 100)
        let tiles = Set(SurfaceRaster.tiles(frame, z: 9))
        for bearing in [45.0, 135.0, 225.0, 315.0] {
            let p = GeoMath.destination(fujiLat, fujiLon, distanceKm: 100 * 1.4142, bearingDeg: bearing)
            XCTAssertTrue(tiles.contains(SurfaceTiles.tileAt(p.lat, p.lon, z: 9)))
        }
    }

    /// 緯度経度が、`frame` の一辺 `size` の画像のどの画素に当たるか(列, 行)。
    private func pixelOf(_ frame: SurfaceFrame, _ size: Int, _ lat: Double, _ lon: Double) -> (i: Int, j: Int) {
        let d = GeoMath.distanceKm(frame.centerLat, frame.centerLon, lat, lon)
        let b = GeoMath.bearingDeg(frame.centerLat, frame.centerLon, lat, lon) * .pi / 180
        let i = Int((d * sin(b) + frame.radiusKm) / (frame.radiusKm * 2) * Double(size))
        let j = Int((frame.radiusKm - d * cos(b)) / (frame.radiusKm * 2) * Double(size))
        return (i, j)
    }

    /// タイルごとに違う 1 色。
    private func colorOf(_ tile: SurfaceTile) -> UInt32 {
        // 古い Xcode では 1 つの式にすると型を決めきれないので、成分ごとに分ける。
        let red = UInt32(tile.x & 0xFF) << 16
        let green = UInt32(tile.y & 0xFF) << 8
        let opaque: UInt32 = 0xFF00_0000
        return opaque | red | green | 0x7F
    }

    func testRenderPutsEachTileWhereItsPlaceIs() throws {
        let level = SurfaceLevel(z: 9, tilePixels: 16)
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 80)
        let size = 128
        var requested: [SurfaceTile] = []
        let image = try SurfaceRaster.render(frame, level: level, size: size) { tile in
            requested.append(tile)
            return [UInt32](repeating: self.colorOf(tile), count: 256)
        }
        // 1 枚のタイルにつき 1 回だけ読む。
        XCTAssertEqual(Set(requested).count, requested.count)
        XCTAssertTrue(Set(requested).isSubset(of: Set(SurfaceRaster.tiles(frame, z: 9))))
        // タイルの中央は、そのタイルの色になる。
        var checked = 0
        for tile in SurfaceRaster.tiles(frame, z: 9) {
            let box = tile.bounds
            let p = pixelOf(frame, size, (box.north + box.south) / 2, (box.west + box.east) / 2)
            guard (0..<size).contains(p.i), (0..<size).contains(p.j) else { continue }
            XCTAssertEqual(image[p.j * size + p.i], colorOf(tile), "tile \(tile)")
            checked += 1
        }
        XCTAssertGreaterThanOrEqual(checked, 4)
        // 中心は画像の中央、北は上、東は右。
        let middle = pixelOf(frame, size, fujiLat, fujiLon)
        XCTAssertEqual(middle.i, size / 2)
        XCTAssertEqual(middle.j, size / 2)
        let north = GeoMath.destination(fujiLat, fujiLon, distanceKm: 40, bearingDeg: 0)
        XCTAssertLessThan(pixelOf(frame, size, north.lat, north.lon).j, size / 2)
        let east = GeoMath.destination(fujiLat, fujiLon, distanceKm: 40, bearingDeg: 90)
        XCTAssertGreaterThan(pixelOf(frame, size, east.lat, east.lon).i, size / 2)
    }

    func testRenderInterpolatesBetweenPixels() throws {
        // 右へ行くほど青が濃くなるタイル(列 × 10)。画像を元の画素より細かく作ると、間の値で埋まる。
        let level = SurfaceLevel(z: 9, tilePixels: 16)
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 8)
        let size = 96
        let tile = SurfaceTiles.tileAt(fujiLat, fujiLon, z: 9)
        let opaque: UInt32 = 0xFF00_0000
        let gradient: [UInt32] = (0..<256).map { (index: Int) -> UInt32 in
            let blue = UInt32(index % 16 * 10)
            return opaque | blue
        }
        let image = try SurfaceRaster.render(frame, level: level, size: size) { _ in gradient }
        var blues = Set<Int>()
        for i in 0..<size {
            let east = (Double(i) + 0.5) / Double(size) * 16 - 8
            let p = GeoMath.destination(fujiLat, fujiLon, distanceKm: abs(east), bearingDeg: east >= 0 ? 90 : 270)
            guard SurfaceTiles.tileAt(p.lat, p.lon, z: 9) == tile else { continue }
            let column = (SurfaceTiles.worldU(p.lon) * 512 - Double(tile.x)) * 16 - 0.5
            guard column >= 0.5, column <= 14.5 else { continue }
            let value = image[size / 2 * size + i]
            XCTAssertEqual(Double(value & 0xFF), column * 10, accuracy: 2, "列 \(i)")
            XCTAssertEqual(value >> 24, 0xFF)
            blues.insert(Int(value & 0xFF))
        }
        // 元の 16 段より細かい値が出ている。
        XCTAssertGreaterThan(blues.count, 20)
    }

    func testRenderLeavesMissingTilesTransparent() throws {
        let level = SurfaceLevel(z: 9, tilePixels: 8)
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 40)
        let center = SurfaceTiles.tileAt(fujiLat, fujiLon, z: 9)
        let image = try SurfaceRaster.render(frame, level: level, size: 32) { tile in
            tile == center ? [UInt32](repeating: 0xFF112233, count: 64) : nil
        }
        XCTAssertEqual(image[16 * 32 + 16], 0xFF112233)
        XCTAssertTrue(image.contains(0))
        // データの無いタイルとの境目は透明度だけが変わり、色は混ざらない。
        XCTAssertTrue(image.allSatisfy { $0 == 0 || $0 & 0xFFFFFF == 0x112233 })
        // 大きさの合わないタイルは受け取らない。
        XCTAssertThrowsError(try SurfaceRaster.render(frame, level: level, size: 32) { _ in [UInt32](repeating: 0, count: 10) })
    }

    func testBlendMixesByWeightAndIgnoresTransparentColors() {
        let red: UInt32 = 0xFFFF0000
        let blue: UInt32 = 0xFF0000FF
        XCTAssertEqual(SurfaceRaster.blend(red, red, red, red, 100, 200), red)
        XCTAssertEqual(SurfaceRaster.blend(red, blue, red, blue, 0, 0), red)
        XCTAssertEqual(SurfaceRaster.blend(red, blue, red, blue, 128, 77), 0xFF800080)
        XCTAssertEqual(SurfaceRaster.blend(red, red, blue, blue, 31, 128), 0xFF800080)
        // 透明な画素の色(黒)は混ざらず、透明度だけが下がる。
        XCTAssertEqual(SurfaceRaster.blend(red, 0, red, 0, 128, 0), 0x80FF0000)
        XCTAssertEqual(SurfaceRaster.blend(0, 0, 0, 0x00FFFFFF, 128, 128), 0)
    }
}
