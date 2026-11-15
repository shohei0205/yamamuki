// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 方位盤の背景に貼る地表の画像 1 枚の範囲。中心から東西南北へ [radiusKm] の正方形で、北が上。
 * 画像の中の位置は、山と同じく中心からの距離と方位で決める(方位盤の上で山とずれないように)。
 */
data class SurfaceFrame(val centerLat: Double, val centerLon: Double, val radiusKm: Double)

/**
 * 地表のタイルを、方位盤に貼る 1 枚の画像に並べ直す。
 *
 * タイルは Web メルカトルで、方位盤は現在地からの距離と方位の平面図なので、タイルをそのまま貼ると継ぎ目がずれる。
 * そこで、表示している場所を中心にした北が上の画像を作っておき、方位盤ではそれを 1 枚だけ回して貼る。
 * 画像は、表示する場所や表示範囲が大きく変わったときだけ作り直す([needsRender])。
 */
object SurfaceRaster {
    /** 画像の一辺の画素数。 */
    const val SIZE = 1280

    /** 画面のいちばん遠い端までの距離の、表示範囲(現在地から画面上端まで)に対する比。画面の角と、上端より上の余白の分。 */
    const val REACH = 1.2

    /** 作る画像の半径を、画面に要る半径よりどれだけ広くするか。少し動かしたくらいでは作り直さないための余裕。 */
    private const val MARGIN = 1.5

    /** 画像の半径の刻み。拡縮のたびに作り直さないよう、段階にする。 */
    private const val MIN_RADIUS_KM = 2.0
    private val RADIUS_STEP = sqrt(2.0)

    /** 画像が、今の表示範囲に合う半径のこの倍より広くなったら、粗すぎるので作り直す。 */
    private const val TOO_WIDE = 2.0

    /** 緯度経度を正確に求める格子の間隔(画素)。間の画素は、格子の値から直線で求める。 */
    private const val GRID = 32

    /** 表示範囲 [rangeKm] のときに作る画像の半径。 */
    fun radiusKm(rangeKm: Double): Double {
        val needed = rangeKm * REACH * MARGIN
        var radius = MIN_RADIUS_KM
        while (radius < needed) radius *= RADIUS_STEP
        return radius
    }

    /**
     * 画像を作り直すか。まだ無いとき、画面が画像からはみ出すとき、画像が粗すぎるときに作り直す。
     * [centerLat]・[centerLon] は画面の基準の位置(ヘディングアップでは現在地、手動位置モードでは動かした先)。
     */
    fun needsRender(frame: SurfaceFrame?, centerLat: Double, centerLon: Double, rangeKm: Double): Boolean {
        if (frame == null) return true
        val drift = GeoMath.distanceKm(frame.centerLat, frame.centerLon, centerLat, centerLon)
        if (drift + rangeKm * REACH > frame.radiusKm) return true
        return frame.radiusKm > radiusKm(rangeKm) * TOO_WIDE
    }

    /** [frame] の画像の 1 画素の長さ(m)。 */
    fun metersPerPixel(frame: SurfaceFrame, size: Int = SIZE): Double = frame.radiusKm * 2000.0 / size

    /**
     * [frame] の画像を作るのに読むズーム。画像の 1 画素より細かい段のうち、最も粗い段を選ぶ(読むタイルを増やさずに、
     * 画像をぼかさない)。どの段も画像より粗いときは、最も細かい段。段が無ければ null。
     */
    fun chooseLevel(levels: List<SurfaceLevel>, frame: SurfaceFrame, size: Int = SIZE): SurfaceLevel? {
        val target = metersPerPixel(frame, size)
        val coarseFirst = levels.sortedByDescending { SurfaceTiles.metersPerPixel(it, frame.centerLat) }
        return coarseFirst.firstOrNull { SurfaceTiles.metersPerPixel(it, frame.centerLat) <= target } ?: coarseFirst.lastOrNull()
    }

    /** [frame] の画像を作るのに要る、ズーム [z] のタイル。 */
    fun tiles(frame: SurfaceFrame, z: Int): List<SurfaceTile> {
        // 画像の角は、中心から半径の √2 倍の距離にある。
        val box = BoundingBox.around(frame.centerLat, frame.centerLon, frame.radiusKm * sqrt(2.0))
        return SurfaceTiles.covering(box, z)
    }

    /**
     * [frame] の画像を作る。左上から行ごとに並べた一辺 [size] の画像で、1 画素は [tile] が返す値(0xAARRGGBB)をそのまま写す。
     *
     * @param tile [level] のタイルの画像([SurfaceCompositor.compose] の結果。一辺は [SurfaceLevel.tilePixels])。
     *   データの無いタイルは null を返す。その場所は透明(0)になる。1 枚のタイルにつき 1 回だけ呼ぶ。
     */
    fun render(frame: SurfaceFrame, level: SurfaceLevel, size: Int = SIZE, tile: (SurfaceTile) -> IntArray?): IntArray {
        require(size > 0) { "画像の一辺 $size が正の数ではありません" }
        val out = IntArray(size * size)
        val tileCount = SurfaceTiles.tileCount(level.z)
        val last = tileCount.toInt() - 1
        val tilePixels = level.tilePixels

        // 格子の点ごとに、世界全体を 0〜1 とした位置を正確に求める。
        val nodes = (size + GRID - 1) / GRID + 1
        val us = DoubleArray(nodes * nodes)
        val vs = DoubleArray(nodes * nodes)
        val kmPerPixel = frame.radiusKm * 2.0 / size
        for (b in 0 until nodes) {
            for (a in 0 until nodes) {
                val east = a * GRID * kmPerPixel - frame.radiusKm
                val north = frame.radiusKm - b * GRID * kmPerPixel
                val (lat, lon) = GeoMath.destination(
                    frame.centerLat, frame.centerLon, hypot(east, north), Math.toDegrees(atan2(east, north)),
                )
                us[b * nodes + a] = SurfaceTiles.worldU(lon)
                vs[b * nodes + a] = SurfaceTiles.worldV(lat)
            }
        }

        val loaded = HashMap<Long, IntArray?>()
        var currentKey = -1L
        var current: IntArray? = null
        for (j in 0 until size) {
            val b = j / GRID
            val fy = (j + 0.5 - b * GRID) / GRID
            for (i in 0 until size) {
                val a = i / GRID
                val fx = (i + 0.5 - a * GRID) / GRID
                val n = b * nodes + a
                val u = (us[n] * (1 - fx) + us[n + 1] * fx) * (1 - fy) + (us[n + nodes] * (1 - fx) + us[n + nodes + 1] * fx) * fy
                val v = (vs[n] * (1 - fx) + vs[n + 1] * fx) * (1 - fy) + (vs[n + nodes] * (1 - fx) + vs[n + nodes + 1] * fx) * fy
                val wx = u * tileCount
                val wy = v * tileCount
                val tx = floor(wx).toInt()
                val ty = floor(wy).toInt()
                if (tx < 0 || tx > last || ty < 0 || ty > last) continue
                val key = ty.toLong() * (last + 1L) + tx
                if (key != currentKey) {
                    currentKey = key
                    current = if (loaded.containsKey(key)) {
                        loaded[key]
                    } else {
                        val image = tile(SurfaceTile(level.z, tx, ty))
                        require(image == null || image.size == tilePixels * tilePixels) {
                            "タイルの画素数 ${image?.size} が ${tilePixels * tilePixels} ではありません"
                        }
                        loaded[key] = image
                        image
                    }
                }
                val image = current ?: continue
                val px = ((wx - tx) * tilePixels).toInt().coerceAtMost(tilePixels - 1)
                val py = ((wy - ty) * tilePixels).toInt().coerceAtMost(tilePixels - 1)
                out[j * size + i] = image[py * tilePixels + px]
            }
        }
        return out
    }
}
