// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurfaceTest {
    private val fujiLat = 35.3606
    private val fujiLon = 138.7274

    /** ズーム 5〜9 を収録した版の段。タイルの一辺はどれも 512 画素。 */
    private val z9Levels = (5..9).map { SurfaceLevel(it, 512) }

    /** yamamuki-data の全国データに入っている既定の色。 */
    private val palette = SurfacePalette(ground = 0xE0D8C4, forest = 0x6F9458, water = 0x72ACCC)
    private val shade = SurfaceShade(neutral = 136, strength = 0.65)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // region タイルの座標

    @Test
    fun tileAtFuji() {
        // yamamuki-data の全国データに収録されているタイル 10/906/404 が富士山を含む。
        assertEquals(SurfaceTile(10, 906, 404), SurfaceTiles.tileAt(fujiLat, fujiLon, 10))
        assertEquals(SurfaceTile(9, 453, 202), SurfaceTiles.tileAt(fujiLat, fujiLon, 9))
    }

    @Test
    fun tileBoundsContainThePoint() {
        val tile = SurfaceTiles.tileAt(fujiLat, fujiLon, 9)
        val box = tile.bounds
        assertTrue(box.contains(fujiLat, fujiLon))
        assertTrue(box.north > box.south && box.east > box.west)
        assertEquals(tile, SurfaceTiles.tileAt((box.north + box.south) / 2, (box.west + box.east) / 2, 9))
        // 隣のタイルと辺を共有する。
        assertEquals(box.east, SurfaceTile(9, 454, 202).bounds.west, 1e-9)
        assertEquals(box.south, SurfaceTile(9, 453, 203).bounds.north, 1e-9)
    }

    @Test
    fun worldPositionRoundTrips() {
        assertEquals(fujiLat, SurfaceTiles.latitude(SurfaceTiles.worldV(fujiLat)), 1e-9)
        assertEquals(fujiLon, SurfaceTiles.longitude(SurfaceTiles.worldU(fujiLon)), 1e-9)
    }

    @Test
    fun tileAtClampsToTheWorldEdge() {
        assertEquals(SurfaceTile(3, 4, 0), SurfaceTiles.tileAt(90.0, 0.0, 3))
        assertEquals(SurfaceTile(3, 4, 7), SurfaceTiles.tileAt(-90.0, 0.0, 3))
        assertEquals(SurfaceTile(3, 7, 3), SurfaceTiles.tileAt(10.0, 180.0, 3))
        assertEquals(SurfaceTile(3, 0, 3), SurfaceTiles.tileAt(10.0, -180.0, 3))
    }

    @Test
    fun coveringListsTilesAroundThePoint() {
        // 富士山から半径 20km。ズーム 9 のタイルは東西 63km ほどなので、多くても 4 枚。
        val tiles = SurfaceTiles.covering(BoundingBox.around(fujiLat, fujiLon, 20.0), 9)
        assertTrue(SurfaceTile(9, 453, 202) in tiles)
        assertTrue(tiles.size in 1..4)
        // 半径 120km では枚数が増え、北西から行ごとに東へ並ぶ。
        val wide = SurfaceTiles.covering(BoundingBox.around(fujiLat, fujiLon, 120.0), 9)
        assertTrue(wide.size > tiles.size)
        assertEquals(wide.sortedWith(compareBy({ it.y }, { it.x })), wide)
        assertEquals(wide.size, wide.toSet().size)
    }

    // endregion

    // region ズームの選び方

    @Test
    fun metersPerPixelAtJapan() {
        // 北緯 36° の緯線は約 32,400km。ズーム 9・512 画素なら 1 画素は約 124m、ズーム 10・384 画素なら約 82m。
        assertEquals(123.6, SurfaceTiles.metersPerPixel(SurfaceLevel(9, 512), 36.0), 0.5)
        assertEquals(82.4, SurfaceTiles.metersPerPixel(SurfaceLevel(10, 384), 36.0), 0.5)
    }

    @Test
    fun chooseLevelPicksTheFinestLevelNotFinerThanTheScreen() {
        fun z(metersPerScreenPixel: Double) = SurfaceTiles.chooseLevel(z9Levels, 36.0, metersPerScreenPixel)?.z
        assertEquals(9, z(100.0))
        // 画面の 1 画素が 1 画素より少しでも粗ければ、1 段粗いズームにする。
        assertEquals(8, z(130.0))
        assertEquals(7, z(300.0))
        // 収録したどの段より細かく表示するときは、最も細かい段。
        assertEquals(9, z(3.0))
        // どの段より粗く表示するときは、最も粗い段。
        assertEquals(5, z(1_000_000.0))
    }

    @Test
    fun chooseLevelHandlesOddInput() {
        assertNull(SurfaceTiles.chooseLevel(emptyList(), 36.0, 100.0))
        assertEquals(9, SurfaceTiles.chooseLevel(z9Levels, 36.0, 0.0)?.z)
        assertEquals(9, SurfaceTiles.chooseLevel(z9Levels, 36.0, Double.NaN)?.z)
        // 段の並びによらない。
        assertEquals(8, SurfaceTiles.chooseLevel(z9Levels.reversed(), 36.0, 130.0)?.z)
    }

    // endregion

    // region レイヤーの種類と設定

    @Test
    fun layerKindNeedsTheReservedIdAndRole() {
        assertEquals(SurfaceLayerKind.FOREST, SurfaceLayerKind.of("forest", "mask"))
        assertEquals(SurfaceLayerKind.WATER, SurfaceLayerKind.of("water", "mask"))
        assertEquals(SurfaceLayerKind.HILLSHADE, SurfaceLayerKind.of("hillshade", "hillshade"))
        assertNull(SurfaceLayerKind.of("forest", "rgb"))
        assertNull(SurfaceLayerKind.of("Forest", "mask"))
        assertNull(SurfaceLayerKind.of("coverage", "mask"))
        assertNull(SurfaceLayerKind.of(null, null))
    }

    @Test
    fun shadeSettingsAreParsed() {
        assertEquals(SurfaceShade(136, 0.65), SurfaceShade.parse("""{"neutral":136,"strength":0.65}"""))
        assertEquals(SurfaceShade(128, 1.0), SurfaceShade.parse("""{"strength":1,"neutral":128,"other":true}"""))
        assertNull(SurfaceShade.parse("""{"neutral":0,"strength":0.65}"""))
        assertNull(SurfaceShade.parse("""{"neutral":256,"strength":0.65}"""))
        assertNull(SurfaceShade.parse("""{"neutral":136}"""))
        assertNull(SurfaceShade.parse("""{"neutral":136,"strength":-1}"""))
        assertNull(SurfaceShade.parse("""[136,0.65]"""))
        assertNull(SurfaceShade.parse("こわれた JSON"))
    }

    // endregion

    // region 合成

    @Test
    fun composeMixesGroundAndForest() {
        val out = SurfaceCompositor.compose(2, bytes(0, 255, 128, 0), bytes(136, 136, 136, 136), bytes(0, 0, 0, 0), palette, shade)
        // 森林 0 は地面の色、255 は森林の色、128 はその間。4 画素目は 1 画素目と同じ。
        assertContentEquals(intArrayOf(0xFFE0D8C4.toInt(), 0xFF6F9458.toInt(), 0xFFA7B68E.toInt(), 0xFFE0D8C4.toInt()), out)
    }

    @Test
    fun composeShadesTheGroundButNotTheWater() {
        val out = SurfaceCompositor.compose(2, bytes(0, 0, 0, 0), bytes(68, 255, 68, 136), bytes(0, 0, 255, 255), palette, shade)
        assertContentEquals(
            intArrayOf(
                // 陰影 68 は 0.675 倍に暗くする。
                0xFF979284.toInt(),
                // 陰影 255 は 1.57 倍に明るくし、255 で止める。
                0xFFFFFFFF.toInt(),
                // 水域には陰影を掛けない。
                0xFF72ACCC.toInt(),
                0xFF72ACCC.toInt(),
            ),
            out,
        )
    }

    @Test
    fun composeWithoutHillshadeKeepsTheBrightness() {
        val expected = intArrayOf(0xFFE0D8C4.toInt())
        assertContentEquals(expected, SurfaceCompositor.compose(1, bytes(0), null, bytes(0), palette, shade))
        assertContentEquals(expected, SurfaceCompositor.compose(1, bytes(0), bytes(68), bytes(0), palette, null))
        assertContentEquals(expected, SurfaceCompositor.compose(1, bytes(0), null, null, palette, null))
    }

    @Test
    fun composeWithoutForestDrawsOnlyWater() {
        val out = SurfaceCompositor.compose(2, null, bytes(68, 68, 68, 68), bytes(0, 128, 255, 1), palette, shade)
        // 森林のタイルが無い場所は地面を塗らない。水域は濃さを透明度にして描く。
        assertContentEquals(intArrayOf(0, 0x8072ACCC.toInt(), 0xFF72ACCC.toInt(), 0x0172ACCC), out)
        assertContentEquals(IntArray(4), SurfaceCompositor.compose(2, null, null, null, palette, shade))
    }

    @Test
    fun composeRejectsWrongSizes() {
        assertFailsWith<IllegalArgumentException> { SurfaceCompositor.compose(2, bytes(0, 0, 0), null, null, palette, shade) }
        assertFailsWith<IllegalArgumentException> { SurfaceCompositor.compose(2, null, bytes(0), null, palette, shade) }
        assertFailsWith<IllegalArgumentException> { SurfaceCompositor.compose(2, null, null, ByteArray(5), palette, shade) }
        assertFailsWith<IllegalArgumentException> { SurfaceCompositor.compose(0, null, null, null, palette, shade) }
    }

    // endregion

    // region manifest

    /** yamamuki-data の Release(esa-worldcover-20261112T164326Z-43261736372-1)の manifest から、読む項目を抜き出したもの。 */
    private fun manifest(
        schemaVersion: Int = 6,
        dataSchemaFormat: String = "yamamuki-sqlite",
        dataSchemaVersion: String = "1",
        fileName: String = "esa-worldcover-z9.sqlite3",
        datasets: String = """[{"dataVersion":"20261112T164326Z-43261736372-1","formatVersion":1,"id":"esa-worldcover-japan","kind":"surface"}]""",
        downloadUrl: String = "https://github.com/shohei0205/yamamuki-data/releases/download/esa-worldcover-20261112T164326Z-43261736372-1/esa-worldcover-z9.sqlite3",
        sha256: String = "457D6881fcd48010a22908484b80da6c054fca35573184a3d940e2bbf4532ea0",
        sizeBytes: Long = 17572352,
        extra: String = "",
    ) = """
        {
          "dataSchemaFormat": "$dataSchemaFormat",
          "dataSchemaVersion": $dataSchemaVersion,
          "datasets": $datasets,
          "downloadUrl": "$downloadUrl",
          "fileName": "$fileName",
          "name": "日本全国の地表（ESA WorldCover 2021）",
          "schemaFormat": "yamamuki-manifest",
          "schemaVersion": $schemaVersion,
          "sha256": "$sha256",
          "sizeBytes": $sizeBytes,
          "uncompressedSizeBytes": $sizeBytes,
          $extra
          "version": "20261112T164326Z-43261736372-1"
        }
    """.trimIndent()

    private fun parse(body: String) = SurfaceData.parseManifest(body, SurfaceDataset.ESA_WORLDCOVER_Z9)

    @Test
    fun manifestIsParsed() {
        val m = parse(manifest())
        assertEquals("20261112T164326Z-43261736372-1", m.version)
        assertEquals("2026/11/12", m.releaseDate)
        assertTrue(m.downloadUrl.endsWith("/esa-worldcover-z9.sqlite3"))
        assertEquals("457d6881fcd48010a22908484b80da6c054fca35573184a3d940e2bbf4532ea0", m.sha256)
        assertEquals(17572352L, m.sizeBytes)
        assertEquals("esa-worldcover-japan", m.datasetId)
        assertEquals(false, m.experimental)
        assertEquals(true, parse(manifest(extra = """"experimental": true,""")).experimental)
    }

    @Test
    fun manifestOfAnotherFormatIsRejected() {
        fun rejects(body: String) {
            assertFailsWith<SurfaceDataException> { parse(body) }
        }
        rejects("こわれた JSON")
        rejects("[]")
        rejects(manifest(schemaVersion = 5))
        rejects(manifest(schemaVersion = 7))
        // 山データの manifest。
        rejects(manifest(dataSchemaFormat = "yamamuki-points"))
        rejects(manifest(dataSchemaVersion = "2"))
        rejects(manifest(dataSchemaVersion = "null"))
        // 収録ズームの違うファイル。
        rejects(manifest(fileName = "esa-worldcover-z10.sqlite3"))
        rejects(manifest(datasets = "[]"))
        rejects(manifest(datasets = """[{"formatVersion":1,"id":"osm-peaks","kind":"points"}]"""))
        rejects(manifest(datasets = """[{"formatVersion":2,"id":"esa-worldcover-japan","kind":"surface"}]"""))
        rejects(manifest(datasets = """[{"formatVersion":1,"kind":"surface"}]"""))
        rejects(manifest(datasets = """[{"formatVersion":1,"id":"a","kind":"surface"},{"formatVersion":1,"id":"b","kind":"surface"}]"""))
        rejects(manifest(downloadUrl = "http://example.com/esa-worldcover-z9.sqlite3"))
        rejects(manifest(sha256 = "457d"))
        rejects(manifest(sizeBytes = 0))
        rejects(manifest(sizeBytes = SurfaceData.MAX_SIZE_BYTES + 1))
    }

    @Test
    fun containerMustHoldTheSurfaceDataset() {
        val m = parse(manifest())
        fun info(applicationId: Long = 0x594D4442, containerVersion: Long = 1, levelCount: Long = 5) =
            SurfaceContainerInfo(applicationId, containerVersion, levelCount)
        SurfaceData.requireContainer(info(), m)
        // SQLite として読めなかった。
        assertFailsWith<SurfaceDataException> { SurfaceData.requireContainer(null, m) }
        assertFailsWith<SurfaceDataException> { SurfaceData.requireContainer(info(applicationId = 0), m) }
        assertFailsWith<SurfaceDataException> { SurfaceData.requireContainer(info(containerVersion = 2), m) }
        assertFailsWith<SurfaceDataException> { SurfaceData.requireContainer(info(levelCount = 0), m) }
    }

    @Test
    fun datasetIsFoundById() {
        assertEquals(SurfaceDataset.ESA_WORLDCOVER_Z9, SurfaceDataset.of("esa-worldcover-z9"))
        assertEquals("esa-worldcover-z9.sqlite3", SurfaceDataset.ESA_WORLDCOVER_Z9.fileName)
        assertNull(SurfaceDataset.of("osm-peaks"))
        assertNull(SurfaceDataset.of(null))
    }

    // endregion
}
