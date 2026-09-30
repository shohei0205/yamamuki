package io.github.shohei0205.yamamuki.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 山データのローカルキャッシュ。Android では Room で実装する。 */
interface MountainCache {
    /** 指定タイルのうち取得済みのものと、その取得時刻(epoch ms)。 */
    suspend fun fetchedAt(tiles: Collection<Tile>): Map<Tile, Long>

    suspend fun mountainsIn(box: BoundingBox): List<Mountain>

    /** 指定タイルの内容を mountains で置き換え、取得時刻を記録する。 */
    suspend fun replaceTiles(tiles: Collection<Tile>, mountains: List<Mountain>, fetchedAtMillis: Long)
}

data class MountainQueryResult(
    /** 距離の近い順。 */
    val mountains: List<NearbyMountain>,
    /** 範囲内に一度も取得できていないタイルがある(オフラインで未取得の地域など)。 */
    val incomplete: Boolean,
    /** 今回の通信で失敗した場合の原因。キャッシュで表示できていても設定される。 */
    val error: Throwable?,
    /** 取り直すべきタイルがあったが、allowNetwork = false のため通信しなかった。 */
    val networkSkipped: Boolean = false,
)

/** 事前ダウンロードの進み具合(タイル数)。 */
data class DownloadProgress(
    val doneTiles: Int,
    val totalTiles: Int,
    /** 問い合わせに失敗して取り直している回数。0 なら取り直していない。 */
    val retry: Int = 0,
    /** 取り直す前に待つ時間。この知らせのあと、これだけ待ってから問い合わせる。 */
    val retryWaitMillis: Long = 0,
) {
    val fraction: Float get() = if (totalTiles == 0) 1f else doneTiles.toFloat() / totalTiles

    /**
     * 区画数が増えない間も進んでいることが分かるよう、今の区画の状況を 1 秒単位で表す。
     * Overpass は集計が終わるまで何も返さないので、受信量ではなく待っている秒数を出す。
     * @param elapsedMillis この知らせを受け取ってからの時間。
     */
    fun statusText(elapsedMillis: Long): String {
        val retryNote = if (retry > 0) "・取り直し $retry 回目" else ""
        return if (retry > 0 && elapsedMillis < retryWaitMillis) {
            val left = (retryWaitMillis - elapsedMillis + 999) / 1000
            "通信に失敗したため、$left 秒後に取り直します（$retry 回目）"
        } else {
            val waited = (elapsedMillis - if (retry > 0) retryWaitMillis else 0) / 1000
            "サーバーの応答を待っています（$waited 秒$retryNote）"
        }
    }
}

/**
 * 現在地周辺の山を返す。キャッシュを優先し、未取得または古いタイルだけ Overpass に問い合わせる。
 * 通信に失敗してもキャッシュにあるデータで結果を返す。
 */
class MountainRepository(
    private val remote: MountainRemoteSource,
    private val cache: MountainCache,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun mountainsAround(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        forceRefresh: Boolean = false,
        /** false ならキャッシュだけで返す(手動取得モードや、初回の同意前)。 */
        allowNetwork: Boolean = true,
        /** これより古いタイルは取り直す。 */
        maxAgeMillis: Long = this.maxAgeMillis,
    ): MountainQueryResult {
        val box = BoundingBox.around(latitude, longitude, radiusKm)
        val tiles = Tile.covering(box)
        val now = clock()
        val fetched = cache.fetchedAt(tiles)
        val toFetch = tiles.filter { tile ->
            val at = fetched[tile]
            forceRefresh || at == null || now - at > maxAgeMillis
        }

        var error: Throwable? = null
        var missing = tiles.filter { it !in fetched }
        val networkSkipped = toFetch.isNotEmpty() && !allowNetwork
        if (toFetch.isNotEmpty() && allowNetwork) {
            try {
                val peaks = remote.fetchPeaks(Tile.union(toFetch))
                val targets = toFetch.toSet()
                cache.replaceTiles(toFetch, peaks.filter { Tile.of(it.latitude, it.longitude) in targets }, now)
                missing = emptyList()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
        }

        val nearby = cache.mountainsIn(box)
            .map { it.seenFrom(latitude, longitude) }
            .filter { it.distanceKm <= radiusKm }
            .sortedBy { it.distanceKm }

        return MountainQueryResult(nearby, incomplete = missing.isNotEmpty(), error = error, networkSkipped = networkSkipped)
    }

    /**
     * 目的地など、現在地から離れた地域のタイルを前もって取得する(圏外に備えた事前ダウンロード)。
     * 数タイルずつ Overpass に問い合わせ、終わるたびに保存して [onProgress] を呼ぶ。
     * Overpass は混み合うと 504 やタイムアウトを返すので、問い合わせが失敗したら [retryDelaysMillis] の間隔で取り直す。
     * 429(問い合わせが多すぎる)のときは、Overpass の利用方針に合わせて少なくとも [rateLimitWaitMillis] 待つ。
     * 途中で失敗・中断しても取得済みのタイルは残り、もう一度呼べば残りだけを取得する。
     *
     * @param forceRefresh true なら取得済みのタイルも取り直す(保存済みの地域の更新)。
     * @return 対象タイルにある山の数。
     * @throws Exception 取り直しても通信に失敗した。それまでに取得したタイルは保存済み。
     */
    suspend fun downloadTiles(
        tiles: Collection<Tile>,
        forceRefresh: Boolean = false,
        maxAgeMillis: Long = this.maxAgeMillis,
        retryDelaysMillis: List<Long> = DOWNLOAD_RETRY_DELAYS_MILLIS,
        rateLimitWaitMillis: Long = RATE_LIMIT_WAIT_MILLIS,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Int {
        val all = tiles.distinct()
        val now = clock()
        val fetched = cache.fetchedAt(all)
        val toFetch = all.filter { tile ->
            val at = fetched[tile]
            forceRefresh || at == null || now - at > maxAgeMillis
        }
        var done = all.size - toFetch.size
        onProgress(DownloadProgress(done, all.size))
        for (chunk in downloadChunks(toFetch)) {
            var attempt = 0
            var peaks: List<Mountain>? = null
            while (peaks == null) {
                try {
                    peaks = remote.fetchPeaks(Tile.union(chunk))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val delayMillis = retryDelaysMillis.getOrNull(attempt) ?: throw e
                    val wait = if ((e as? OverpassException)?.httpStatus == 429) maxOf(delayMillis, rateLimitWaitMillis) else delayMillis
                    attempt++
                    onProgress(DownloadProgress(done, all.size, retry = attempt, retryWaitMillis = wait))
                    delay(wait)
                }
            }
            val targets = chunk.toSet()
            cache.replaceTiles(chunk, peaks.filter { Tile.of(it.latitude, it.longitude) in targets }, clock())
            done += chunk.size
            onProgress(DownloadProgress(done, all.size))
        }
        if (all.isEmpty()) return 0
        val targets = all.toSet()
        return cache.mountainsIn(Tile.union(all)).count { Tile.of(it.latitude, it.longitude) in targets }
    }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** 事前ダウンロードで問い合わせが失敗したときに、取り直すまで待つ時間(回数分)。 */
        val DOWNLOAD_RETRY_DELAYS_MILLIS = listOf(5_000L, 15_000L, 30_000L)

        /** 429 が返ったときに、取り直すまで待つ最短の時間。Overpass の利用方針(OSM Wiki)の「30 秒待つ」に合わせる。 */
        const val RATE_LIMIT_WAIT_MILLIS = 30_000L

        /** 事前ダウンロードで 1 回に問い合わせるタイルの縦横の数。1°四方なら混み合っていても応答が返りやすい。 */
        const val DOWNLOAD_CHUNK_TILES = 2

        /** タイルを [DOWNLOAD_CHUNK_TILES] 四方ごとにまとめる。北西から順に並べる。 */
        fun downloadChunks(tiles: Collection<Tile>): List<List<Tile>> =
            tiles.groupBy { Math.floorDiv(it.latIndex, DOWNLOAD_CHUNK_TILES) to Math.floorDiv(it.lonIndex, DOWNLOAD_CHUNK_TILES) }
                .entries
                .sortedWith(compareByDescending<Map.Entry<Pair<Int, Int>, List<Tile>>> { it.key.first }.thenBy { it.key.second })
                .map { it.value }
    }
}
