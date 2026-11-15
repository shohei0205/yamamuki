// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurfaceRasterTest {
    private val fujiLat = 35.3606
    private val fujiLon = 138.7274
    private val z9Levels = (5..9).map { SurfaceLevel(it, 512) }

    @Test
    fun destinationIsTheInverseOfDistanceAndBearing() {
        for ((distance, bearing) in listOf(0.5 to 0.0, 20.0 to 90.0, 80.0 to 217.0, 250.0 to 315.0)) {
            val (lat, lon) = GeoMath.destination(fujiLat, fujiLon, distance, bearing)
            assertEquals(distance, GeoMath.distanceKm(fujiLat, fujiLon, lat, lon), 1e-6)
            assertEquals(bearing, GeoMath.bearingDeg(fujiLat, fujiLon, lat, lon), 1e-6)
        }
        val (lat, lon) = GeoMath.destination(fujiLat, fujiLon, 0.0, 123.0)
        assertEquals(fujiLat, lat, 1e-9)
        assertEquals(fujiLon, lon, 1e-9)
        // 経度は -180〜180° に収める。
        assertTrue(GeoMath.destination(0.0, 179.9, 100.0, 90.0).second < -179.0)
    }

    @Test
    fun radiusCoversTheScreenWithRoomToMove() {
        for (range in listOf(2.0, 5.0, 15.0, 37.0, 80.0)) {
            val radius = SurfaceRaster.radiusKm(range)
            // 画面に要る半径の 1.5 倍以上で、その √2 倍より狭い。
            assertTrue(radius >= range * SurfaceRaster.REACH * 1.5, "range $range radius $radius")
            assertTrue(radius < range * SurfaceRaster.REACH * 1.5 * 1.4143 || radius == 2.0, "range $range radius $radius")
        }
        // 少しの拡縮では変わらない。
        assertEquals(SurfaceRaster.radiusKm(15.0), SurfaceRaster.radiusKm(15.5))
    }

    @Test
    fun rendersAgainOnlyWhenTheScreenLeavesTheImageOrItIsTooCoarse() {
        val frame = SurfaceFrame(fujiLat, fujiLon, SurfaceRaster.radiusKm(15.0))
        assertTrue(SurfaceRaster.needsRender(null, fujiLat, fujiLon, 15.0))
        assertFalse(SurfaceRaster.needsRender(frame, fujiLat, fujiLon, 15.0))
        // 少し歩いても、少し拡縮しても作り直さない。
        val (nearLat, nearLon) = GeoMath.destination(fujiLat, fujiLon, 3.0, 45.0)
        assertFalse(SurfaceRaster.needsRender(frame, nearLat, nearLon, 15.0))
        assertFalse(SurfaceRaster.needsRender(frame, fujiLat, fujiLon, 18.0))
        assertFalse(SurfaceRaster.needsRender(frame, fujiLat, fujiLon, 8.0))
        // 画面が画像からはみ出すほど動かしたり、遠くまで表示したりしたら作り直す。
        val (farLat, farLon) = GeoMath.destination(fujiLat, fujiLon, 20.0, 45.0)
        assertTrue(SurfaceRaster.needsRender(frame, farLat, farLon, 15.0))
        assertTrue(SurfaceRaster.needsRender(frame, fujiLat, fujiLon, 40.0))
        // 大きく拡大したら、粗すぎるので作り直す。
        assertTrue(SurfaceRaster.needsRender(frame, fujiLat, fujiLon, 3.0))
    }

    @Test
    fun levelIsTheCoarsestOneFinerThanTheImage() {
        fun z(range: Double) = SurfaceRaster.chooseLevel(z9Levels, SurfaceFrame(36.0, 138.0, SurfaceRaster.radiusKm(range)))?.z
        // 表示範囲 15km の画像は 1 画素が約 50m。ズーム 9(約 124m)でも粗いので、最も細かい段を使う。
        assertEquals(9, z(15.0))
        assertEquals(9, z(2.0))
        // 表示範囲 80km の画像は 1 画素が約 280m。ズーム 8(約 247m)で足りる。
        assertEquals(8, z(80.0))
        assertNull(SurfaceRaster.chooseLevel(emptyList(), SurfaceFrame(36.0, 138.0, 20.0)))
    }

    @Test
    fun tilesCoverTheCornersOfTheImage() {
        val frame = SurfaceFrame(fujiLat, fujiLon, 100.0)
        val tiles = SurfaceRaster.tiles(frame, 9).toSet()
        for (bearing in listOf(45.0, 135.0, 225.0, 315.0)) {
            val (lat, lon) = GeoMath.destination(fujiLat, fujiLon, 100.0 * 1.4142, bearing)
            assertTrue(SurfaceTiles.tileAt(lat, lon, 9) in tiles)
        }
    }

    /** タイルの位置と画素の位置を色に書いた、見本のタイル。0xFF000000 | (x の下位 4 ビット, y の下位 4 ビット, 画素の列, 画素の行)。 */
    private fun probeTile(tile: SurfaceTile, pixels: Int) = IntArray(pixels * pixels) { index ->
        (0xFF shl 24) or ((tile.x and 0xF) shl 20) or ((tile.y and 0xF) shl 16) or ((index % pixels) shl 8) or (index / pixels)
    }

    @Test
    fun renderPutsNorthUpAndSamplesTheRightPixel() {
        val level = SurfaceLevel(9, 16)
        val frame = SurfaceFrame(fujiLat, fujiLon, 30.0)
        val size = 64
        val requested = mutableListOf<SurfaceTile>()
        val image = SurfaceRaster.render(frame, level, size) { tile ->
            requested += tile
            probeTile(tile, level.tilePixels)
        }
        // 1 枚のタイルにつき 1 回だけ読む。
        assertEquals(requested.size, requested.toSet().size)
        assertTrue(requested.toSet().all { it in SurfaceRaster.tiles(frame, 9).toSet() })

        fun expected(lat: Double, lon: Double): Int {
            val tile = SurfaceTiles.tileAt(lat, lon, 9)
            val px = ((SurfaceTiles.worldU(lon) * 512 - tile.x) * 16).toInt()
            val py = ((SurfaceTiles.worldV(lat) * 512 - tile.y) * 16).toInt()
            return (0xFF shl 24) or ((tile.x and 0xF) shl 20) or ((tile.y and 0xF) shl 16) or (px shl 8) or py
        }
        /** 画像の画素 (i, j) の中心の、中心からの東と北への距離(km)。 */
        fun at(i: Int, j: Int): Pair<Double, Double> {
            val east = (i + 0.5) / size * 60.0 - 30.0
            val north = 30.0 - (j + 0.5) / size * 60.0
            return GeoMath.destination(fujiLat, fujiLon, kotlin.math.hypot(east, north), Math.toDegrees(kotlin.math.atan2(east, north)))
        }
        // 格子の点の間も、正確に求めた位置と同じ画素を写している(ずれても隣の画素まで)。
        var exact = 0
        val samples = listOf(0 to 0, 63 to 0, 0 to 63, 63 to 63, 32 to 32, 10 to 50, 45 to 7, 31 to 33)
        for ((i, j) in samples) {
            val (lat, lon) = at(i, j)
            if (image[j * size + i] == expected(lat, lon)) exact++
        }
        assertTrue(exact >= samples.size - 1, "一致した画素 $exact / ${samples.size}")
        // 上の行ほど北、右の列ほど東。
        assertTrue(at(32, 0).first > at(32, 63).first)
        assertTrue(at(63, 32).second > at(0, 32).second)
    }

    @Test
    fun renderLeavesMissingTilesTransparent() {
        val level = SurfaceLevel(9, 8)
        val frame = SurfaceFrame(fujiLat, fujiLon, 40.0)
        val center = SurfaceTiles.tileAt(fujiLat, fujiLon, 9)
        val image = SurfaceRaster.render(frame, level, 32) { tile -> if (tile == center) IntArray(64) { 0xFF112233.toInt() } else null }
        assertEquals(0xFF112233.toInt(), image[16 * 32 + 16])
        assertTrue(image.any { it == 0 })
        assertTrue(image.all { it == 0 || it == 0xFF112233.toInt() })
        // 大きさの合わないタイルは受け取らない。
        assertFailsWith<IllegalArgumentException> { SurfaceRaster.render(frame, level, 32) { IntArray(10) } }
    }
}
