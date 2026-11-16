// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/**
 * 地表データ(方位盤の背景に描く森林・水域・陰影の画像)のタイル。Web メルカトルの XYZ タイルで、原点は左上、
 * x は東向き、y は南向き。形式は yamamuki-data の surface/formats/sqlite.md にある。
 * 山データのキャッシュの単位([Tile]、緯度経度の格子)とは別のもの。
 */
data class SurfaceTile(val z: Int, val x: Int, val y: Int) {
    /** タイルの緯度経度の範囲。 */
    val bounds: BoundingBox
        get() {
            val n = SurfaceTiles.tileCount(z)
            return BoundingBox(
                south = SurfaceTiles.latitude((y + 1) / n),
                west = SurfaceTiles.longitude(x / n),
                north = SurfaceTiles.latitude(y / n),
                east = SurfaceTiles.longitude((x + 1) / n),
            )
        }
}

/** 地表データに収録されたズーム 1 段。[tilePixels] は正方形のタイル画像の一辺の画素数。 */
data class SurfaceLevel(val z: Int, val tilePixels: Int)

object SurfaceTiles {
    /** Web メルカトルで表せる緯度の上限。これより極に近い緯度は、この値に丸める。 */
    const val MAX_LATITUDE = 85.0511287798

    /** ズーム [z] の、1 辺のタイルの枚数(2 の z 乗)。 */
    fun tileCount(z: Int): Double = (1L shl z).toDouble()

    /** 経度を、世界全体を 0〜1 とした横の位置にする(西端が 0)。 */
    fun worldU(lon: Double): Double = (lon + 180.0) / 360.0

    /** 緯度を、世界全体を 0〜1 とした縦の位置にする(北端が 0)。 */
    fun worldV(lat: Double): Double {
        val clamped = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        return (1.0 - asinh(tan(Math.toRadians(clamped))) / PI) / 2.0
    }

    /** [worldU] の逆。 */
    fun longitude(u: Double): Double = u * 360.0 - 180.0

    /** [worldV] の逆。 */
    fun latitude(v: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * v))))

    /** その地点を含む、ズーム [z] のタイル。世界の端をはみ出す位置は端のタイルにする(経度は折り返さない)。 */
    fun tileAt(lat: Double, lon: Double, z: Int): SurfaceTile {
        val n = tileCount(z)
        val last = n.toInt() - 1
        return SurfaceTile(
            z = z,
            x = floor(worldU(lon) * n).toInt().coerceIn(0, last),
            y = floor(worldV(lat) * n).toInt().coerceIn(0, last),
        )
    }

    /** [box] にかかる、ズーム [z] のタイル。北西から、行ごとに東へ並べる。 */
    fun covering(box: BoundingBox, z: Int): List<SurfaceTile> {
        val nw = tileAt(box.north, box.west, z)
        val se = tileAt(box.south, box.east, z)
        return (nw.y..se.y).flatMap { y -> (nw.x..se.x).map { x -> SurfaceTile(z, x, y) } }
    }

    /** 緯度 [lat] での、[level] の 1 画素の東西の長さ(m)。 */
    fun metersPerPixel(level: SurfaceLevel, lat: Double): Double {
        val parallelM = 2.0 * PI * GeoMath.EARTH_RADIUS_KM * 1000.0 * cos(Math.toRadians(lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
        return parallelM / (tileCount(level.z) * level.tilePixels)
    }

    /**
     * 画面の 1 画素が [metersPerScreenPixel] m のときに描くズーム。画面より細かくならない段のうち最も細かい段を選ぶ。
     * 画面に入るタイルの枚数を増やさないためで、等倍より少しぼける。すべての段が画面より細かいときは、最も粗い段。
     * 段が無ければ null。
     */
    fun chooseLevel(levels: List<SurfaceLevel>, lat: Double, metersPerScreenPixel: Double): SurfaceLevel? {
        val coarseFirst = levels.sortedByDescending { metersPerPixel(it, lat) }
        if (!metersPerScreenPixel.isFinite() || metersPerScreenPixel <= 0.0) return coarseFirst.lastOrNull()
        return coarseFirst.lastOrNull { metersPerPixel(it, lat) >= metersPerScreenPixel } ?: coarseFirst.firstOrNull()
    }
}
