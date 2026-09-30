package io.github.shohei0205.yamamuki.core

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AreaDownloadTest {
    private val fuji = Mountain(1, "富士山", 35.3606, 138.7274, 3776.0)
    private val yari = Mountain(2, "槍ヶ岳", 36.3420, 137.6476, 3180.0)
    private val outside = Mountain(3, "範囲外の山", 40.0, 140.0, 1000.0)

    private class FakeRemote(val peaks: List<Mountain>) : MountainRemoteSource {
        val calls = mutableListOf<BoundingBox>()
        /** この回数目(1 始まり)の問い合わせで失敗する。 */
        var failAt: Set<Int> = emptySet()
        /** 失敗させるときに投げる例外。 */
        var failure: () -> Exception = { IOException("offline") }
        override suspend fun fetchPeaks(box: BoundingBox): List<Mountain> {
            calls += box
            if (calls.size in failAt) throw failure()
            return peaks.filter { box.contains(it.latitude, it.longitude) }
        }
    }

    private var now = 1_000_000L
    private val nagano = Prefecture.byCode(20)!!

    private fun repo(remote: FakeRemote, cache: MountainCache) =
        MountainRepository(remote, cache, maxAgeMillis = 1000, clock = { now })

    @Test
    fun downloadsAllTilesInChunksAndReportsProgress() = runTest {
        val remote = FakeRemote(listOf(fuji, yari, outside))
        val cache = InMemoryMountainCache()
        val progress = mutableListOf<DownloadProgress>()

        val count = repo(remote, cache).downloadTiles(nagano.tiles) { progress += it }

        val total = nagano.tiles.size
        assertEquals(MountainRepository.downloadChunks(nagano.tiles).size, remote.calls.size)
        assertTrue(remote.calls.size < total, "数タイルずつまとめて問い合わせる")
        assertEquals(DownloadProgress(0, total), progress.first())
        assertEquals(DownloadProgress(total, total), progress.last())
        assertEquals(nagano.tiles.toSet(), cache.tiles.keys)
        assertEquals(2, count, "タイル単位で取るので、長野県の外の富士山も入る(範囲外の山は入らない)")
    }

    @Test
    fun resumesAfterFailureWithoutRefetchingDoneTiles() = runTest {
        val remote = FakeRemote(listOf(yari)).apply { failAt = setOf(3) }
        val cache = InMemoryMountainCache()
        val repo = repo(remote, cache)

        assertFailsWith<IOException> { repo.downloadTiles(nagano.tiles, retryDelaysMillis = emptyList()) }
        val doneBefore = cache.tiles.size
        assertTrue(doneBefore > 0, "失敗する前に取得したタイルは残る")

        val progress = mutableListOf<DownloadProgress>()
        repo.downloadTiles(nagano.tiles) { progress += it }
        assertEquals(doneBefore, progress.first().doneTiles, "取得済みの分は最初から済みとして数える")
        assertEquals(nagano.tiles.size, cache.tiles.size)
        val chunks = MountainRepository.downloadChunks(nagano.tiles).size
        assertEquals(chunks + 1, remote.calls.size, "失敗した 1 回を除き、同じ範囲を二度問い合わせない")
    }

    @Test
    fun retriesFailedChunkBeforeGivingUp() = runTest {
        // 3 回目の問い合わせ(= 3 つ目の区画)が 2 回続けて失敗しても、取り直して最後まで取得する。
        val remote = FakeRemote(listOf(yari)).apply { failAt = setOf(3, 4) }
        val cache = InMemoryMountainCache()
        val progress = mutableListOf<DownloadProgress>()

        repo(remote, cache).downloadTiles(nagano.tiles) { progress += it }

        assertEquals(nagano.tiles.size, cache.tiles.size)
        assertEquals(MountainRepository.downloadChunks(nagano.tiles).size + 2, remote.calls.size)
        assertEquals(listOf(1, 2), progress.map { it.retry }.filter { it > 0 }, "取り直していることを知らせる")
        assertEquals(0, progress.last().retry)

        // 取り直す回数を使い切ったら失敗として返す。
        val failing = FakeRemote(listOf(yari)).apply { failAt = (1..10).toSet() }
        assertFailsWith<IOException> {
            repo(failing, InMemoryMountainCache()).downloadTiles(nagano.tiles, retryDelaysMillis = listOf(1L, 1L))
        }
        assertEquals(3, failing.calls.size, "最初の 1 回 + 取り直し 2 回")
    }

    @Test
    fun waitsLongerAfterTooManyRequests() = runTest {
        // 429 のときは 5 秒ではなく、少なくとも 30 秒待ってから取り直す。504 などはそのままの間隔。
        val progress = mutableListOf<DownloadProgress>()
        val limited = FakeRemote(listOf(yari)).apply {
            failAt = setOf(1)
            failure = { OverpassException("HTTP 429", httpStatus = 429) }
        }
        repo(limited, InMemoryMountainCache()).downloadTiles(nagano.tiles) { progress += it }
        assertEquals(listOf(30_000L), progress.filter { it.retry > 0 }.map { it.retryWaitMillis })

        progress.clear()
        val busy = FakeRemote(listOf(yari)).apply {
            failAt = setOf(1)
            failure = { OverpassException("HTTP 504", httpStatus = 504) }
        }
        repo(busy, InMemoryMountainCache()).downloadTiles(nagano.tiles) { progress += it }
        assertEquals(listOf(5_000L), progress.filter { it.retry > 0 }.map { it.retryWaitMillis })
    }

    @Test
    fun statusTextCountsWaitingSeconds() {
        assertEquals("サーバーの応答を待っています（35 秒）", DownloadProgress(3, 10).statusText(35_400))
        val retrying = DownloadProgress(3, 10, retry = 2, retryWaitMillis = 15_000)
        assertEquals("通信に失敗したため、15 秒後に取り直します（2 回目）", retrying.statusText(0))
        assertEquals("通信に失敗したため、1 秒後に取り直します（2 回目）", retrying.statusText(14_500))
        assertEquals("サーバーの応答を待っています（5 秒・取り直し 2 回目）", retrying.statusText(20_000))
    }

    @Test
    fun refreshRefetchesEvenFreshTiles() = runTest {
        val remote = FakeRemote(listOf(yari))
        val repo = repo(remote, InMemoryMountainCache())
        repo.downloadTiles(nagano.tiles)
        val first = remote.calls.size

        repo.downloadTiles(nagano.tiles)
        assertEquals(first, remote.calls.size, "新しいタイルは取り直さない")
        repo.downloadTiles(nagano.tiles, forceRefresh = true)
        assertEquals(first * 2, remote.calls.size)
    }

    @Test
    fun chunksGroupTwoByTwoTiles() {
        val tiles = (0..2).flatMap { la -> (0..2).map { lo -> Tile(la, lo) } }
        val chunks = MountainRepository.downloadChunks(tiles)
        assertEquals(listOf(2, 1, 4, 2), chunks.map { it.size }, "北の行から、西から順に並ぶ")
        assertEquals(tiles.toSet(), chunks.flatten().toSet())
    }

    @Test
    fun prefecturesCoverJapanWithReasonableTileCounts() {
        assertEquals((101..104).toList() + (2..47).toList(), Prefecture.ALL.map { it.code })
        assertEquals(Prefecture.ALL.size, Prefecture.ALL.map { it.name }.toSet().size)
        for (p in Prefecture.ALL) {
            assertTrue(p.tiles.size in 1..50, "${p.name}: ${p.tiles.size} タイル")
            for (box in p.areas) {
                assertTrue(box.south < box.north && box.west < box.east, p.name)
                assertTrue(box.south in 24.0..46.0 && box.west in 122.0..146.0, p.name)
            }
        }
        assertTrue(Prefecture.byCode(20)!!.areas.first().contains(yari.latitude, yari.longitude))
        assertTrue(Prefecture.byCode(46)!!.areas.any { it.contains(30.3358, 130.5047) }, "鹿児島県に屋久島(宮之浦岳)が入る")
        // 北海道の主な山が、分けたどれかの地域に入る(旭岳・羊蹄山・駒ヶ岳・羅臼岳・利尻山)。
        val hokkaido = Prefecture.HOKKAIDO_CODES.map { Prefecture.byCode(it)!! }
        for ((lat, lon) in listOf(43.6636 to 142.8544, 42.8267 to 140.8114, 42.0631 to 140.6772, 44.0758 to 145.1222, 45.1789 to 141.2419)) {
            assertTrue(hokkaido.any { p -> p.areas.any { it.contains(lat, lon) } }, "北海道の $lat, $lon")
        }
        assertEquals("北海道・東北", hokkaido.first().region)
        // 北端がちょうど 44.0° なので、44.0〜44.5° のタイルは含めない。
        assertEquals(40, Prefecture.byCode(101)!!.tiles.size)
    }
}
