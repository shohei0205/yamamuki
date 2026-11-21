// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.github.shohei0205.yamamuki.core.InstalledSurfaceData
import io.github.shohei0205.yamamuki.core.SurfaceContainerInfo
import io.github.shohei0205.yamamuki.core.SurfaceData
import io.github.shohei0205.yamamuki.core.SurfaceDataFiles
import io.github.shohei0205.yamamuki.core.SurfaceDataset
import io.github.shohei0205.yamamuki.core.SurfaceManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 取り込み済みの地表データ([dataset])の版を、端末内(SharedPreferences)に覚えておく。
 * データ本体(SQLite)と取り込んだ manifest は surface-data/<種類>/ に置く([files])。山データの DB とは別のファイルで、
 * Room には入れない。取り直せるので、山データと同じく自動バックアップには入れない。
 */
class SurfaceDataStore(context: Context, val dataset: SurfaceDataset) {
    private val prefs = context.getSharedPreferences("surface_data_${dataset.id}", Context.MODE_PRIVATE)
    val files = SurfaceDataFiles(File(context.noBackupFilesDir, "surface-data/${dataset.id}"), dataset)

    /** 取り込み済みの記録。記録があってもデータ本体が無ければ(消去の途中でアプリが終わったときなど)、null。 */
    fun load(): InstalledSurfaceData? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        if (!files.database.isFile) return null
        return InstalledSurfaceData(
            version = version,
            sizeBytes = prefs.getLong(KEY_SIZE_BYTES, 0),
            datasetId = prefs.getString(KEY_DATASET_ID, null).orEmpty(),
            manifestEtag = prefs.getString(KEY_ETAG, null),
            installedAtMillis = prefs.getLong(KEY_INSTALLED_AT, 0),
            manifestSha256 = prefs.getString(KEY_MANIFEST_SHA256, null).orEmpty(),
            experimental = prefs.getBoolean(KEY_EXPERIMENTAL, false),
        )
    }

    fun save(data: InstalledSurfaceData) {
        prefs.edit()
            .putString(KEY_VERSION, data.version)
            .putLong(KEY_SIZE_BYTES, data.sizeBytes)
            .putString(KEY_DATASET_ID, data.datasetId)
            .putString(KEY_ETAG, data.manifestEtag)
            .putLong(KEY_INSTALLED_AT, data.installedAtMillis)
            .putString(KEY_MANIFEST_SHA256, data.manifestSha256)
            .putBoolean(KEY_EXPERIMENTAL, data.experimental)
            .apply()
    }

    /** 記録を先に消してから、データ本体と manifest を消す。途中でアプリが終わっても、記録だけが残らないように。 */
    suspend fun clear() {
        prefs.edit().clear().commit()
        files.delete()
    }

    private companion object {
        const val KEY_VERSION = "version"
        const val KEY_SIZE_BYTES = "size_bytes"
        const val KEY_DATASET_ID = "dataset_id"
        const val KEY_ETAG = "manifest_etag"
        const val KEY_INSTALLED_AT = "installed_at"
        const val KEY_MANIFEST_SHA256 = "manifest_sha256"
        const val KEY_EXPERIMENTAL = "experimental"
    }
}

/** 種類ごとの [SurfaceDataStore]。 */
class SurfaceDataStores(context: Context) {
    private val stores = SurfaceDataset.entries.associateWith { SurfaceDataStore(context, it) }

    fun of(dataset: SurfaceDataset): SurfaceDataStore = stores.getValue(dataset)
}

/** ダウンロードした地表データの SQLite を、置き換える前に開いて確かめる。 */
object SurfaceDatabaseValidator {
    /**
     * [file] が山むきの SQLite コンテナで、[manifest] に書かれた地表のデータセットとタイルを収録しているかを確かめる。
     * 違えば [SurfaceDataException] を投げる。
     */
    suspend fun validate(file: File, manifest: SurfaceManifest) = withContext(Dispatchers.IO) {
        val container = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val applicationId = db.longOf("PRAGMA application_id")
                val containerVersion = db.longOf("PRAGMA user_version")
                // 山むきの SQLite でなければ、地表の表があるとは限らないので数えない。
                val levels = if (applicationId == SurfaceData.CONTAINER_APPLICATION_ID.toLong()) {
                    db.longOf(
                        SurfaceData.LEVEL_COUNT_SQL,
                        manifest.datasetId, SurfaceData.DATASET_KIND, SurfaceData.SURFACE_FORMAT_VERSION.toString(),
                    )
                } else {
                    0L
                }
                SurfaceContainerInfo(applicationId, containerVersion, levels)
            }
        } catch (e: SQLiteException) {
            null
        }
        SurfaceData.requireContainer(container, manifest)
    }

    private fun SQLiteDatabase.longOf(sql: String, vararg args: String): Long =
        rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }
}
