package io.github.shohei0205.yamamuki.data

import android.content.Context
import io.github.shohei0205.yamamuki.core.InstalledPeakData

/** 取り込み済みの全国の山データの版を、端末内(SharedPreferences)に覚えておく。 */
class PeakDataStore(context: Context) {
    private val prefs = context.getSharedPreferences("peak_data", Context.MODE_PRIVATE)

    fun load(): InstalledPeakData? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        return InstalledPeakData(
            version = version,
            sourceTimestamp = prefs.getString(KEY_SOURCE_TIMESTAMP, null).orEmpty(),
            mountainCount = prefs.getInt(KEY_MOUNTAIN_COUNT, 0),
            manifestEtag = prefs.getString(KEY_ETAG, null),
            installedAtMillis = prefs.getLong(KEY_INSTALLED_AT, 0),
        )
    }

    fun save(data: InstalledPeakData) {
        prefs.edit()
            .putString(KEY_VERSION, data.version)
            .putString(KEY_SOURCE_TIMESTAMP, data.sourceTimestamp)
            .putInt(KEY_MOUNTAIN_COUNT, data.mountainCount)
            .putString(KEY_ETAG, data.manifestEtag)
            .putLong(KEY_INSTALLED_AT, data.installedAtMillis)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_VERSION = "version"
        const val KEY_SOURCE_TIMESTAMP = "source_timestamp"
        const val KEY_MOUNTAIN_COUNT = "mountain_count"
        const val KEY_ETAG = "manifest_etag"
        const val KEY_INSTALLED_AT = "installed_at"
    }
}
