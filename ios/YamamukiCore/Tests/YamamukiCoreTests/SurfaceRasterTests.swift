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

    /// 森林の濃さがそのまま灰色の明るさになる配色(地面は黒、森林は白)。水域は青。
    private let gray = SurfacePalette(ground: 0x000000, forest: 0xFFFFFF, water: 0x0000FF)

    /// 森林の濃さだけを持つタイル。
    private func forestTile(_ pixels: Int, _ value: (Int) -> Int) -> SurfaceRaster.TileLayers {
        let forest: [UInt8] = (0..<(pixels * pixels)).map { UInt8(value($0)) }
        return SurfaceRaster.TileLayers(forest: forest, hillshade: nil, water: nil)
    }

    /// タイルごとに違う濃さ(20〜219)。
    private func valueOf(_ tile: SurfaceTile) -> Int {
        let mixed = tile.x * 7 + tile.y * 13
        return 20 + mixed % 200
    }

    private func grayOf(_ value: Int) -> UInt32 {
        // 古い Xcode では 1 つの式にすると型を決めきれないので、成分ごとに分ける。
        let v = UInt32(value)
        let opaque: UInt32 = 0xFF00_0000
        return opaque | (v << 16) | (v << 8) | v
    }

    func testRenderPutsEachTileWhereItsPlaceIs() throws {
        let level = SurfaceLevel(z: 9, tilePixels: 16)
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 80)
        let size = 128
        var requested: [SurfaceTile] = []
        let image = try SurfaceRaster.render(frame, level: level, palette: gray, shade: nil, size: size, sharpen: false) { tile in
            requested.append(tile)
            return self.forestTile(16) { _ in self.valueOf(tile) }
        }
        // 1 枚のタイルにつき 1 回だけ読む。
        XCTAssertEqual(Set(requested).count, requested.count)
        XCTAssertTrue(Set(requested).isSubset(of: Set(SurfaceRaster.tiles(frame, z: 9))))
        // タイルの中央は、そのタイルの濃さになる。
        var checked = 0
        for tile in SurfaceRaster.tiles(frame, z: 9) {
            let box = tile.bounds
            let p = pixelOf(frame, size, (box.north + box.south) / 2, (box.west + box.east) / 2)
            guard (0..<size).contains(p.i), (0..<size).contains(p.j) else { continue }
            XCTAssertEqual(image[p.j * size + p.i], grayOf(valueOf(tile)), "tile \(tile)")
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
        // 右へ行くほど濃くなるタイル(列 × 10)。画像を元の画素より細かく作ると、間の値で埋まる。
        let level = SurfaceLevel(z: 9, tilePixels: 16)
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 8)
        let size = 96
        let tile = SurfaceTiles.tileAt(fujiLat, fujiLon, z: 9)
        let gradient = forestTile(16) { $0 % 16 * 10 }
        let image = try SurfaceRaster.render(frame, level: level, palette: gray, shade: nil, size: size, sharpen: false) { _ in gradient }
        var values = Set<Int>()
        for i in 0..<size {
            let east = (Double(i) + 0.5) / Double(size) * 16 - 8
            let p = GeoMath.destination(fujiLat, fujiLon, distanceKm: abs(east), bearingDeg: east >= 0 ? 90 : 270)
            guard SurfaceTiles.tileAt(p.lat, p.lon, z: 9) == tile else { continue }
            let column = (SurfaceTiles.worldU(p.lon) * 512 - Double(tile.x)) * 16 - 0.5
            guard column >= 0.5, column <= 14.5 else { continue }
            let value = image[size / 2 * size + i]
            XCTAssertEqual(Double(value & 0xFF), column * 10, accuracy: 2, "列 \(i)")
            XCTAssertEqual(value >> 24, 0xFF)
            values.insert(Int(value & 0xFF))
        }
        // 元の 16 段より細かい値が出ている。
        XCTAssertGreaterThan(values.count, 20)
    }

    func testRenderLeavesMissingTilesTransparent() throws {
        let level = SurfaceLevel(z: 9, tilePixels: 8)
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 40)
        let center = SurfaceTiles.tileAt(fujiLat, fujiLon, z: 9)
        let palette = SurfacePalette(ground: 0x445566, forest: 0x112233, water: 0x0000FF)
        let image = try SurfaceRaster.render(frame, level: level, palette: palette, shade: nil, size: 32) { tile in
            tile == center ? self.forestTile(8) { _ in 255 } : nil
        }
        XCTAssertEqual(image[16 * 32 + 16], 0xFF112233)
        XCTAssertTrue(image.contains(0))
        // データの無いタイルとの境目は透明度だけが変わり、色は混ざらない。
        XCTAssertTrue(image.allSatisfy { $0 == 0 || $0 & 0xFFFFFF == 0x112233 })
        // 大きさの合わないタイルは受け取らない。
        XCTAssertThrowsError(try SurfaceRaster.render(frame, level: level, palette: palette, shade: nil, size: 32) { _ in
            SurfaceRaster.TileLayers(forest: [UInt8](repeating: 0, count: 10), hillshade: nil, water: nil)
        })
    }

    func testSharpeningNarrowsTheEdgeOfAMagnifiedMask() throws {
        // 左半分が森林、右半分が森林なしのタイル。元の 1 画素が画像の十画素ほどに拡大される。
        let level = SurfaceLevel(z: 9, tilePixels: 16)
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 40)
        let size = 192
        let edge = forestTile(16) { $0 % 16 < 8 ? 255 : 0 }
        let soft = try SurfaceRaster.render(frame, level: level, palette: gray, shade: nil, size: size, sharpen: false) { _ in edge }
        let sharp = try SurfaceRaster.render(frame, level: level, palette: gray, shade: nil, size: size, sharpen: true) { _ in edge }
        func middle(_ image: [UInt32]) -> Int {
            (0..<size).filter { (30...225).contains(Int(image[size / 2 * size + $0] & 0xFF)) }.count
        }
        XCTAssertGreaterThanOrEqual(middle(soft), 8)
        XCTAssertLessThan(middle(sharp) * 3, middle(soft))
        // 森林と森林なしの面積はほぼ変わらない。
        func forestShare(_ image: [UInt32]) -> Int { image.filter { ($0 & 0xFF) >= 128 }.count }
        XCTAssertEqual(Double(forestShare(soft)), Double(forestShare(sharp)), accuracy: Double(size * size) * 0.02)
        // 拡大しないとき(画像のほうが粗いとき)は補間のまま。
        let coarse = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 4000)
        XCTAssertEqual(SurfaceRaster.magnification(coarse, level: level, size: size), 1.0)
        XCTAssertGreaterThan(SurfaceRaster.magnification(frame, level: level, size: size), 4.0)
    }

    func testSharpeningKeepsAThinWater() throws {
        // 1 画素だけの細い水域(濃さ 100)。補間のままだと薄い線になるが、鋭くすると消えずにはっきり残る。
        let level = SurfaceLevel(z: 9, tilePixels: 16)
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 40)
        let size = 192
        let forest = [UInt8](repeating: 0, count: 256)
        let water: [UInt8] = (0..<256).map { $0 % 16 == 8 ? 100 : 0 }
        let river = SurfaceRaster.TileLayers(forest: forest, hillshade: nil, water: water)
        func maxBlue(_ sharpen: Bool) throws -> UInt32 {
            let image = try SurfaceRaster.render(frame, level: level, palette: gray, shade: nil, size: size, sharpen: sharpen) { _ in river }
            return image.map { $0 & 0xFF }.max() ?? 0
        }
        XCTAssertLessThanOrEqual(try maxBlue(false), 110)
        XCTAssertGreaterThanOrEqual(try maxBlue(true), 200)
    }

    func testNoWaterStaysClearAtAnyMagnification() throws {
        // 水域が無い(濃さ 0)場所と、ごく薄い場所(濃さ 8)は、拡大の倍率が小さくても青くならない。
        let level = SurfaceLevel(z: 9, tilePixels: 16)
        let size = 192
        for radius in [30.0, 80.0, 150.0, 250.0, 400.0] {
            let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: radius)
            for faint in [0, 8] {
                let water = [UInt8](repeating: UInt8(faint), count: 256)
                let tile = SurfaceRaster.TileLayers(forest: [UInt8](repeating: 0, count: 256), hillshade: nil, water: water)
                let image = try SurfaceRaster.render(frame, level: level, palette: gray, shade: nil, size: size, sharpen: true) { _ in tile }
                XCTAssertTrue(image.allSatisfy { $0 & 0xFF <= 8 }, "半径 \(radius) km、濃さ \(faint) で青い画素がある")
            }
        }
    }

    func testSharpeningKeepsAFaintRiverVisible() throws {
        // データの中で薄く入っている川(濃さ 50、約 0.2)も、補間のままより濃く、途切れずに見える。
        let level = SurfaceLevel(z: 9, tilePixels: 16)
        let frame = SurfaceFrame(centerLat: fujiLat, centerLon: fujiLon, radiusKm: 40)
        let size = 192
        let forest = [UInt8](repeating: 0, count: 256)
        let water: [UInt8] = (0..<256).map { $0 % 16 == 8 ? 50 : 0 }
        let river = SurfaceRaster.TileLayers(forest: forest, hillshade: nil, water: water)
        func maxBlue(_ sharpen: Bool) throws -> UInt32 {
            let image = try SurfaceRaster.render(frame, level: level, palette: gray, shade: nil, size: size, sharpen: sharpen) { _ in river }
            return image.map { $0 & 0xFF }.max() ?? 0
        }
        XCTAssertLessThanOrEqual(try maxBlue(false), 60)
        XCTAssertGreaterThanOrEqual(try maxBlue(true), 120)
    }
}
