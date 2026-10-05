package io.github.shohei0205.yamamuki.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * android/app/schemas/ に残した過去の版のスキーマから DB を作り、今のアプリの DB として開けるかを確かめる。
 * 版を上げたのに移行を入れ忘れると、Room が開くときに落ちるのでテストも失敗する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MountainDatabaseMigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    // Gradle の単体テストは app/ を作業ディレクトリにして動く。
    private val schemaDir = File("schemas/${MountainDatabase::class.java.name}")

    @Test
    fun currentVersionSchemaIsExported() {
        assertTrue(
            "今の版(${MountainDatabase.VERSION})のスキーマが android/app/schemas/ にない",
            File(schemaDir, "${MountainDatabase.VERSION}.json").exists(),
        )
    }

    @Test
    fun everyVersionOpensAsCurrentAndKeepsMountains() {
        val versions = schemaDir.listFiles().orEmpty()
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .sorted()
        assertTrue("android/app/schemas/ にスキーマがない", versions.isNotEmpty())

        for (version in versions) {
            val name = "from-$version.db"
            createDatabase(name, version)

            val database = Room.databaseBuilder(context, MountainDatabase::class.java, name)
                .addMigrations(*MOUNTAIN_MIGRATIONS)
                .build()
            try {
                val db = database.openHelper.readableDatabase
                db.query("SELECT name FROM mountains WHERE osmId = 1").use {
                    assertTrue("版 $version から移したあとに山が残っていない", it.moveToFirst())
                    assertEquals("富士山", it.getString(0))
                }
                db.query("SELECT COUNT(*) FROM fetched_tiles").use {
                    it.moveToFirst()
                    assertEquals("版 $version から移したあとに取得済みのタイルが残っていない", 1, it.getInt(0))
                }
            } finally {
                database.close()
            }
        }
    }

    /** その版のスキーマ JSON のとおりに DB を作り、事前ダウンロードした山を 1 件入れておく。 */
    private fun createDatabase(name: String, version: Int) {
        val schema = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.delete()

        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            schema.optJSONArray("views")?.let { views ->
                for (i in 0 until views.length()) {
                    val view = views.getJSONObject(i)
                    db.execSQL(view.getString("createSql").replace("\${VIEW_NAME}", view.getString("viewName")))
                }
            }
            val setupQueries = schema.getJSONArray("setupQueries")
            for (i in 0 until setupQueries.length()) db.execSQL(setupQueries.getString(i))

            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val sample = SAMPLE_ROWS[entity.getString("tableName")] ?: continue
                insertSample(db, entity, sample, version)
            }
            db.version = version
        }
    }

    /**
     * 見本の行のうち、その版にある列だけを入れる。見本に無い列は、省略できる列(NULL を許すか初期値がある)なら
     * 飛ばし、省略できない必須の列ならテストを直すよう知らせる。
     */
    private fun insertSample(db: SQLiteDatabase, entity: JSONObject, sample: Map<String, Any>, version: Int) {
        val table = entity.getString("tableName")
        val fields = entity.getJSONArray("fields")
        val columns = mutableListOf<String>()
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            val column = field.getString("columnName")
            when {
                column in sample -> columns += column
                field.optBoolean("notNull") && !field.has("defaultValue") -> fail("版 $version の $table.$column の見本の値を SAMPLE_ROWS に足す")
            }
        }
        db.execSQL(
            "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})",
            columns.map { sample.getValue(it) }.toTypedArray(),
        )
    }

    private companion object {
        /** 事前ダウンロードした山と、その取得済みのタイル。移行のあとも残っていなければならない。 */
        val SAMPLE_ROWS: Map<String, Map<String, Any>> = mapOf(
            "mountains" to mapOf(
                "osmId" to 1L,
                "name" to "富士山",
                "latitude" to 35.3606,
                "longitude" to 138.7274,
                "elevationM" to 3776.0,
                "tileLat" to 250,
                "tileLon" to 637,
            ),
            "fetched_tiles" to mapOf(
                "tileLat" to 250,
                "tileLon" to 637,
                "fetchedAtMillis" to 1L,
            ),
        )
    }
}
