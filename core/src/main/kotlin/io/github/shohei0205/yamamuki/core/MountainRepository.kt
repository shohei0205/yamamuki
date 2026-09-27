package io.github.shohei0205.yamamuki.core

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

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
