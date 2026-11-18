// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.shohei0205.yamamuki.core.OnlineSurface
import io.github.shohei0205.yamamuki.core.OnlineSurfaceClient
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

/**
 * 方位盤の背景に貼る地表の画像と、その範囲。[complete] が false なら、通信に失敗して取れなかったタイルがあり、
 * その場所は透明になっている(しばらくしてから作り直す)。
 */
class SurfaceImage(val bitmap: ImageBitmap, val frame: SurfaceFrame, val complete: Boolean = true)

/** 方位盤に貼る地表の画像を作るもの。地表データ([FileSurfaceRenderer])と、通信して取る地図([OnlineSurfaceRenderer])がある。 */
interface SurfaceRenderer {
    /** [frame] の画像を作る。作れなければ null。 */
    suspend fun render(frame: SurfaceFrame): SurfaceImage?

    suspend fun close() {}
}

/**
 * 地表データの SQLite から、方位盤に貼る画像を作る。SQLite は最初に画像を作るときに開く。
 * 作るのは重い(タイルの復号と並べ直し)ので、画面を描くスレッドとは別のところで、1 つずつ順に行う。
 */
class FileSurfaceRenderer(private val file: File, private val datasetId: String, private val hillshade: Boolean) : SurfaceRenderer {
    private val mutex = Mutex()
    private var source: SurfaceTileSource? = null

    override suspend fun render(frame: SurfaceFrame): SurfaceImage? = mutex.withLock {
        withContext(Dispatchers.Default) {
            val source = source ?: SurfaceTileSource(file, datasetId).also { source = it }
            val level = SurfaceRaster.chooseLevel(source.levels, frame) ?: return@withContext null
            val size = SurfaceRaster.SIZE
            val pixels = SurfaceRaster.render(frame, level, SurfacePalette.DIAL, source.shade.takeIf { hillshade }, size) { tile -> source.tile(tile, level, hillshade) }
            SurfaceImage(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap(), frame)
        }
    }

    override suspend fun close() = mutex.withLock {
        source?.close()
        source = null
    }
}

/**
 * 通信して取る地図(地理院タイル)から、方位盤に貼る画像を作る。画像の範囲にかかるタイルをまとめて取り、並べ直す。
 * 取ったタイルは [client] がメモリにだけ覚え、ファイルには保存しない。通信できなければ画像を作らない。
 */
class OnlineSurfaceRenderer(private val client: OnlineSurfaceClient, private val surface: OnlineSurface) : SurfaceRenderer {
    private val mutex = Mutex()

    override suspend fun render(frame: SurfaceFrame): SurfaceImage? = mutex.withLock {
        val size = SurfaceRaster.SIZE
        val level = SurfaceRaster.chooseLevel(surface.levels, frame, size) ?: return@withLock null
        val fetched = client.fetch(surface, SurfaceRaster.imageTiles(frame, level, size))
        // 1 枚も取れなかった(圏外など)ときは、前の画像を残す。
        if (fetched.tiles.isEmpty() && fetched.failed > 0) return@withLock null
        withContext(Dispatchers.Default) {
            val pixels = SurfaceRaster.renderImage(frame, level, size) { tile -> fetched.tiles[tile]?.let(::decode) }
            SurfaceImage(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap(), frame, complete = fetched.failed == 0)
        }
    }

    /** タイルの画像(PNG か JPEG)を、透明度を掛けていない 0xAARRGGBB の画素にする。読めない、大きさが違うときは null。 */
    private fun decode(bytes: ByteArray): IntArray? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            val tilePixels = OnlineSurface.TILE_PIXELS
            if (bitmap.width != tilePixels || bitmap.height != tilePixels) return null
            val pixels = IntArray(tilePixels * tilePixels)
            bitmap.getPixels(pixels, 0, tilePixels, 0, 0, tilePixels, tilePixels)
            return pixels
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * 方位盤の背景に貼る地表の画像。[renderer] で、画面の基準の位置([centerLat]・[centerLon])と表示範囲に合わせて作り、
 * 大きく動かしたり拡縮したりしたときだけ作り直す。通信に失敗して欠けた画像は、しばらくしてから作り直す。
 * 地表を表示しない([renderer] が null)あいだと、最初の画像ができるまでは null。
 */
@Composable
fun rememberSurfaceImage(
    renderer: SurfaceRenderer?,
    centerLat: Double?,
    centerLon: Double?,
    rangeKm: Double,
): SurfaceImage? {
    // 地表の種類を変えたら、画像も捨てる。
    var image by remember(renderer) { mutableStateOf<SurfaceImage?>(null) }
    LaunchedEffect(renderer) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { renderer?.close() }
        }
    }
    // 通信に失敗して作れなかった、または欠けた画像になったら、しばらくしてから作り直させる。
    var failed by remember(renderer) { mutableStateOf(false) }
    var retryRequested by remember(renderer) { mutableStateOf(false) }
    LaunchedEffect(renderer, failed, image) {
        if (!failed && image?.complete != false) return@LaunchedEffect
        delay(RETRY_DELAY_MS)
        failed = false
        retryRequested = true
    }
    LaunchedEffect(renderer, centerLat, centerLon, rangeKm, retryRequested) {
        if (renderer == null || centerLat == null || centerLon == null) return@LaunchedEffect
        if (!retryRequested && !SurfaceRaster.needsRender(image?.frame, centerLat, centerLon, rangeKm)) return@LaunchedEffect
        // 拡縮や移動の途中では作らず、落ち着いてから作る。
        delay(RENDER_DELAY_MS)
        try {
            val rendered = renderer.render(SurfaceFrame(centerLat, centerLon, SurfaceRaster.radiusKm(rangeKm)))
            retryRequested = false
            if (rendered != null) image = rendered else failed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "地表の画像を作れませんでした\n${e.stackTraceToString()}")
        }
    }
    return image
}

private const val RENDER_DELAY_MS = 200L

/** 通信に失敗したときに、作り直すまで待つ時間。 */
private const val RETRY_DELAY_MS = 10_000L
private const val TAG = "SurfaceLayer"
