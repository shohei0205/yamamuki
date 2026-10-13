package io.github.shohei0205.yamamuki.data

import android.content.Context
import io.github.shohei0205.yamamuki.core.Tile
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray

@Entity(
    tableName = "mountains",
    indices = [Index("latitude", "longitude"), Index("tileLat", "tileLon")],
)
data class MountainEntity(
    @PrimaryKey val osmId: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double?,
    val tileLat: Int,
    val tileLon: Int,
    // ここから下は地点データ版 5 の項目(DB の版 2 で追加)。版 1 の行と Overpass から取った山には無い。
    val pointId: String? = null,
    val nameReading: String? = null,
    @ColumnInfo(defaultValue = "[]") val aliases: List<String> = emptyList(),
    val wikipediaUrl: String? = null,
    val wikidataUrl: String? = null,
    @ColumnInfo(defaultValue = "[]") val tags: List<String> = emptyList(),
    /** 地点の画像の SVG。画像が無ければ null。 */
    val graphicSvg: String? = null,
    /** 地点の画像の表示倍率。画像が無ければ null。 */
    val graphicScale: Double? = null,
)

/** 別名やタグの一覧を、JSON の配列の文字列として 1 つの列に入れる。 */
class StringListConverter {
    @TypeConverter
    fun fromList(value: List<String>): String = JSONArray(value).toString()

    @TypeConverter
    fun toList(value: String): List<String> {
        val array = JSONArray(value)
        return List(array.length()) { array.getString(it) }
    }
}

/** Overpass から取得済みのタイルと取得時刻。山が0件のタイルも記録してオフライン時に再取得しない。 */
@Entity(tableName = "fetched_tiles", primaryKeys = ["tileLat", "tileLon"])
data class FetchedTileEntity(
    val tileLat: Int,
    val tileLon: Int,
    val fetchedAtMillis: Long,
)

@Dao
interface MountainDao {
    @Query(
        "SELECT * FROM mountains WHERE latitude BETWEEN :south AND :north AND longitude BETWEEN :west AND :east"
    )
    suspend fun mountainsIn(south: Double, west: Double, north: Double, east: Double): List<MountainEntity>

    /** 保存しているすべての山。山の検索で使う(全国分でも 1.5 万件ほど)。 */
    @Query("SELECT * FROM mountains")
    suspend fun allMountains(): List<MountainEntity>

    /** タグのある山の、タグの列(JSON の配列の文字列)。山のグループの一覧を作るのに使う。 */
    @Query("SELECT tags FROM mountains WHERE tags != '[]'")
    suspend fun tagColumns(): List<String>

    @Query(
        "SELECT * FROM fetched_tiles WHERE tileLat BETWEEN :minLat AND :maxLat AND tileLon BETWEEN :minLon AND :maxLon"
    )
    suspend fun tilesIn(minLat: Int, maxLat: Int, minLon: Int, maxLon: Int): List<FetchedTileEntity>

    @Query("DELETE FROM mountains WHERE tileLat = :tileLat AND tileLon = :tileLon")
    suspend fun deleteMountainsInTile(tileLat: Int, tileLon: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMountains(mountains: List<MountainEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTiles(tiles: List<FetchedTileEntity>)

    @Query("SELECT * FROM fetched_tiles")
    suspend fun allTiles(): List<FetchedTileEntity>

    @Query("DELETE FROM fetched_tiles WHERE tileLat = :tileLat AND tileLon = :tileLon")
    suspend fun deleteTile(tileLat: Int, tileLon: Int)

    @Query("SELECT COUNT(*) FROM mountains")
    suspend fun countMountains(): Int

    @Query("SELECT COUNT(*) FROM fetched_tiles")
    suspend fun countTiles(): Int

    @Query("DELETE FROM mountains")
    suspend fun deleteAllMountains()

    @Query("DELETE FROM fetched_tiles")
    suspend fun deleteAllTiles()

    @Transaction
    suspend fun clearAll() {
        deleteAllMountains()
        deleteAllTiles()
    }

    /** タイルの山と取得済みの記録を消す。次に表示するときは未取得として扱う。 */
    @Transaction
    suspend fun deleteTiles(tiles: List<Tile>) {
        tiles.forEach {
            deleteMountainsInTile(it.latIndex, it.lonIndex)
            deleteTile(it.latIndex, it.lonIndex)
        }
    }

    @Transaction
    suspend fun replaceTiles(tiles: List<FetchedTileEntity>, mountains: List<MountainEntity>) {
        tiles.forEach { deleteMountainsInTile(it.tileLat, it.tileLon) }
        insertMountains(mountains)
        insertTiles(tiles)
    }
}

/**
 * 山データのキャッシュ。版([VERSION])を上げるときは、同じ PR で古い版からの移行も入れる(MountainMigrations.kt)。
 */
@Database(
    entities = [MountainEntity::class, FetchedTileEntity::class],
    version = MountainDatabase.VERSION,
    autoMigrations = [
        // 版 5 の項目の列を足す。足した列は、次の起動で端末に残した gz から作り直すときに埋まる。
        AutoMigration(from = 1, to = 2),
    ],
)
@TypeConverters(StringListConverter::class)
abstract class MountainDatabase : RoomDatabase() {
    abstract fun mountainDao(): MountainDao

    companion object {
        const val FILE_NAME = "mountains.db"

        /** DB の版。テーブルや列を変えたら上げる。書き出したスキーマは android/app/schemas/ に版ごとに残す。 */
        const val VERSION = 2

        /**
         * 端末の DB がこのアプリより新しい版のとき(新しい版の開発版を入れたあとで古い版に戻したとき)は、
         * 落ちずに山データのキャッシュを作り直す。中身は取り直せるので、そのときは [onCacheReset] を呼ぶ。
         */
        fun create(context: Context, onCacheReset: () -> Unit): MountainDatabase =
            Room.databaseBuilder(context, MountainDatabase::class.java, FILE_NAME)
                .addMigrations(*MOUNTAIN_MIGRATIONS)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) = onCacheReset()
                })
                .build()
    }
}
