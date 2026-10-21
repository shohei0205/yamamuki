package io.github.shohei0205.yamamuki.data

import android.content.Context
import io.github.shohei0205.yamamuki.core.FilePeakDataArchive
import io.github.shohei0205.yamamuki.core.InstalledPeakData
import io.github.shohei0205.yamamuki.core.PeakDataArchive
import io.github.shohei0205.yamamuki.core.PeakDataset
import java.io.File

/**
 * 取り込み済みの山データ([dataset])の版を、端末内(SharedPreferences)に覚えておく。
 * 取り込んだ manifest とデータ本体(gz)も 1 組だけ残す([archive])。山データの DB と同じく、自動バックアップには入れない。
 * osm-peaks は 0.7.0 までと同じ置き場所(peak_data と peak-data/)を使い、アプリを上げても取り直さない。
 * ほかの種類は peak_data_<種類> と peak-data/<種類>/ に置く。
 */
class PeakDataStore(context: Context, val dataset: PeakDataset) {
    private val prefs = context.getSharedPreferences(prefsName(dataset), Context.MODE_PRIVATE)
    private val archiveDirectory = File(context.noBackupFilesDir, archivePath(dataset))

    val archive: PeakDataArchive = FilePeakDataArchive(archiveDirectory, dataset)

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
            manifestSha256 = prefs.getString(KEY_MANIFEST_SHA256, null),
            experimental = prefs.getBoolean(KEY_EXPERIMENTAL, false),
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
            .putString(KEY_MANIFEST_SHA256, data.manifestSha256)
            .putBoolean(KEY_EXPERIMENTAL, data.experimental)
            .apply()
    }

    /** 自動の確認で新しい版が見つかり、まだ取り込んでいない。アプリを開き直しても方位盤のラベルを出し続ける。 */
    fun updateFound(): Boolean = prefs.getBoolean(KEY_UPDATE_FOUND, false)

    /** 更新を確かめた結果を覚える。 */
    fun saveUpdateFound(updateFound: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_FOUND, updateFound).apply()
    }

    /**
     * 記録と、残した manifest と gz を消す。記録は apply() だと書き込みが後回しになり、直後にアプリが終わると
     * 記録だけが残って取り直せなくなるので、commit() で書き終えてから戻る。
     * 自動の確認の日時(osm-peaks の置き場所に入れている)は、種類をまたいだものなので残す。
     */
    fun clear() {
        prefs.edit().apply { RECORD_KEYS.forEach(::remove) }.commit()
        File(archiveDirectory, FilePeakDataArchive.MANIFEST_NAME).delete()
        File(archiveDirectory, dataset.fileName).delete()
    }

    internal companion object {
        const val KEY_VERSION = "version"
        const val KEY_SOURCE_TIMESTAMP = "source_timestamp"
        const val KEY_MOUNTAIN_COUNT = "mountain_count"
        const val KEY_ETAG = "manifest_etag"
        const val KEY_INSTALLED_AT = "installed_at"
        const val KEY_READER_VERSION = "reader_version"
        const val KEY_DATA_SHA256 = "data_sha256"
        const val KEY_MANIFEST_SHA256 = "manifest_sha256"
        const val KEY_EXPERIMENTAL = "experimental"
        const val KEY_UPDATE_FOUND = "update_found"

        val RECORD_KEYS = listOf(
            KEY_VERSION, KEY_SOURCE_TIMESTAMP, KEY_MOUNTAIN_COUNT, KEY_ETAG, KEY_INSTALLED_AT, KEY_READER_VERSION,
            KEY_DATA_SHA256, KEY_MANIFEST_SHA256, KEY_EXPERIMENTAL, KEY_UPDATE_FOUND,
        )

        /** osm-peaks は 0.7.0 までの名前のまま。 */
        fun prefsName(dataset: PeakDataset) = if (dataset == PeakDataset.OSM_PEAKS) "peak_data" else "peak_data_${dataset.id}"

        fun archivePath(dataset: PeakDataset) = if (dataset == PeakDataset.OSM_PEAKS) "peak-data" else "peak-data/${dataset.id}"
    }
}

/** 種類ごとの [PeakDataStore] と、種類をまたいだ記録(自動の確認の日時)。 */
class PeakDataStores(context: Context) {
    private val stores = PeakDataset.entries.associateWith { PeakDataStore(context, it) }

    /** 0.7.0 までと同じ置き場所に残し、アプリを上げても前回の確認から数え直さない。 */
    private val prefs = context.getSharedPreferences(PeakDataStore.prefsName(PeakDataset.OSM_PEAKS), Context.MODE_PRIVATE)

    fun of(dataset: PeakDataset): PeakDataStore = stores.getValue(dataset)

    /** 自動の確認で、最後に確かめられた日時。まだ確かめていなければ null。 */
    fun autoCheckedAtMillis(): Long? = prefs.getLong(KEY_AUTO_CHECKED_AT, -1).takeIf { it >= 0 }

    fun saveAutoCheckedAt(millis: Long) {
        prefs.edit().putLong(KEY_AUTO_CHECKED_AT, millis).apply()
    }

    /** すべての種類の記録と、残した manifest と gz を消す(DB を作り直したとき)。 */
    fun clearAll() = stores.values.forEach { it.clear() }

    private companion object {
        const val KEY_AUTO_CHECKED_AT = "auto_checked_at"
    }
}
