// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnlineSurfaceTest {
    private val fujiLat = 35.3606
    private val fujiLon = 138.7274

    @Test
    fun urlAndLevelsFollowTheGsiTiles() {
        assertEquals("https://cyberjapandata.gsi.go.jp/xyz/pale/15/29099/12902.png", OnlineSurface.GSI_PALE.url(SurfaceTile(15, 29099, 12902)))
        assertEquals("https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/18/1/2.jpg", OnlineSurface.GSI_PHOTO.url(SurfaceTile(18, 1, 2)))
        assertEquals(15, OnlineSurface.GSI_RELIEF.levels.last().z)
        assertTrue(OnlineSurface.entries.flatMap { it.levels }.all { it.tilePixels == 256 })
        assertEquals(OnlineSurface.GSI_HILLSHADE, OnlineSurface.of("gsi-hillshade"))
        assertEquals(null, OnlineSurface.of("esa-worldcover-z9"))
        // 地表データの種類と ID がかぶらない(設定に並べて覚えるため)。
        assertTrue(OnlineSurface.entries.none { o -> SurfaceDataset.entries.any { it.id == o.id } })
    }

    @Test
    fun imageTilesCoverEveryTileTheImageReads() {
        for (radiusKm in listOf(2.0, 8.0, 45.0)) {
            val frame = SurfaceFrame(fujiLat, fujiLon, radiusKm)
            val level = SurfaceRaster.chooseLevel(OnlineSurface.GSI_PALE.levels, frame, size = 256)!!
            val planned = SurfaceRaster.imageTiles(frame, level, size = 256).toSet()
            val read = mutableSetOf<SurfaceTile>()
            SurfaceRaster.renderImage(frame, level, size = 256) { tile ->
                read += tile
                null
            }
            assertTrue(planned.containsAll(read), "半径 $radiusKm km で読むタイルが足りません: ${read - planned}")
            // 画像の範囲より大きくは取らない(周りに 1 枚ずつまで)。
            assertTrue(planned.size <= (kotlin.math.sqrt(read.size.toDouble()) + 2).let { it * it }, "半径 $radiusKm km で ${planned.size} 枚")
        }
    }

    @Test
    fun renderImageCopiesOpaqueTilesAndLeavesMissingTilesTransparent() {
        val frame = SurfaceFrame(fujiLat, fujiLon, 2.0)
        val level = SurfaceLevel(15, 256)
        val color = 0xFF336699.toInt()
        val opaque = SurfaceRaster.renderImage(frame, level, size = 64) { IntArray(256 * 256) { color } }
        assertTrue(opaque.all { it == color })
        val missing = SurfaceRaster.renderImage(frame, level, size = 64) { null }
        assertTrue(missing.all { it == 0 })
    }

    @Test
    fun renderImageDoesNotDarkenEdgesOfTransparentPixels() {
        // 左半分が透明な黒、右半分が不透明な白のタイル。境目の半透明な画素も白のまま(黒が混ざらない)。
        val frame = SurfaceFrame(fujiLat, fujiLon, 2.0)
        val level = SurfaceLevel(12, 256)
        val tile = IntArray(256 * 256) { if (it % 256 < 128) 0 else 0xFFFFFFFF.toInt() }
        val out = SurfaceRaster.renderImage(frame, level, size = 256) { tile }
        val visible = out.filter { it ushr 24 > 0 }
        assertTrue(visible.isNotEmpty())
        assertTrue(visible.all { it and 0xFFFFFF == 0xFFFFFF })
        assertTrue(out.any { it ushr 24 in 1..254 }, "境目の半透明な画素がありません")
    }

    @Test
    fun clientKeepsFetchedTilesInMemoryAndCountsFailures() = runTest {
        // MockEngine は同時に来た要求を別々のスレッドで受けるので、記録はスレッドを守るリストにする。
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requested += path
            when {
                path.endsWith("/1.png") -> respond(byteArrayOf(1, 2, 3))
                path.endsWith("/2.png") -> respondError(HttpStatusCode.NotFound)
                else -> respondError(HttpStatusCode.InternalServerError)
            }
        }
        val client = OnlineSurfaceClient(HttpClient(engine), userAgent = "test")
        val tiles = listOf(SurfaceTile(15, 0, 1), SurfaceTile(15, 0, 2), SurfaceTile(15, 0, 3))
        val first = client.fetch(OnlineSurface.GSI_PALE, tiles)
        assertEquals(setOf(SurfaceTile(15, 0, 1)), first.tiles.keys)
        assertContentEquals(byteArrayOf(1, 2, 3), first.tiles.getValue(SurfaceTile(15, 0, 1)))
        // 404 はタイルが無いだけなので失敗に数えない。
        assertEquals(1, first.failed)
        assertEquals(3, requested.size)

        // 取れたタイルと 404 だったタイルは取り直さない。失敗したタイルだけ取り直す。
        val second = client.fetch(OnlineSurface.GSI_PALE, tiles)
        assertEquals(first.tiles.keys, second.tiles.keys)
        assertEquals(listOf("/xyz/pale/15/0/3.png"), requested.drop(3))
    }
}
