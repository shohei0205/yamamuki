// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
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

    /** 補間の重みを整数で持つときの 1.0。 */
    private const val WEIGHT_ONE = 256

    /** 濃さ 255 を、補間の重みを掛けた値で持つときの値(重みの合計は [WEIGHT_ONE] の 2 乗)。 */
    private const val FULL = 255 * WEIGHT_ONE * WEIGHT_ONE

    /**
     * 輪郭を鋭くするときの、境目にする濃さ(0〜255 の割合)。森林は中間の濃さを境にする。水域は川のように 1 画素より細い
     * ものがデータの中で薄く(0.1〜0.3 ほど)入っているので、低い境目から立ち上げて、途切れずに残す。
     */
    private const val FOREST_EDGE = 0.5
    private const val WATER_EDGE = 0.175

    /** 立ち上げの始まりの下限(濃さの割合)。これより薄い場所は、データの中の薄いばらつきとして色を付けない。 */
    private const val FOREST_FLOOR = 0.0
    private const val WATER_FLOOR = 0.05

    /** 輪郭を鋭くする強さ(境目の濃さの傾き)の上限。元の画素が画像の何画素に拡大されても、境目はこの傾きより急にはしない。 */
    private const val MAX_SHARPNESS = 8.0

    /** 水域の傾きの上限。川の薄い濃さが 0.05〜0.3 の間で、なだらかに濃くなるようにする(大きな湖の岸は別に鋭くなる)。 */
    private const val WATER_MAX_SHARPNESS = 4.0

    /** 傾きを整数で持つときの 1.0。 */
    private const val GAIN_ONE = 256

    /** 1 枚のタイルの、森林・陰影・水域の濃さ(1 画素 1 バイト、0〜255)。無いレイヤーは null。 */
    class TileLayers(val forest: ByteArray?, val hillshade: ByteArray?, val water: ByteArray?)

    /**
     * 元の画素が画像の何画素に拡大されるか(1 以上)。1 画素より細かい段が無くて拡大するときだけ、1 より大きくなる。
     * 輪郭を鋭くする傾きに使う。
     */
    fun magnification(frame: SurfaceFrame, level: SurfaceLevel, size: Int = SIZE): Double {
        val scale = SurfaceTiles.metersPerPixel(level, frame.centerLat) / metersPerPixel(frame, size)
        return scale.coerceIn(1.0, MAX_SHARPNESS)
    }

    /**
     * 補間した濃さ [value](0〜[FULL])の境目を [edge] にして、傾き [gain] で立ち上げた濃さ(0〜255)。[gain] が 1.0 なら補間のまま。
     * 濃さが [floor](0〜1 の割合)以下の場所は 0 にする。境目から傾きの逆数の半分だけ下がったところが、立ち上がりの始まり
     * (ただし [floor] より下にはしない)で、そこより薄い場所に色が付かない(水域が無い場所が青くならない)。
     */
    private fun sharpen(value: Int, edge: Double, gain: Int, floor: Double): Int {
        if (gain == GAIN_ONE) return (value + WEIGHT_ONE * WEIGHT_ONE / 2) / (WEIGHT_ONE * WEIGHT_ONE)
        val half = 0.5 * GAIN_ONE / gain
        val low = maxOf(floor, edge - half)
        val high = edge + half
        val lowValue = (FULL * low).toLong()
        val highValue = (FULL * high).toLong()
        val x = if (highValue > lowValue) (value - lowValue) * FULL / (highValue - lowValue) else if (value >= lowValue) FULL.toLong() else 0L
        val clamped = x.coerceIn(0L, FULL.toLong())
        return ((clamped + WEIGHT_ONE * WEIGHT_ONE / 2) / (WEIGHT_ONE * WEIGHT_ONE)).toInt()
    }

    /** [frame] の画像を作るのに要る、ズーム [z] のタイル。 */
    fun tiles(frame: SurfaceFrame, z: Int): List<SurfaceTile> {
        // 画像の角は、中心から半径の √2 倍の距離にある。
        val box = BoundingBox.around(frame.centerLat, frame.centerLon, frame.radiusKm * sqrt(2.0))
        return SurfaceTiles.covering(box, z)
    }

    /**
     * [frame] の画像を作る。左上から行ごとに並べた一辺 [size] の画像で、1 画素(0xAARRGGBB)は周りの 4 画素の濃さから求める。
     *
     * 色にする前の濃さを補間し、森林と水域は境目の濃さで切って輪郭を鋭くする。色を補間すると、元の画素より細かく作ったときに
     * 輪郭がぼけるため。切る急さは拡大の倍率([magnification])で決め、拡大しないときは補間のままにする。
     * 森林が無い場所(データの端)は地面を塗らず、色は混ぜずに透明度だけが変わる。陰影は鋭くしない。
     *
     * @param tile [level] のタイルの濃さ。データの無いタイルは null を返す。その場所は透明(0)になる。1 枚のタイルにつき 1 回だけ呼ぶ。
     * @param sharpen false なら輪郭を鋭くしない(補間だけ)。
     */
    fun render(
        frame: SurfaceFrame,
        level: SurfaceLevel,
        palette: SurfacePalette,
        shade: SurfaceShade?,
        size: Int = SIZE,
        sharpen: Boolean = true,
        tile: (SurfaceTile) -> TileLayers?,
    ): IntArray {
        require(size > 0) { "画像の一辺 $size が正の数ではありません" }
        val out = IntArray(size * size)
        val tileCount = SurfaceTiles.tileCount(level.z)
        val last = tileCount.toInt() - 1
        val tilePixels = level.tilePixels
        val painter = SurfaceCompositor.Painter(palette, shade)
        val gain = if (sharpen) (magnification(frame, level, size) * GAIN_ONE).roundToInt() else GAIN_ONE

        val grid = WorldGrid(frame, size)
        val nodes = grid.nodes
        val us = grid.us
        val vs = grid.vs

        // タイルをまたいで、世界全体の画素の位置(列・行)から濃さを読む。データの無い場所は、すべて 0。
        val loaded = HashMap<Long, TileLayers?>()
        var currentKey = -1L
        var current: TileLayers? = null
        val worldPixels = (last + 1L) * tilePixels
        // 4 画素ぶんの、地面を塗る範囲・森林・陰影・水域の濃さ。
        val ground = IntArray(4)
        val forest = IntArray(4)
        val hillshade = IntArray(4)
        val water = IntArray(4)
        fun read(k: Int, gx: Long, gy: Long) {
            ground[k] = 0
            forest[k] = 0
            hillshade[k] = painter.neutral
            water[k] = 0
            if (gx < 0 || gy < 0 || gx >= worldPixels || gy >= worldPixels) return
            val tx = (gx / tilePixels).toInt()
            val ty = (gy / tilePixels).toInt()
            val key = ty.toLong() * (last + 1L) + tx
            if (key != currentKey) {
                currentKey = key
                current = if (loaded.containsKey(key)) {
                    loaded[key]
                } else {
                    val layers = tile(SurfaceTile(level.z, tx, ty))
                    if (layers != null) {
                        for ((name, layer) in listOf("forest" to layers.forest, "hillshade" to layers.hillshade, "water" to layers.water)) {
                            require(layer == null || layer.size == tilePixels * tilePixels) {
                                "タイルの $name の画素数 ${layer?.size} が ${tilePixels * tilePixels} ではありません"
                            }
                        }
                    }
                    loaded[key] = layers
                    layers
                }
            }
            val layers = current ?: return
            val index = (gy - ty.toLong() * tilePixels).toInt() * tilePixels + (gx - tx.toLong() * tilePixels).toInt()
            if (layers.forest != null) {
                ground[k] = 255
                forest[k] = layers.forest[index].toInt() and 0xFF
            }
            layers.hillshade?.let { hillshade[k] = it[index].toInt() and 0xFF }
            layers.water?.let { water[k] = it[index].toInt() and 0xFF }
        }

        for (j in 0 until size) {
            val b = j / GRID
            val fy = (j + 0.5 - b * GRID) / GRID
            for (i in 0 until size) {
                val a = i / GRID
                val fx = (i + 0.5 - a * GRID) / GRID
                val n = b * nodes + a
                val u = (us[n] * (1 - fx) + us[n + 1] * fx) * (1 - fy) + (us[n + nodes] * (1 - fx) + us[n + nodes + 1] * fx) * fy
                val v = (vs[n] * (1 - fx) + vs[n + 1] * fx) * (1 - fy) + (vs[n + nodes] * (1 - fx) + vs[n + nodes + 1] * fx) * fy
                // 画素の中心を基準に、周りの 4 画素から求める。
                val gx = u * worldPixels - 0.5
                val gy = v * worldPixels - 0.5
                val x0 = floor(gx).toLong()
                val y0 = floor(gy).toLong()
                val wx = ((gx - x0) * WEIGHT_ONE).toInt()
                val wy = ((gy - y0) * WEIGHT_ONE).toInt()
                read(0, x0, y0)
                read(1, x0 + 1, y0)
                read(2, x0, y0 + 1)
                read(3, x0 + 1, y0 + 1)
                val w00 = (WEIGHT_ONE - wx) * (WEIGHT_ONE - wy)
                val w10 = wx * (WEIGHT_ONE - wy)
                val w01 = (WEIGHT_ONE - wx) * wy
                val w11 = wx * wy
                val groundValue = ground[0] * w00 + ground[1] * w10 + ground[2] * w01 + ground[3] * w11
                var forestValue = forest[0] * w00 + forest[1] * w10 + forest[2] * w01 + forest[3] * w11
                val waterValue = water[0] * w00 + water[1] * w10 + water[2] * w01 + water[3] * w11
                if (groundValue == 0 && waterValue == 0) {
                    out[j * size + i] = 0
                    continue
                }
                var shadeValue = painter.neutral
                if (groundValue > 0) {
                    // 地面を塗らない場所(データの端)の分は、色に混ぜない。
                    val h = hillshade[0] * w00 + hillshade[1] * w10 + hillshade[2] * w01 + hillshade[3] * w11
                    if (groundValue < FULL) {
                        forestValue = (forestValue.toLong() * FULL / groundValue).toInt()
                        shadeValue = ((h.toLong() - painter.neutral.toLong() * (FULL - groundValue) / 255) * 255 / groundValue).toInt()
                            .coerceIn(0, 255)
                    } else {
                        shadeValue = (h + WEIGHT_ONE * WEIGHT_ONE / 2) / (WEIGHT_ONE * WEIGHT_ONE)
                    }
                }
                out[j * size + i] = painter.paint(
                    ground = (groundValue + WEIGHT_ONE * WEIGHT_ONE / 2) / (WEIGHT_ONE * WEIGHT_ONE),
                    forest = sharpen(forestValue.coerceAtMost(FULL), FOREST_EDGE, gain, FOREST_FLOOR),
                    hillshade = shadeValue,
                    water = sharpen(waterValue, WATER_EDGE, minOf(gain, (WATER_MAX_SHARPNESS * GAIN_ONE).toInt()), WATER_FLOOR),
                )
            }
        }
        return out
    }

    /** 透明度を掛けた 4 画素の色 [c] を重みで補間し、透明度の合計 [alphaSum] で割って、透明度を掛けない色(0〜255)に戻す。 */
    private fun unpremultiply(c: IntArray, w00: Int, w10: Int, w01: Int, w11: Int, alphaSum: Int): Int {
        val sum = c[0].toLong() * w00 + c[1].toLong() * w10 + c[2].toLong() * w01 + c[3].toLong() * w11
        return ((sum + alphaSum / 2) / alphaSum).toInt().coerceIn(0, 255)
    }

    /**
     * 画像の格子の点ごとの、世界全体を 0〜1 とした位置。格子の間隔は [GRID] 画素で、間の画素は格子の値から直線で求める。
     */
    private class WorldGrid(frame: SurfaceFrame, size: Int) {
        val nodes = (size + GRID - 1) / GRID + 1
        val us = DoubleArray(nodes * nodes)
        val vs = DoubleArray(nodes * nodes)

        init {
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
        }
    }

    /**
     * [frame] の画像を地図の画像のタイル([OnlineSurface])から作るのに読む、[level] のタイル。画像の範囲にかかるものだけ
     * (補間で読む隣の画素の分を含む)を、北西から行ごとに並べる。
     */
    fun imageTiles(frame: SurfaceFrame, level: SurfaceLevel, size: Int = SIZE): List<SurfaceTile> {
        val grid = WorldGrid(frame, size)
        val worldPixels = SurfaceTiles.tileCount(level.z) * level.tilePixels
        val last = SurfaceTiles.tileCount(level.z).toInt() - 1
        // 画像の縁は格子の点の外周にある。格子の点のあいだは直線で求めるので、外周の点の最小と最大で足りる。
        var minU = Double.MAX_VALUE
        var maxU = -Double.MAX_VALUE
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (n in grid.us.indices) {
            minU = minOf(minU, grid.us[n])
            maxU = maxOf(maxU, grid.us[n])
            minV = minOf(minV, grid.vs[n])
            maxV = maxOf(maxV, grid.vs[n])
        }
        fun tileIndex(world: Double, offset: Double) =
            (floor((world * worldPixels + offset) / level.tilePixels).toInt()).coerceIn(0, last)
        val x0 = tileIndex(minU, -1.0)
        val x1 = tileIndex(maxU, 1.0)
        val y0 = tileIndex(minV, -1.0)
        val y1 = tileIndex(maxV, 1.0)
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> SurfaceTile(level.z, x, y) } }
    }

    /**
     * [frame] の画像を、地図の画像のタイル([OnlineSurface])から作る。左上から行ごとに並べた一辺 [size] の画像で、
     * 1 画素(0xAARRGGBB、透明度を掛けていない色)は周りの 4 画素から求める。透明な画素の色が混ざって縁が暗くならないよう、
     * 透明度を掛けた色で補間する。
     *
     * @param tile [level] のタイルの画素(0xAARRGGBB、透明度を掛けていない色、一辺は tilePixels)。無いタイルは null を返し、
     *   その場所は透明(0)になる。1 枚のタイルにつき 1 回だけ呼ぶ。
     */
    fun renderImage(frame: SurfaceFrame, level: SurfaceLevel, size: Int = SIZE, tile: (SurfaceTile) -> IntArray?): IntArray {
        require(size > 0) { "画像の一辺 $size が正の数ではありません" }
        val out = IntArray(size * size)
        val tilePixels = level.tilePixels
        val tilesPerSide = SurfaceTiles.tileCount(level.z).toLong()
        val worldPixels = tilesPerSide * tilePixels
        val grid = WorldGrid(frame, size)
        val nodes = grid.nodes
        val us = grid.us
        val vs = grid.vs

        val loaded = HashMap<Long, IntArray?>()
        var currentKey = -1L
        var current: IntArray? = null
        // 4 画素ぶんの、透明度と、透明度を掛けた赤・緑・青。
        val alpha = IntArray(4)
        val red = IntArray(4)
        val green = IntArray(4)
        val blue = IntArray(4)
        fun read(k: Int, gx: Long, gy: Long) {
            alpha[k] = 0
            red[k] = 0
            green[k] = 0
            blue[k] = 0
            if (gx < 0 || gy < 0 || gx >= worldPixels || gy >= worldPixels) return
            val tx = (gx / tilePixels).toInt()
            val ty = (gy / tilePixels).toInt()
            val key = ty.toLong() * tilesPerSide + tx
            if (key != currentKey) {
                currentKey = key
                current = if (loaded.containsKey(key)) {
                    loaded[key]
                } else {
                    val pixels = tile(SurfaceTile(level.z, tx, ty))
                    require(pixels == null || pixels.size == tilePixels * tilePixels) {
                        "タイルの画素数 ${pixels?.size} が ${tilePixels * tilePixels} ではありません"
                    }
                    loaded[key] = pixels
                    pixels
                }
            }
            val pixels = current ?: return
            val p = pixels[(gy - ty.toLong() * tilePixels).toInt() * tilePixels + (gx - tx.toLong() * tilePixels).toInt()]
            val a = p ushr 24
            alpha[k] = a
            red[k] = (p shr 16 and 0xFF) * a
            green[k] = (p shr 8 and 0xFF) * a
            blue[k] = (p and 0xFF) * a
        }

        for (j in 0 until size) {
            val b = j / GRID
            val fy = (j + 0.5 - b * GRID) / GRID
            for (i in 0 until size) {
                val a = i / GRID
                val fx = (i + 0.5 - a * GRID) / GRID
                val n = b * nodes + a
                val u = (us[n] * (1 - fx) + us[n + 1] * fx) * (1 - fy) + (us[n + nodes] * (1 - fx) + us[n + nodes + 1] * fx) * fy
                val v = (vs[n] * (1 - fx) + vs[n + 1] * fx) * (1 - fy) + (vs[n + nodes] * (1 - fx) + vs[n + nodes + 1] * fx) * fy
                val gx = u * worldPixels - 0.5
                val gy = v * worldPixels - 0.5
                val x0 = floor(gx).toLong()
                val y0 = floor(gy).toLong()
                val wx = ((gx - x0) * WEIGHT_ONE).toInt()
                val wy = ((gy - y0) * WEIGHT_ONE).toInt()
                read(0, x0, y0)
                read(1, x0 + 1, y0)
                read(2, x0, y0 + 1)
                read(3, x0 + 1, y0 + 1)
                val w00 = (WEIGHT_ONE - wx) * (WEIGHT_ONE - wy)
                val w10 = wx * (WEIGHT_ONE - wy)
                val w01 = (WEIGHT_ONE - wx) * wy
                val w11 = wx * wy
                // 透明度は 0〜255 に重み(合計 WEIGHT_ONE の 2 乗)を掛けた値、色はさらに透明度を掛けた値。
                val aSum = alpha[0] * w00 + alpha[1] * w10 + alpha[2] * w01 + alpha[3] * w11
                if (aSum == 0) {
                    out[j * size + i] = 0
                    continue
                }
                val outAlpha = (aSum + WEIGHT_ONE * WEIGHT_ONE / 2) / (WEIGHT_ONE * WEIGHT_ONE)
                out[j * size + i] = (outAlpha shl 24) or
                    (unpremultiply(red, w00, w10, w01, w11, aSum) shl 16) or
                    (unpremultiply(green, w00, w10, w01, w11, aSum) shl 8) or
                    unpremultiply(blue, w00, w10, w01, w11, aSum)
            }
        }
        return out
    }
}
