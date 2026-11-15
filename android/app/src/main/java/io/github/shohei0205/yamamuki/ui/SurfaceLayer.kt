// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.shohei0205.yamamuki.core.InstalledSurfaceData
import io.github.shohei0205.yamamuki.core.SurfaceFrame
import io.github.shohei0205.yamamuki.core.SurfacePalette
import io.github.shohei0205.yamamuki.core.SurfaceRaster
import io.github.shohei0205.yamamuki.data.SurfaceTileSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 方位盤の背景に貼る地表の画像と、その範囲。 */
class SurfaceImage(val bitmap: ImageBitmap, val frame: SurfaceFrame)

/**
 * 地表データの SQLite から、方位盤に貼る画像を作る。SQLite は最初に画像を作るときに開く。
 * 作るのは重い(タイルの復号と並べ直し)ので、画面を描くスレッドとは別のところで、1 つずつ順に行う。
 */
private class SurfaceRenderer(private val file: File, private val datasetId: String) {
    private val mutex = Mutex()
    private var source: SurfaceTileSource? = null

    suspend fun render(frame: SurfaceFrame): SurfaceImage? = mutex.withLock {
        withContext(Dispatchers.Default) {
            val source = source ?: SurfaceTileSource(file, datasetId).also { source = it }
            val level = SurfaceRaster.chooseLevel(source.levels, frame) ?: return@withContext null
            val size = SurfaceRaster.SIZE
            val pixels = SurfaceRaster.render(frame, level, size) { tile -> source.tile(tile, level, SurfacePalette.DIAL) }
            SurfaceImage(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap(), frame)
        }
    }

    suspend fun close() = mutex.withLock {
        source?.close()
        source = null
    }
}

/**
 * 方位盤の背景に貼る地表の画像。[file] の地表データ([installed])から、画面の基準の位置([centerLat]・[centerLon])と
 * 表示範囲に合わせて作り、大きく動かしたり拡縮したりしたときだけ作り直す。
 * 地表データが無い([installed] が null)あいだと、最初の画像ができるまでは null。
 */
@Composable
fun rememberSurfaceImage(
    file: File,
    installed: InstalledSurfaceData?,
    centerLat: Double?,
    centerLon: Double?,
    rangeKm: Double,
): SurfaceImage? {
    // 地表データを取り直したら、新しいファイルを開き直す。地表を表示しなくなったら、画像も捨てる。
    val renderer = remember(file, installed?.version) { installed?.let { SurfaceRenderer(file, it.datasetId) } }
    var image by remember(renderer) { mutableStateOf<SurfaceImage?>(null) }
    LaunchedEffect(renderer) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { renderer?.close() }
        }
    }
    LaunchedEffect(renderer, centerLat, centerLon, rangeKm) {
        if (renderer == null || centerLat == null || centerLon == null) return@LaunchedEffect
        if (!SurfaceRaster.needsRender(image?.frame, centerLat, centerLon, rangeKm)) return@LaunchedEffect
        // 拡縮や移動の途中では作らず、落ち着いてから作る。
        delay(RENDER_DELAY_MS)
        try {
            renderer.render(SurfaceFrame(centerLat, centerLon, SurfaceRaster.radiusKm(rangeKm)))?.let { image = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "地表の画像を作れませんでした\n${e.stackTraceToString()}")
        }
    }
    return image
}

private const val RENDER_DELAY_MS = 200L
private const val TAG = "SurfaceLayer"
