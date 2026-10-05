package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoMathTest {
    @Test
    fun distanceTokyoToFuji() {
        // 東京駅 → 富士山剣ヶ峰 はおよそ 100km
        val d = GeoMath.distanceKm(35.6812, 139.7671, 35.3606, 138.7274)
        assertEquals(100.8, d, 1.5)
    }

    @Test
    fun bearingCardinalDirections() {
        assertEquals(0.0, GeoMath.bearingDeg(35.0, 138.0, 36.0, 138.0), 0.01)
        assertEquals(90.0, GeoMath.bearingDeg(0.0, 138.0, 0.0, 139.0), 0.01)
        assertEquals(180.0, GeoMath.bearingDeg(36.0, 138.0, 35.0, 138.0), 0.01)
        assertEquals(270.0, GeoMath.bearingDeg(0.0, 139.0, 0.0, 138.0), 0.01)
    }

    @Test
    fun boundingBoxContainsCircle() {
        val box = BoundingBox.around(35.68, 139.77, 50.0)
        assertTrue(GeoMath.distanceKm(35.68, 139.77, box.north, 139.77) >= 49.9)
        assertTrue(GeoMath.distanceKm(35.68, 139.77, 35.68, box.east) >= 49.9)
    }

    @Test
    fun tilesCoverBox() {
        val box = BoundingBox(35.1, 138.2, 35.9, 138.6)
        val tiles = Tile.covering(box)
        assertEquals(setOf(Tile(70, 276), Tile(70, 277), Tile(71, 276), Tile(71, 277)), tiles.toSet())
        assertEquals(BoundingBox(35.0, 138.0, 36.0, 139.0), Tile.union(tiles))
    }
}
