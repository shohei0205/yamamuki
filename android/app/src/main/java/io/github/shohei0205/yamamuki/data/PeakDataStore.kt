package io.github.shohei0205.yamamuki.data

import android.content.Context
import io.github.shohei0205.yamamuki.core.FilePeakDataArchive
import io.github.shohei0205.yamamuki.core.InstalledPeakData
import io.github.shohei0205.yamamuki.core.PeakDataArchive
import java.io.File

/**
 * 取り込み済みの全国の山データの版を、端末内(SharedPreferences)に覚えておく。
 * 取り込んだ manifest とデータ本体(gz)も 1 組だけ残す([archive])。山データの DB と同じく、自動バックアップには入れない。
 */
class PeakDataStore(context: Context) {
    private val prefs = context.getSharedPreferences("peak_data", Context.MODE_PRIVATE)
    private val archiveDirectory = File(context.noBackupFilesDir, "peak-data")

    val archive: PeakDataArchive = FilePeakDataArchive(archiveDirectory)

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
            .apply()
    }

    /** 自動の確認で、最後に確かめられた日時。まだ確かめていなければ null。 */
    fun autoCheckedAtMillis(): Long? = prefs.getLong(KEY_AUTO_CHECKED_AT, -1).takeIf { it >= 0 }

    /** 自動の確認で新しい版が見つかり、まだ取り込んでいない。アプリを開き直しても方位盤のラベルを出し続ける。 */
    fun updateFound(): Boolean = prefs.getBoolean(KEY_UPDATE_FOUND, false)

    /** 更新を確かめた結果を覚える。[checkedAtMillis] が null なら、確かめた日時は変えない(利用者が頼んだ確認のとき)。 */
    fun saveCheckResult(updateFound: Boolean, checkedAtMillis: Long? = null) {
        val editor = prefs.edit().putBoolean(KEY_UPDATE_FOUND, updateFound)
        if (checkedAtMillis != null) editor.putLong(KEY_AUTO_CHECKED_AT, checkedAtMillis)
        editor.apply()
    }

    /**
     * 記録と、残した manifest と gz を消す。記録は apply() だと書き込みが後回しになり、直後にアプリが終わると
     * 記録だけが残って取り直せなくなるので、commit() で書き終えてから戻る。
     */
    fun clear() {
        prefs.edit().clear().commit()
        File(archiveDirectory, FilePeakDataArchive.MANIFEST_NAME).delete()
        File(archiveDirectory, FilePeakDataArchive.DATA_NAME).delete()
    }

    private companion object {
        const val KEY_VERSION = "version"
        const val KEY_SOURCE_TIMESTAMP = "source_timestamp"
        const val KEY_MOUNTAIN_COUNT = "mountain_count"
        const val KEY_ETAG = "manifest_etag"
        const val KEY_INSTALLED_AT = "installed_at"
        const val KEY_READER_VERSION = "reader_version"
        const val KEY_DATA_SHA256 = "data_sha256"
        const val KEY_MANIFEST_SHA256 = "manifest_sha256"
        const val KEY_AUTO_CHECKED_AT = "auto_checked_at"
        const val KEY_UPDATE_FOUND = "update_found"
    }
}
