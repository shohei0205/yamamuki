package io.github.shohei0205.yamamuki.data

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.shohei0205.yamamuki.core.Tile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** キャッシュの消去で、行だけでなく DB のファイルも縮むかを確かめる。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CacheManagerTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var database: MountainDatabase
    private lateinit var cacheManager: CacheManager

    @Before
    fun setUp() {
        context.deleteDatabase(MountainDatabase.FILE_NAME)
        // 端末と同じく WAL で書く(消した行の分が -wal に残るかどうかを確かめるため)。
        database = Room.databaseBuilder(context, MountainDatabase::class.java, MountainDatabase.FILE_NAME)
            .addMigrations(*MOUNTAIN_MIGRATIONS)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        cacheManager = CacheManager(context, database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(MountainDatabase.FILE_NAME)
    }

    @Test
    fun clearShrinksDatabaseFile() = runBlocking {
        insertMountains(tile = Tile(35, 138), count = 20_000)
        val before = cacheManager.info()

        cacheManager.clear()

        val after = cacheManager.info()
        assertEquals(0, after.mountainCount)
        assertEquals(0, after.tileCount)
        assertTrue(
            "消去のあとも DB のファイルが縮んでいない(前 ${before.sizeBytes} バイト、後 ${after.sizeBytes} バイト)",
            after.sizeBytes < before.sizeBytes / 10,
        )
    }

    @Test
    fun removeTilesShrinksDatabaseFile() = runBlocking {
        val removed = Tile(35, 138)
        insertMountains(tile = removed, count = 20_000)
        insertMountains(tile = Tile(36, 139), count = 10, firstId = 100_000)
        val before = cacheManager.info()

        cacheManager.removeTiles(listOf(removed))

        val after = cacheManager.info()
        assertEquals(10, after.mountainCount)
        assertEquals(1, after.tileCount)
        assertTrue(
            "地域の削除のあとも DB のファイルが縮んでいない(前 ${before.sizeBytes} バイト、後 ${after.sizeBytes} バイト)",
            after.sizeBytes < before.sizeBytes / 10,
        )
    }

    private suspend fun insertMountains(tile: Tile, count: Int, firstId: Long = 1) {
        val dao = database.mountainDao()
        dao.insertMountains(
            (0 until count).map { i ->
                MountainEntity(
                    osmId = firstId + i,
                    name = "山$i", // 文言チェック対象外
                    latitude = tile.latIndex + i % 100 / 100.0,
                    longitude = tile.lonIndex + i / 100 % 100 / 100.0,
                    elevationM = 1000.0,
                    tileLat = tile.latIndex,
                    tileLon = tile.lonIndex,
                )
            },
        )
        dao.insertTiles(listOf(FetchedTileEntity(tile.latIndex, tile.lonIndex, fetchedAtMillis = 0)))
    }
}
