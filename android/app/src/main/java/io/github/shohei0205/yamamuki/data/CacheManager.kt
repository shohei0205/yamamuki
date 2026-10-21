package io.github.shohei0205.yamamuki.data

import android.content.Context
import io.github.shohei0205.yamamuki.core.PeakDataset
import io.github.shohei0205.yamamuki.core.Tile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 山データの種類ごとの、保存データの状況。 */
data class CacheInfo(
    val mountainCount: Int,
    val tileCount: Int,
    /** データベースのファイル(WAL などの付随ファイルを含む)の合計。 */
    val sizeBytes: Long,
)

/** 山データのキャッシュ(Room)の状況確認と消去。どれも山データの種類を 1 つ選んで行う。 */
class CacheManager(private val context: Context, private val database: MountainDatabase) {
    private val dao = database.mountainDao()

    /** [dataset] の山とタイルの数。大きさは DB のファイル全体(ほかの種類の分も含む)。 */
    suspend fun info(dataset: PeakDataset): CacheInfo = CacheInfo(
        mountainCount = dao.countMountains(dataset.id),
        tileCount = dao.countTiles(dataset.id),
        sizeBytes = withContext(Dispatchers.IO) { databaseFileBytes() },
    )

    /** [dataset] の山と取得済みタイルを消す([keep] のタイルは残す)。 */
    suspend fun clear(dataset: PeakDataset, keep: Set<Tile> = emptySet()) {
        if (keep.isEmpty()) {
            dao.clearAll(dataset.id)
        } else {
            dao.deleteTiles(dataset.id, dao.allTiles(dataset.id).map { Tile(it.tileLat, it.tileLon) }.filter { it !in keep })
        }
        compact()
    }

    /** [dataset] の指定したタイルだけを消す(事前ダウンロードした地域の削除)。 */
    suspend fun removeTiles(dataset: PeakDataset, tiles: Collection<Tile>) {
        if (tiles.isEmpty()) return
        dao.deleteTiles(dataset.id, tiles.toList())
        compact()
    }

    private suspend fun compact() {
        // 行を消しただけではファイルは縮まないので、VACUUM で詰める。WAL では詰めた結果も WAL に書かれ、
        // 書き戻すまで本体は大きいままなので、VACUUM のあとに書き戻して WAL も空にする。
        // query() はカーソルを読み進めるまで実行されないので、moveToFirst() で実行させる。
        withContext(Dispatchers.IO) {
            val db = database.openHelper.writableDatabase
            db.execSQL("VACUUM")
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
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
