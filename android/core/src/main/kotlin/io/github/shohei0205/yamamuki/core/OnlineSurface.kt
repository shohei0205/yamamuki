// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 方位盤の背景に描く、通信して取る地図(国土地理院の地理院タイル)の種類。並びは選択画面の並び。
 * 地表データ([SurfaceDataset])と違ってダウンロードせず、通信できるときに表示している周りのタイルだけを取る。端末には保存しない。
 * タイルは Web メルカトルの XYZ タイル(一辺 256 画素)。[maxZoom] はタイルがある最も細かいズーム。
 */
enum class OnlineSurface(val id: String, private val path: String, private val extension: String, val minZoom: Int, val maxZoom: Int) {
    /** 淡色地図。道路・川・等高線などを淡い色で描いた地図。 */
    GSI_PALE("gsi-pale", "pale", "png", 5, 18),

    /** 標準地図。 */
    GSI_STD("gsi-std", "std", "png", 5, 18),

    /** 陰影起伏図。 */
    GSI_HILLSHADE("gsi-hillshade", "hillshademap", "png", 2, 16),

    /** 色別標高図。標高を色で塗り分け、陰影を重ねた図。 */
    GSI_RELIEF("gsi-relief", "relief", "png", 5, 15),

    /** 写真(全国最新写真・シームレス)。 */
    GSI_PHOTO("gsi-photo", "seamlessphoto", "jpg", 2, 18),
    ;

    /** 読めるズームの段。 */
    val levels: List<SurfaceLevel> get() = (minZoom..maxZoom).map { SurfaceLevel(it, TILE_PIXELS) }

    /** [tile] の画像の URL。 */
    fun url(tile: SurfaceTile): String = "$BASE_URL/$path/${tile.z}/${tile.x}/${tile.y}.$extension"

    companion object {
        /** タイルの一辺の画素数。 */
        const val TILE_PIXELS = 256

        private const val BASE_URL = "https://cyberjapandata.gsi.go.jp/xyz"

        /** 出典に添える、地理院タイルの一覧のページ。利用規約で、出典と一緒に示すよう求められている。 */
        const val CREDITS_URL = "https://maps.gsi.go.jp/development/ichiran.html"

        /** [id] の種類。知らない ID なら null。 */
        fun of(id: String?): OnlineSurface? = entries.firstOrNull { it.id == id }
    }
}

/**
 * [OnlineSurface] のタイルを取る。取ったタイルは、アプリを開いているあいだだけメモリに覚えておき、拡縮や少しの移動で
 * 取り直さないようにする。ファイルには保存しない(オフライン用のキャッシュは持たない)。
 *
 * @param maxCachedTiles メモリに覚えておくタイルの枚数の上限。古く使ったものから捨てる。
 */
class OnlineSurfaceClient(
    private val http: HttpClient,
    private val userAgent: String,
    private val maxCachedTiles: Int = 256,
    parallelism: Int = 6,
) {
    /** 取ったタイルの画像。最後に使った順に並ぶ。タイルが無かった(404)ときは空の配列。 */
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > maxCachedTiles
    }
    private val semaphore = Semaphore(parallelism)

    /** 取った結果。[tiles] はタイルごとの画像(タイルが無い場所は入らない)、[failed] は通信に失敗して取れなかった枚数。 */
    class Result(val tiles: Map<SurfaceTile, ByteArray>, val failed: Int)

    /**
     * [surface] の [tiles] を、まとめて取る。取れたタイルはすぐメモリに覚えるので、途中で取り消されても次に取り直さない。
     * 通信に失敗したタイルは [Result.failed] に数え、ほかのタイルは返す。
     */
    suspend fun fetch(surface: OnlineSurface, tiles: List<SurfaceTile>): Result = coroutineScope {
        val results = tiles.map { tile ->
            async {
                tile to semaphore.withPermit {
                    try {
                        fetchOne(surface, tile)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }.map { it.await() }
        Result(
            tiles = results.mapNotNull { (tile, bytes) -> bytes?.takeIf { it.isNotEmpty() }?.let { tile to it } }.toMap(),
            failed = results.count { it.second == null },
        )
    }

    /** 1 枚を取る。タイルが無い(404)ときは空の配列。通信に失敗したら例外。 */
    private suspend fun fetchOne(surface: OnlineSurface, tile: SurfaceTile): ByteArray {
        val url = surface.url(tile)
        synchronized(cache) { cache[url] }?.let { return it }
        val response = http.get(url) { header(HttpHeaders.UserAgent, userAgent) }
        val bytes = when {
            response.status.isSuccess() -> response.readRawBytes()
            response.status == HttpStatusCode.NotFound -> ByteArray(0)
            else -> throw IllegalStateException("タイル $url の取得に失敗(HTTP ${response.status.value})")
        }
        synchronized(cache) { cache[url] = bytes }
        return bytes
    }
}
