package io.github.shohei0205.yamamuki.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 版 2 → 3: 山データの種類(dataset)の列を足し、山は (dataset, pointId) で見分けるようにする(osm-peaks のほかに gsi-peaks を持つため)。
 * 主キーが変わるので、テーブルを作り直して行を写す。今ある山と取得済みのタイルは、すべて osm-peaks のものとして残す。
 * 地点の ID は、読み込み処理と同じく OSM のノード番号から「osm:<番号>」を作る(版 5 までの pointId は形が決まっていないので使わない)。
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mountains_new` (`dataset` TEXT NOT NULL, `pointId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `elevationM` REAL, `tileLat` INTEGER NOT NULL, " +
                "`tileLon` INTEGER NOT NULL, `nameReading` TEXT, `aliases` TEXT NOT NULL DEFAULT '[]', `wikipediaUrl` TEXT, " +
                "`wikidataUrl` TEXT, `tags` TEXT NOT NULL DEFAULT '[]', `graphicSvg` TEXT, `graphicScale` REAL, " +
                "PRIMARY KEY(`dataset`, `pointId`))",
        )
        db.execSQL(
            "INSERT INTO `mountains_new` (`dataset`, `pointId`, `name`, `latitude`, `longitude`, `elevationM`, `tileLat`, `tileLon`, " +
                "`nameReading`, `aliases`, `wikipediaUrl`, `wikidataUrl`, `tags`, `graphicSvg`, `graphicScale`) " +
                "SELECT '$OSM_PEAKS', 'osm:' || `osmId`, `name`, `latitude`, `longitude`, `elevationM`, `tileLat`, `tileLon`, " +
                "`nameReading`, `aliases`, `wikipediaUrl`, `wikidataUrl`, `tags`, `graphicSvg`, `graphicScale` FROM `mountains`",
        )
        db.execSQL("DROP TABLE `mountains`")
        db.execSQL("ALTER TABLE `mountains_new` RENAME TO `mountains`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mountains_dataset_latitude_longitude` ON `mountains` (`dataset`, `latitude`, `longitude`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mountains_dataset_tileLat_tileLon` ON `mountains` (`dataset`, `tileLat`, `tileLon`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `fetched_tiles_new` (`dataset` TEXT NOT NULL, `tileLat` INTEGER NOT NULL, " +
                "`tileLon` INTEGER NOT NULL, `fetchedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`dataset`, `tileLat`, `tileLon`))",
        )
        db.execSQL(
            "INSERT INTO `fetched_tiles_new` (`dataset`, `tileLat`, `tileLon`, `fetchedAtMillis`) " +
                "SELECT '$OSM_PEAKS', `tileLat`, `tileLon`, `fetchedAtMillis` FROM `fetched_tiles`",
        )
        db.execSQL("DROP TABLE `fetched_tiles`")
        db.execSQL("ALTER TABLE `fetched_tiles_new` RENAME TO `fetched_tiles`")
    }
}

/** 版 3 より前の山は、すべて osm-peaks のもの。 */
private const val OSM_PEAKS = "osm-peaks"

/**
 * 山データの DB を古い版から新しい版へ移す処理のうち、手で書くもの。
 *
 * DB の版([MountainDatabase.VERSION])を上げるときは、同じ PR で移行も入れる。
 * - テーブルや列を足すだけなら、@Database の autoMigrations に AutoMigration(from, to) を足す(ここには書かない)。
 * - 列の名前を変える・消すなど、Room が自動で作れない移行は、ここに Migration(from, to) を足す。
 * - 事前ダウンロードした山(mountains と fetched_tiles)は移行で消さない。取り直せるテーブルだけは作り直してよい。
 * どの移行も MountainDatabaseMigrationTest で、書き出した過去の版のスキーマから確かめる。
 */
val MOUNTAIN_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_2_3)
