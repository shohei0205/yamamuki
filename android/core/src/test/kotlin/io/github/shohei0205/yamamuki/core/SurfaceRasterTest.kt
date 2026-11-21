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

    /** 緯度経度が、[frame] の一辺 [size] の画像のどの画素に当たるか(列, 行)。 */
    private fun pixelOf(frame: SurfaceFrame, size: Int, lat: Double, lon: Double): Pair<Int, Int> {
        val d = GeoMath.distanceKm(frame.centerLat, frame.centerLon, lat, lon)
        val b = Math.toRadians(GeoMath.bearingDeg(frame.centerLat, frame.centerLon, lat, lon))
        val i = ((d * kotlin.math.sin(b) + frame.radiusKm) / (frame.radiusKm * 2) * size).toInt()
        val j = ((frame.radiusKm - d * kotlin.math.cos(b)) / (frame.radiusKm * 2) * size).toInt()
        return i to j
    }

    /** 森林の濃さがそのまま灰色の明るさになる配色(地面は黒、森林は白)。水域は青。 */
    private val gray = SurfacePalette(ground = 0x000000, forest = 0xFFFFFF, water = 0x0000FF)

    /** 森林の濃さだけを持つタイル。 */
    private fun forestTile(pixels: Int, value: (Int) -> Int) =
        SurfaceRaster.TileLayers(ByteArray(pixels * pixels) { value(it).toByte() }, null, null)

    /** タイルごとに違う濃さ(20〜219)。 */
    private fun valueOf(tile: SurfaceTile) = 20 + (tile.x * 7 + tile.y * 13) % 200

    private fun grayOf(value: Int) = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    @Test
    fun renderPutsEachTileWhereItsPlaceIs() {
        val level = SurfaceLevel(9, 16)
        val frame = SurfaceFrame(fujiLat, fujiLon, 80.0)
        val size = 128
        val requested = mutableListOf<SurfaceTile>()
        val image = SurfaceRaster.render(frame, level, gray, null, size, sharpen = false) { tile ->
            requested += tile
            forestTile(16) { valueOf(tile) }
        }
        // 1 枚のタイルにつき 1 回だけ読む。
        assertEquals(requested.size, requested.toSet().size)
        assertTrue(requested.toSet().all { it in SurfaceRaster.tiles(frame, 9).toSet() })
        // タイルの中央は、そのタイルの濃さになる。
        var checked = 0
        for (tile in SurfaceRaster.tiles(frame, 9)) {
            val box = tile.bounds
            val (i, j) = pixelOf(frame, size, (box.north + box.south) / 2, (box.west + box.east) / 2)
            if (i !in 0 until size || j !in 0 until size) continue
            assertEquals(grayOf(valueOf(tile)), image[j * size + i], "tile $tile")
            checked++
        }
        assertTrue(checked >= 4, "確かめたタイル $checked 枚")
        // 中心は画像の中央、北は上、東は右。
        assertEquals(size / 2 to size / 2, pixelOf(frame, size, fujiLat, fujiLon))
        val (northLat, northLon) = GeoMath.destination(fujiLat, fujiLon, 40.0, 0.0)
        assertTrue(pixelOf(frame, size, northLat, northLon).second < size / 2)
        val (eastLat, eastLon) = GeoMath.destination(fujiLat, fujiLon, 40.0, 90.0)
        assertTrue(pixelOf(frame, size, eastLat, eastLon).first > size / 2)
    }

    @Test
    fun renderInterpolatesBetweenPixels() {
        // 右へ行くほど濃くなるタイル(列 × 10)。画像を元の画素より細かく作ると、間の値で埋まる。
        val level = SurfaceLevel(9, 16)
        val frame = SurfaceFrame(fujiLat, fujiLon, 8.0)
        val size = 96
        val tile = SurfaceTiles.tileAt(fujiLat, fujiLon, 9)
        val image = SurfaceRaster.render(frame, level, gray, null, size, sharpen = false) { forestTile(16) { index -> index % 16 * 10 } }
        val values = mutableSetOf<Int>()
        for (i in 0 until size) {
            val east = (i + 0.5) / size * 16.0 - 8.0
            val (lat, lon) = GeoMath.destination(fujiLat, fujiLon, kotlin.math.abs(east), if (east >= 0) 90.0 else 270.0)
            if (SurfaceTiles.tileAt(lat, lon, 9) != tile) continue
            val column = (SurfaceTiles.worldU(lon) * 512 - tile.x) * 16 - 0.5
            if (column < 0.5 || column > 14.5) continue
            val value = image[size / 2 * size + i] and 0xFF
            assertEquals(column * 10, value.toDouble(), 2.0, "列 $i")
            assertEquals(0xFF, image[size / 2 * size + i] ushr 24)
            values += value
        }
        // 元の 16 段より細かい値が出ている。
        assertTrue(values.size > 20, "濃さの値 ${values.size} 種類")
    }

    @Test
    fun renderLeavesMissingTilesTransparent() {
        val level = SurfaceLevel(9, 8)
        val frame = SurfaceFrame(fujiLat, fujiLon, 40.0)
        val center = SurfaceTiles.tileAt(fujiLat, fujiLon, 9)
        val palette = SurfacePalette(ground = 0x445566, forest = 0x112233, water = 0x0000FF)
        val image = SurfaceRaster.render(frame, level, palette, null, 32) { tile -> if (tile == center) forestTile(8) { 255 } else null }
        assertEquals(0xFF112233.toInt(), image[16 * 32 + 16])
        assertTrue(image.any { it == 0 })
        // データの無いタイルとの境目は透明度だけが変わり、色は混ざらない。
        assertTrue(image.all { it == 0 || it and 0xFFFFFF == 0x112233 })
        // 大きさの合わないタイルは受け取らない。
        assertFailsWith<IllegalArgumentException> {
            SurfaceRaster.render(frame, level, palette, null, 32) { SurfaceRaster.TileLayers(ByteArray(10), null, null) }
        }
    }

    @Test
    fun sharpeningNarrowsTheEdgeOfAMagnifiedMask() {
        // 左半分が森林、右半分が森林なしのタイル。元の 1 画素が画像の十画素ほどに拡大される。
        val level = SurfaceLevel(9, 16)
        val frame = SurfaceFrame(fujiLat, fujiLon, 40.0)
        val size = 192
        val edge = { _: SurfaceTile -> forestTile(16) { index -> if (index % 16 < 8) 255 else 0 } }
        val soft = SurfaceRaster.render(frame, level, gray, null, size, sharpen = false, tile = edge)
        val sharp = SurfaceRaster.render(frame, level, gray, null, size, sharpen = true, tile = edge)
        fun middle(image: IntArray) = (0 until size).count { (image[size / 2 * size + it] and 0xFF) in 30..225 }
        assertTrue(middle(soft) >= 8, "補間だけの境目 ${middle(soft)} 画素")
        assertTrue(middle(sharp) * 3 < middle(soft), "鋭くした境目 ${middle(sharp)} 画素、補間だけ ${middle(soft)} 画素")
        // 森林と森林なしの面積はほぼ変わらない。
        fun forestShare(image: IntArray) = image.count { (it and 0xFF) >= 128 }
        assertEquals(forestShare(soft).toDouble(), forestShare(sharp).toDouble(), size * size * 0.02)
        // 拡大しないとき(画像のほうが粗いとき)は補間のまま。
        val coarse = SurfaceFrame(fujiLat, fujiLon, 4000.0)
        assertEquals(1.0, SurfaceRaster.magnification(coarse, level, size))
        assertTrue(SurfaceRaster.magnification(frame, level, size) > 4.0)
    }

    @Test
    fun sharpeningKeepsAThinWater() {
        // 1 画素だけの細い水域(濃さ 100)。補間のままだと薄い線になるが、鋭くすると消えずにはっきり残る。
        val level = SurfaceLevel(9, 16)
        val frame = SurfaceFrame(fujiLat, fujiLon, 40.0)
        val size = 192
        val river = { _: SurfaceTile ->
            SurfaceRaster.TileLayers(
                ByteArray(256) { 0 },
                null,
                ByteArray(256) { if (it % 16 == 8) 100 else 0 },
            )
        }
        fun maxBlue(image: IntArray) = image.maxOf { it and 0xFF }
        assertTrue(maxBlue(SurfaceRaster.render(frame, level, gray, null, size, sharpen = false, tile = river)) <= 110)
        assertTrue(maxBlue(SurfaceRaster.render(frame, level, gray, null, size, sharpen = true, tile = river)) >= 200)
    }

    @Test
    fun noWaterStaysClearAtAnyMagnification() {
        // 水域が無い(濃さ 0)場所と、ごく薄い場所(濃さ 8)は、拡大の倍率が小さくても青くならない。
        val level = SurfaceLevel(9, 16)
        val size = 192
        for (radius in listOf(30.0, 80.0, 150.0, 250.0, 400.0)) {
            val frame = SurfaceFrame(fujiLat, fujiLon, radius)
            for (faint in listOf(0, 8)) {
                val tile = { _: SurfaceTile -> SurfaceRaster.TileLayers(ByteArray(256), null, ByteArray(256) { faint.toByte() }) }
                val image = SurfaceRaster.render(frame, level, gray, null, size, sharpen = true, tile = tile)
                assertTrue(image.all { it and 0xFF <= 8 }, "半径 $radius km、濃さ $faint で青い画素がある(倍率 ${SurfaceRaster.magnification(frame, level, size)})")
            }
        }
    }

    @Test
    fun sharpeningKeepsAFaintRiverVisible() {
        // データの中で薄く入っている川(濃さ 50、約 0.2)も、補間のままより濃く、途切れずに見える。
        val level = SurfaceLevel(9, 16)
        val frame = SurfaceFrame(fujiLat, fujiLon, 40.0)
        val size = 192
        val river = { _: SurfaceTile ->
            SurfaceRaster.TileLayers(ByteArray(256), null, ByteArray(256) { if (it % 16 == 8) 50 else 0 })
        }
        fun maxBlue(image: IntArray) = image.maxOf { it and 0xFF }
        assertTrue(maxBlue(SurfaceRaster.render(frame, level, gray, null, size, sharpen = false, tile = river)) <= 60)
        assertTrue(maxBlue(SurfaceRaster.render(frame, level, gray, null, size, sharpen = true, tile = river)) >= 120)
    }
}
