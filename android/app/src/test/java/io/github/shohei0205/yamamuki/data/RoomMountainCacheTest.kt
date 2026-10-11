package io.github.shohei0205.yamamuki.data

import android.app.Application
import androidx.room.Room
import io.github.shohei0205.yamamuki.core.BoundingBox
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.PointGraphic
import io.github.shohei0205.yamamuki.core.Tile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 地点データ版 5 の項目が、DB に入れて読み戻しても変わらないかを確かめる。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomMountainCacheTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var database: MountainDatabase
    private lateinit var cache: RoomMountainCache

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, MountainDatabase::class.java).build()
        cache = RoomMountainCache(database.mountainDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun keepsPointDataV5Fields() = runBlocking {
        val full = Mountain(
            osmId = 1,
            name = "富士山",
            latitude = 35.3606,
            longitude = 138.7274,
            elevationM = 3776.0,
            pointId = "1",
            nameReading = "ふじさん",
            aliases = listOf("不二山", "芙蓉峰"),
            wikipediaUrl = "https://ja.wikipedia.org/wiki/%E5%AF%8C%E5%A3%AB%E5%B1%B1",
            wikidataUrl = "https://www.wikidata.org/wiki/Q39231",
            tags = listOf("日本百名山"),
            graphic = PointGraphic("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"/>", 1.5),
        )
        val plain = Mountain(2, "名無し", 35.4, 138.8, null)
        cache.replaceTiles(listOf(Tile.of(35.3606, 138.7274)), listOf(full, plain), fetchedAtMillis = 1)

        val read = cache.mountainsIn(BoundingBox(south = 35.0, west = 138.5, north = 35.5, east = 139.0))

        assertEquals(listOf(full, plain), read.sortedBy { it.osmId })
    }
}
