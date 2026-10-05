package io.github.shohei0205.yamamuki.data

import android.content.Context
import io.github.shohei0205.yamamuki.core.Tile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 設定画面に出すキャッシュの状況。 */
data class CacheInfo(
    val mountainCount: Int,
    val tileCount: Int,
    /** データベースのファイル(WAL などの付随ファイルを含む)の合計。 */
    val sizeBytes: Long,
)

/** 山データのキャッシュ(Room)の状況確認と消去。 */
class CacheManager(private val context: Context, private val database: MountainDatabase) {
    private val dao = database.mountainDao()

    suspend fun info(): CacheInfo = CacheInfo(
        mountainCount = dao.countMountains(),
        tileCount = dao.countTiles(),
        sizeBytes = withContext(Dispatchers.IO) { databaseFileBytes() },
    )

    /** 山と取得済みタイルを消す([keep] のタイルは残す)。次の表示で現在地周辺を取り直す。 */
    suspend fun clear(keep: Set<Tile> = emptySet()) {
        if (keep.isEmpty()) {
            dao.clearAll()
        } else {
            dao.deleteTiles(dao.allTiles().map { Tile(it.tileLat, it.tileLon) }.filter { it !in keep })
        }
        compact()
    }

    /** 指定したタイルだけを消す(事前ダウンロードした地域の削除)。 */
    suspend fun removeTiles(tiles: Collection<Tile>) {
        if (tiles.isEmpty()) return
        dao.deleteTiles(tiles.toList())
        compact()
    }

    private suspend fun compact() {
        // 行を消しただけではファイルは縮まないので、WAL を書き戻してから VACUUM で詰める。
        withContext(Dispatchers.IO) {
            val db = database.openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            db.execSQL("VACUUM")
        }
    }

    private fun databaseFileBytes(): Long {
        val main = context.getDatabasePath(MountainDatabase.FILE_NAME)
        return listOf("", "-wal", "-shm", "-journal")
            .map { java.io.File(main.path + it) }
            .filter { it.exists() }
            .sumOf { it.length() }
    }
}
