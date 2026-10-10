package io.github.shohei0205.yamamuki.data

import android.content.Context
import io.github.shohei0205.yamamuki.core.FilePeakDataArchive
import io.github.shohei0205.yamamuki.core.InstalledPeakData
import io.github.shohei0205.yamamuki.core.PeakDataArchive
import java.io.File

/**
 * 取り込み済みの全国の山データの版を、端末内(SharedPreferences)に覚えておく。
 * 取り込んだデータ本体(gz)も 1 つだけ残す([archive])。山データの DB と同じく、自動バックアップには入れない。
 */
class PeakDataStore(context: Context) {
    private val prefs = context.getSharedPreferences("peak_data", Context.MODE_PRIVATE)
    private val archiveFile = File(context.noBackupFilesDir, "peak-data/osm-peaks.json.gz")

    val archive: PeakDataArchive = FilePeakDataArchive(archiveFile)

    fun load(): InstalledPeakData? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        return InstalledPeakData(
            version = version,
            sourceTimestamp = prefs.getString(KEY_SOURCE_TIMESTAMP, null).orEmpty(),
            mountainCount = prefs.getInt(KEY_MOUNTAIN_COUNT, 0),
            manifestEtag = prefs.getString(KEY_ETAG, null),
            installedAtMillis = prefs.getLong(KEY_INSTALLED_AT, 0),
            readerVersion = prefs.getInt(KEY_READER_VERSION, 0),
            dataSha256 = prefs.getString(KEY_DATA_SHA256, null),
        )
    }

    fun save(data: InstalledPeakData) {
        prefs.edit()
            .putString(KEY_VERSION, data.version)
            .putString(KEY_SOURCE_TIMESTAMP, data.sourceTimestamp)
            .putInt(KEY_MOUNTAIN_COUNT, data.mountainCount)
            .putString(KEY_ETAG, data.manifestEtag)
            .putLong(KEY_INSTALLED_AT, data.installedAtMillis)
            .putInt(KEY_READER_VERSION, data.readerVersion)
            .putString(KEY_DATA_SHA256, data.dataSha256)
            .apply()
    }

    /** 記録と、残した gz を消す。 */
    fun clear() {
        prefs.edit().clear().apply()
        archiveFile.delete()
    }

    private companion object {
        const val KEY_VERSION = "version"
        const val KEY_SOURCE_TIMESTAMP = "source_timestamp"
        const val KEY_MOUNTAIN_COUNT = "mountain_count"
        const val KEY_ETAG = "manifest_etag"
        const val KEY_INSTALLED_AT = "installed_at"
        const val KEY_READER_VERSION = "reader_version"
        const val KEY_DATA_SHA256 = "data_sha256"
    }
}
