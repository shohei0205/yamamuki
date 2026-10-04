package io.github.shohei0205.yamamuki.data

import android.content.Context
import io.github.shohei0205.yamamuki.core.Tile
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
import androidx.sqlite.db.SupportSQLiteDatabase

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
)

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

@Database(entities = [MountainEntity::class, FetchedTileEntity::class], version = 1)
abstract class MountainDatabase : RoomDatabase() {
    abstract fun mountainDao(): MountainDao

    companion object {
        const val FILE_NAME = "mountains.db"

        /**
         * 端末の DB がこのアプリより新しい版のとき(新しい版の開発版を入れたあとで古い版に戻したとき)は、
         * 落ちずに山データのキャッシュを作り直す。中身は取り直せるので、そのときは [onCacheReset] を呼ぶ。
         */
        fun create(context: Context, onCacheReset: () -> Unit): MountainDatabase =
            Room.databaseBuilder(context, MountainDatabase::class.java, FILE_NAME)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) = onCacheReset()
                })
                .build()
    }
}
