// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import io.github.shohei0205.yamamuki.core.InstalledSurfaceData
import io.github.shohei0205.yamamuki.core.SurfaceDataException
import io.github.shohei0205.yamamuki.core.SurfaceDataset
import io.github.shohei0205.yamamuki.core.SurfaceManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SurfaceDataStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dataset = SurfaceDataset.ESA_WORLDCOVER_Z9
    private val manifest = SurfaceManifest(
        version = "20261112T164326Z-43261736372-1",
        downloadUrl = "https://example.com/esa-worldcover-z9.sqlite3",
        sha256 = "0".repeat(64),
        sizeBytes = 1,
        datasetId = "esa-worldcover-japan",
    )
    private val installed = InstalledSurfaceData(
        version = manifest.version,
        sizeBytes = 17_572_352,
        datasetId = manifest.datasetId,
        manifestEtag = "etag",
        installedAtMillis = 1_000,
        manifestSha256 = "a".repeat(64),
        experimental = true,
    )

    /** 山むきの SQLite コンテナの形をした、小さな地表データ。 */
    private fun container(
        name: String,
        applicationId: Int = 0x594D4442,
        userVersion: Int = 1,
        datasetId: String = "esa-worldcover-japan",
        kind: String = "surface",
        formatVersion: Int = 1,
        levels: Int = 2,
    ): File {
        val file = File(context.cacheDir, name)
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA application_id = $applicationId")
            db.version = userVersion
            db.execSQL("CREATE TABLE datasets (dataset_key INTEGER PRIMARY KEY, id TEXT, kind TEXT, title TEXT, data_version TEXT, format_version INTEGER, metadata_json TEXT)")
            db.execSQL("CREATE TABLE surface_levels (dataset_key INTEGER, layer_key INTEGER, z INTEGER, lod TEXT, tile_pixels INTEGER)")
            db.execSQL("INSERT INTO datasets VALUES (1, ?, ?, '地表', '1', ?, '{}')", arrayOf<Any>(datasetId, kind, formatVersion))
            repeat(levels) { db.execSQL("INSERT INTO surface_levels VALUES (1, 1, ?, ?, 512)", arrayOf<Any>(5 + it, "z${5 + it}")) }
        }
        return file
    }

    private fun validate(file: File) = runBlocking { SurfaceDatabaseValidator.validate(file, manifest) }

    @Test
    fun validatorAcceptsTheContainerWithTheSurfaceDataset() {
        validate(container("ok.sqlite3"))
    }

    @Test
    fun validatorRejectsOtherFiles() {
        fun rejects(file: File) {
            assertThrows(SurfaceDataException::class.java) { validate(file) }
        }
        rejects(File(context.cacheDir, "text.sqlite3").apply { writeText("SQLite ではないファイル".repeat(100)) })
        rejects(container("other-app.sqlite3", applicationId = 1))
        rejects(container("new-container.sqlite3", userVersion = 2))
        rejects(container("other-dataset.sqlite3", datasetId = "another"))
        rejects(container("points.sqlite3", kind = "points"))
        rejects(container("new-format.sqlite3", formatVersion = 2))
        rejects(container("no-levels.sqlite3", levels = 0))
    }

    @Test
    fun storeKeepsTheRecordWhileTheDatabaseExists() = runBlocking {
        val store = SurfaceDataStore(context, dataset)
        assertNull(store.load())
        store.save(installed)
        // 記録があっても、データ本体が無ければ取得していないものとして扱う。
        assertNull(store.load())

        store.files.directory.mkdirs()
        store.files.database.writeText("data")
        store.files.manifest.writeText("{}")
        assertEquals(installed, SurfaceDataStore(context, dataset).load())
        assertTrue(store.files.database.path.contains("no_backup"))

        store.clear()
        assertNull(store.load())
        assertFalse(store.files.database.exists())
        assertFalse(store.files.manifest.exists())
        // データ本体だけが戻っても、記録が無ければ取得していない。
        store.files.database.writeText("data")
        assertNull(store.load())
    }
}
