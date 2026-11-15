// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.shohei0205.yamamuki.core.SurfaceCompositor
import io.github.shohei0205.yamamuki.core.SurfaceLayerKind
import io.github.shohei0205.yamamuki.core.SurfaceLevel
import io.github.shohei0205.yamamuki.core.SurfacePalette
import io.github.shohei0205.yamamuki.core.SurfaceShade
import io.github.shohei0205.yamamuki.core.SurfaceTile
import java.io.Closeable
import java.io.File

/**
 * 端末に置いた地表データの SQLite から、タイルを読んで 1 枚の色の画像にする。
 * 森林・陰影・水域の画像(WebP か PNG)を復号し、core の [SurfaceCompositor] で合成する。
 * SQLite は読み取り専用で開く。呼び出しは 1 つのスレッドから順に行う。
 *
 * @param datasetId SQLite の中の、地表のデータセットの ID(manifest の datasets の id)。
 */
class SurfaceTileSource(file: File, datasetId: String) : Closeable {
    private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    private val datasetKey: Long
    private val layerKeys: Map<SurfaceLayerKind, Long>
    private val shade: SurfaceShade?

    /** 収録されたズームの段。森林・陰影・水域のどのレイヤーにもある段だけ。 */
    val levels: List<SurfaceLevel>

    init {
        datasetKey = db.rawQuery("SELECT dataset_key FROM datasets WHERE id = ? AND kind = 'surface'", arrayOf(datasetId)).use {
            if (it.moveToFirst()) it.getLong(0) else -1
        }
        val keys = mutableMapOf<SurfaceLayerKind, Long>()
        var shadeSettings: SurfaceShade? = null
        db.rawQuery("SELECT layer_key, id, role, settings_json FROM surface_layers WHERE dataset_key = ?", arrayOf(datasetKey.toString())).use {
            while (it.moveToNext()) {
                val kind = SurfaceLayerKind.of(it.getString(1), it.getString(2)) ?: continue
                keys[kind] = it.getLong(0)
                if (kind == SurfaceLayerKind.HILLSHADE) shadeSettings = SurfaceShade.parse(it.getString(3).orEmpty())
            }
        }
        layerKeys = keys
        shade = shadeSettings
        levels = keys.values.map { layerKey ->
            db.rawQuery(
                "SELECT z, tile_pixels FROM surface_levels WHERE dataset_key = ? AND layer_key = ?",
                arrayOf(datasetKey.toString(), layerKey.toString()),
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(SurfaceLevel(cursor.getInt(0), cursor.getInt(1))) }
            }
        }.reduceOrNull { a, b -> a intersect b }.orEmpty().sortedBy { it.z }
    }

    /**
     * [tile] の森林・陰影・水域を [palette] の色で合成した画像(一辺は [level] の tilePixels、1 画素は 0xAARRGGBB)。
     * どのレイヤーにもそのタイルが無ければ null。
     */
    fun tile(tile: SurfaceTile, level: SurfaceLevel, palette: SurfacePalette): IntArray? {
        val forest = mask(SurfaceLayerKind.FOREST, tile, level)
        val hillshade = mask(SurfaceLayerKind.HILLSHADE, tile, level)
        val water = mask(SurfaceLayerKind.WATER, tile, level)
        if (forest == null && water == null) return null
        return SurfaceCompositor.compose(level.tilePixels, forest, hillshade, water, palette, shade)
    }

    /** [kind] のレイヤーの [tile] を復号した、1 画素 1 バイトの濃さ。タイルが無い、復号できない、大きさが合わないときは null。 */
    private fun mask(kind: SurfaceLayerKind, tile: SurfaceTile, level: SurfaceLevel): ByteArray? {
        val layerKey = layerKeys[kind] ?: return null
        val payload = db.rawQuery(
            "SELECT payload FROM surface_tiles WHERE dataset_key = ? AND layer_key = ? AND z = ? AND x = ? AND y = ?",
            arrayOf(datasetKey.toString(), layerKey.toString(), tile.z.toString(), tile.x.toString(), tile.y.toString()),
        ).use { if (it.moveToFirst()) it.getBlob(0) else null } ?: return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.size, options) ?: return null
        try {
            val size = level.tilePixels
            if (bitmap.width != size || bitmap.height != size) return null
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            // 濃さは灰色の画像で入っている。復号した色の緑の成分を読む。
            return ByteArray(pixels.size) { (pixels[it] shr 8 and 0xFF).toByte() }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = db.close()
}
