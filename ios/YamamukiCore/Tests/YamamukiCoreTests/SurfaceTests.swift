// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import XCTest
@testable import YamamukiCore

final class SurfaceTests: XCTestCase {
    private let fujiLat = 35.3606
    private let fujiLon = 138.7274

    /// ズーム 5〜9 を収録した版の段。タイルの一辺はどれも 512 画素。
    private let z9Levels = (5...9).map { SurfaceLevel(z: $0, tilePixels: 512) }

    /// yamamuki-data の全国データに入っている既定の色。
    private let palette = SurfacePalette(ground: 0xE0D8C4, forest: 0x6F9458, water: 0x72ACCC)
    private let shade = SurfaceShade(neutral: 136, strength: 0.65)

    private func compose(_ tilePixels: Int, _ forest: [UInt8]?, _ hillshade: [UInt8]?, _ water: [UInt8]?, shade: SurfaceShade? = SurfaceShade(neutral: 136, strength: 0.65)) throws -> [UInt32] {
        try SurfaceCompositor.compose(tilePixels: tilePixels, forest: forest, hillshade: hillshade, water: water, palette: palette, shade: shade)
    }

    // MARK: - タイルの座標

    func testTileAtFuji() {
        // yamamuki-data の全国データに収録されているタイル 10/906/404 が富士山を含む。
        XCTAssertEqual(SurfaceTiles.tileAt(fujiLat, fujiLon, z: 10), SurfaceTile(z: 10, x: 906, y: 404))
        XCTAssertEqual(SurfaceTiles.tileAt(fujiLat, fujiLon, z: 9), SurfaceTile(z: 9, x: 453, y: 202))
    }

    func testTileBoundsContainThePoint() {
        let tile = SurfaceTiles.tileAt(fujiLat, fujiLon, z: 9)
        let box = tile.bounds
        XCTAssertTrue(box.contains(fujiLat, fujiLon))
        XCTAssertTrue(box.north > box.south && box.east > box.west)
        XCTAssertEqual(SurfaceTiles.tileAt((box.north + box.south) / 2, (box.west + box.east) / 2, z: 9), tile)
        // 隣のタイルと辺を共有する。
        XCTAssertEqual(SurfaceTile(z: 9, x: 454, y: 202).bounds.west, box.east, accuracy: 1e-9)
        XCTAssertEqual(SurfaceTile(z: 9, x: 453, y: 203).bounds.north, box.south, accuracy: 1e-9)
    }

    func testWorldPositionRoundTrips() {
        XCTAssertEqual(SurfaceTiles.latitude(SurfaceTiles.worldV(fujiLat)), fujiLat, accuracy: 1e-9)
        XCTAssertEqual(SurfaceTiles.longitude(SurfaceTiles.worldU(fujiLon)), fujiLon, accuracy: 1e-9)
    }

    func testTileAtClampsToTheWorldEdge() {
        XCTAssertEqual(SurfaceTiles.tileAt(90, 0, z: 3), SurfaceTile(z: 3, x: 4, y: 0))
        XCTAssertEqual(SurfaceTiles.tileAt(-90, 0, z: 3), SurfaceTile(z: 3, x: 4, y: 7))
        XCTAssertEqual(SurfaceTiles.tileAt(10, 180, z: 3), SurfaceTile(z: 3, x: 7, y: 3))
        XCTAssertEqual(SurfaceTiles.tileAt(10, -180, z: 3), SurfaceTile(z: 3, x: 0, y: 3))
    }

    func testCoveringListsTilesAroundThePoint() {
        // 富士山から半径 20km。ズーム 9 のタイルは東西 63km ほどなので、多くても 4 枚。
        let tiles = SurfaceTiles.covering(BoundingBox.around(fujiLat, fujiLon, radiusKm: 20), z: 9)
        XCTAssertTrue(tiles.contains(SurfaceTile(z: 9, x: 453, y: 202)))
        XCTAssertTrue((1...4).contains(tiles.count))
        // 半径 120km では枚数が増え、北西から行ごとに東へ並ぶ。
        let wide = SurfaceTiles.covering(BoundingBox.around(fujiLat, fujiLon, radiusKm: 120), z: 9)
        XCTAssertGreaterThan(wide.count, tiles.count)
        XCTAssertEqual(wide.sorted { ($0.y, $0.x) < ($1.y, $1.x) }, wide)
        XCTAssertEqual(Set(wide).count, wide.count)
    }

    // MARK: - ズームの選び方

    func testMetersPerPixelAtJapan() {
        // 北緯 36° の緯線は約 32,400km。ズーム 9・512 画素なら 1 画素は約 124m、ズーム 10・384 画素なら約 82m。
        XCTAssertEqual(SurfaceTiles.metersPerPixel(SurfaceLevel(z: 9, tilePixels: 512), lat: 36), 123.6, accuracy: 0.5)
        XCTAssertEqual(SurfaceTiles.metersPerPixel(SurfaceLevel(z: 10, tilePixels: 384), lat: 36), 82.4, accuracy: 0.5)
    }

    func testChooseLevelPicksTheFinestLevelNotFinerThanTheScreen() {
        func z(_ metersPerScreenPixel: Double) -> Int? {
            SurfaceTiles.chooseLevel(z9Levels, lat: 36, metersPerScreenPixel: metersPerScreenPixel)?.z
        }
        XCTAssertEqual(z(100), 9)
        // 画面の 1 画素が 1 画素より少しでも粗ければ、1 段粗いズームにする。
        XCTAssertEqual(z(130), 8)
        XCTAssertEqual(z(300), 7)
        // 収録したどの段より細かく表示するときは、最も細かい段。
        XCTAssertEqual(z(3), 9)
        // どの段より粗く表示するときは、最も粗い段。
        XCTAssertEqual(z(1_000_000), 5)
    }

    func testChooseLevelHandlesOddInput() {
        XCTAssertNil(SurfaceTiles.chooseLevel([], lat: 36, metersPerScreenPixel: 100))
        XCTAssertEqual(SurfaceTiles.chooseLevel(z9Levels, lat: 36, metersPerScreenPixel: 0)?.z, 9)
        XCTAssertEqual(SurfaceTiles.chooseLevel(z9Levels, lat: 36, metersPerScreenPixel: .nan)?.z, 9)
        // 段の並びによらない。
        XCTAssertEqual(SurfaceTiles.chooseLevel(z9Levels.reversed(), lat: 36, metersPerScreenPixel: 130)?.z, 8)
    }

    // MARK: - レイヤーの種類と設定

    func testLayerKindNeedsTheReservedIdAndRole() {
        XCTAssertEqual(SurfaceLayerKind.of(id: "forest", role: "mask"), .forest)
        XCTAssertEqual(SurfaceLayerKind.of(id: "water", role: "mask"), .water)
        XCTAssertEqual(SurfaceLayerKind.of(id: "hillshade", role: "hillshade"), .hillshade)
        XCTAssertNil(SurfaceLayerKind.of(id: "forest", role: "rgb"))
        XCTAssertNil(SurfaceLayerKind.of(id: "Forest", role: "mask"))
        XCTAssertNil(SurfaceLayerKind.of(id: "coverage", role: "mask"))
        XCTAssertNil(SurfaceLayerKind.of(id: nil, role: nil))
    }

    func testShadeSettingsAreParsed() {
        XCTAssertEqual(SurfaceShade.parse(#"{"neutral":136,"strength":0.65}"#), SurfaceShade(neutral: 136, strength: 0.65))
        XCTAssertEqual(SurfaceShade.parse(#"{"strength":1,"neutral":128,"other":true}"#), SurfaceShade(neutral: 128, strength: 1))
        XCTAssertNil(SurfaceShade.parse(#"{"neutral":0,"strength":0.65}"#))
        XCTAssertNil(SurfaceShade.parse(#"{"neutral":256,"strength":0.65}"#))
        XCTAssertNil(SurfaceShade.parse(#"{"neutral":136}"#))
        XCTAssertNil(SurfaceShade.parse(#"{"neutral":136,"strength":-1}"#))
        XCTAssertNil(SurfaceShade.parse("[136,0.65]"))
        XCTAssertNil(SurfaceShade.parse("こわれた JSON"))
    }

    // MARK: - 合成

    func testComposeMixesGroundAndForest() throws {
        // 森林 0 は地面の色、255 は森林の色、128 はその間。4 画素目は 1 画素目と同じ。
        XCTAssertEqual(
            try compose(2, [0, 255, 128, 0], [136, 136, 136, 136], [0, 0, 0, 0]),
            [0xFFE0D8C4, 0xFF6F9458, 0xFFA7B68E, 0xFFE0D8C4]
        )
    }

    func testComposeShadesTheGroundButNotTheWater() throws {
        XCTAssertEqual(
            try compose(2, [0, 0, 0, 0], [68, 255, 68, 136], [0, 0, 255, 255]),
            [
                // 陰影 68 は 0.675 倍に暗くする。
                0xFF979284,
                // 陰影 255 は 1.57 倍に明るくし、255 で止める。
                0xFFFFFFFF,
                // 水域には陰影を掛けない。
                0xFF72ACCC,
                0xFF72ACCC,
            ]
        )
    }

    func testComposeWithoutHillshadeKeepsTheBrightness() throws {
        let expected: [UInt32] = [0xFFE0D8C4]
        XCTAssertEqual(try compose(1, [0], nil, [0]), expected)
        XCTAssertEqual(try compose(1, [0], [68], [0], shade: nil), expected)
        XCTAssertEqual(try compose(1, [0], nil, nil, shade: nil), expected)
    }

    func testComposeWithoutForestDrawsOnlyWater() throws {
        // 森林のタイルが無い場所は地面を塗らない。水域は濃さを透明度にして描く。
        XCTAssertEqual(try compose(2, nil, [68, 68, 68, 68], [0, 128, 255, 1]), [0, 0x8072ACCC, 0xFF72ACCC, 0x0172ACCC])
        XCTAssertEqual(try compose(2, nil, nil, nil), [0, 0, 0, 0])
    }

    func testComposeRejectsWrongSizes() {
        XCTAssertThrowsError(try compose(2, [0, 0, 0], nil, nil))
        XCTAssertThrowsError(try compose(2, nil, [0], nil))
        XCTAssertThrowsError(try compose(2, nil, nil, [0, 0, 0, 0, 0]))
        XCTAssertThrowsError(try compose(0, nil, nil, nil))
    }

    // MARK: - manifest

    /// yamamuki-data の Release(esa-worldcover-20261112T164326Z-43261736372-1)の manifest から、読む項目を抜き出したもの。
    private func manifest(
        schemaVersion: Int = 6,
        dataSchemaFormat: String = "yamamuki-sqlite",
        dataSchemaVersion: String = "1",
        fileName: String = "esa-worldcover-z9.sqlite3",
        datasets: String = #"[{"dataVersion":"20261112T164326Z-43261736372-1","formatVersion":1,"id":"esa-worldcover-japan","kind":"surface"}]"#,
        downloadUrl: String = "https://github.com/shohei0205/yamamuki-data/releases/download/esa-worldcover-20261112T164326Z-43261736372-1/esa-worldcover-z9.sqlite3",
        sha256: String = "457D6881fcd48010a22908484b80da6c054fca35573184a3d940e2bbf4532ea0",
        sizeBytes: Int = 17_572_352,
        extra: String = ""
    ) -> Data {
        Data("""
        {
          "dataSchemaFormat": "\(dataSchemaFormat)",
          "dataSchemaVersion": \(dataSchemaVersion),
          "datasets": \(datasets),
          "downloadUrl": "\(downloadUrl)",
          "fileName": "\(fileName)",
          "name": "日本全国の地表（ESA WorldCover 2021）",
          "schemaFormat": "yamamuki-manifest",
          "schemaVersion": \(schemaVersion),
          "sha256": "\(sha256)",
          "sizeBytes": \(sizeBytes),
          "uncompressedSizeBytes": \(sizeBytes),
          \(extra)
          "version": "20261112T164326Z-43261736372-1"
        }
        """.utf8)
    }

    private func parse(_ body: Data) throws -> SurfaceManifest {
        try SurfaceData.parseManifest(body, dataset: .esaWorldcoverZ9)
    }

    func testManifestIsParsed() throws {
        let m = try parse(manifest())
        XCTAssertEqual(m.version, "20261112T164326Z-43261736372-1")
        XCTAssertEqual(m.releaseDate, "2026/11/12")
        XCTAssertTrue(m.downloadUrl.hasSuffix("/esa-worldcover-z9.sqlite3"))
        XCTAssertEqual(m.sha256, "457d6881fcd48010a22908484b80da6c054fca35573184a3d940e2bbf4532ea0")
        XCTAssertEqual(m.sizeBytes, 17_572_352)
        XCTAssertEqual(m.datasetId, "esa-worldcover-japan")
        XCTAssertFalse(m.experimental)
        XCTAssertTrue(try parse(manifest(extra: #""experimental": true,"#)).experimental)
    }

    func testManifestOfAnotherFormatIsRejected() {
        func rejects(_ body: Data, line: UInt = #line) {
            XCTAssertThrowsError(try parse(body), line: line) { XCTAssertTrue($0 is SurfaceDataError, line: line) }
        }
        rejects(Data("こわれた JSON".utf8))
        rejects(Data("[]".utf8))
        rejects(manifest(schemaVersion: 5))
        rejects(manifest(schemaVersion: 7))
        // 山データの manifest。
        rejects(manifest(dataSchemaFormat: "yamamuki-points"))
        rejects(manifest(dataSchemaVersion: "2"))
        rejects(manifest(dataSchemaVersion: "null"))
        // 収録ズームの違うファイル。
        rejects(manifest(fileName: "esa-worldcover-z10.sqlite3"))
        rejects(manifest(datasets: "[]"))
        rejects(manifest(datasets: #"[{"formatVersion":1,"id":"osm-peaks","kind":"points"}]"#))
        rejects(manifest(datasets: #"[{"formatVersion":2,"id":"esa-worldcover-japan","kind":"surface"}]"#))
        rejects(manifest(datasets: #"[{"formatVersion":1,"kind":"surface"}]"#))
        rejects(manifest(datasets: #"[{"formatVersion":1,"id":"a","kind":"surface"},{"formatVersion":1,"id":"b","kind":"surface"}]"#))
        rejects(manifest(downloadUrl: "http://example.com/esa-worldcover-z9.sqlite3"))
        rejects(manifest(sha256: "457d"))
        rejects(manifest(sizeBytes: 0))
        rejects(manifest(sizeBytes: SurfaceData.maxSizeBytes + 1))
    }

    func testDatasetIsFoundById() {
        XCTAssertEqual(SurfaceDataset(rawValue: "esa-worldcover-z9"), .esaWorldcoverZ9)
        XCTAssertEqual(SurfaceDataset.esaWorldcoverZ9.fileName, "esa-worldcover-z9.sqlite3")
        XCTAssertNil(SurfaceDataset(rawValue: "osm-peaks"))
    }
}
