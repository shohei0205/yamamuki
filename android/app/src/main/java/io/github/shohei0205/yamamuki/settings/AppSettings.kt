package io.github.shohei0205.yamamuki.settings

import android.content.Context
import androidx.annotation.StringRes
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.core.DialGeometry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 設定画面で変えられる値。 */
data class Settings(
    /** この標高(m)以上の山だけ方位盤に出す。0 なら絞り込まない。 */
    val minElevationM: Int = 0,
    /** 方位盤を表示している間は画面を消さない。 */
    val keepScreenOn: Boolean = false,
    /** 方位盤に一度に出す山の上限。 */
    val maxPeaks: Int = 40,
    /** 方位盤の文字の大きさ(標準 = 1.0 に対する倍率)。 */
    val textScale: Float = 1.0f,
    /** 起動時の表示範囲(現在地から画面上端までの距離)。 */
    val initialRangeKm: Int = DialGeometry.DEFAULT_RANGE_KM.toInt(),
    /** 現在地と方位をどのくらいこまめに測るか。こまめなほど電池を使う。 */
    val sensorPrecision: SensorPrecision = SensorPrecision.STANDARD,
    /** 初回起動時の「山データを取得しますか」に答えた。答えるまでは位置情報の許可を求めない(ダイアログを重ねない)。 */
    val peakDataAsked: Boolean = false,
) {
    companion object {
        val TEXT_SCALES = listOf(0.85f, 1.0f, 1.2f, 1.4f)
        val INITIAL_RANGES_KM = listOf(5, 10, 15, 20, 30, 50)
        val MAX_PEAKS_RANGE = 10..100
    }
}

/**
 * 現在地と方位を測る頻度の段階。設定画面のスライダーで左(省電力)から右(高精度)へ並ぶ。
 * [locationIntervalMs] は位置の更新を頼む間隔、[headingPeriodMs] は方位センサーの値を受け取る間隔。
 */
enum class SensorPrecision(@StringRes val label: Int, val locationIntervalMs: Long, val headingPeriodMs: Int) {
    SAVER(R.string.settings_sensor_precision_saver, 30_000L, 100),
    STANDARD(R.string.settings_sensor_precision_standard, 10_000L, 60),
    PRECISE(R.string.settings_sensor_precision_precise, 5_000L, 20),
}

/** [Settings] を端末内(SharedPreferences)に保存する。 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putInt(KEY_MIN_ELEVATION, next.minElevationM)
            .putBoolean(KEY_KEEP_SCREEN_ON, next.keepScreenOn)
            .putInt(KEY_MAX_PEAKS, next.maxPeaks)
            .putFloat(KEY_TEXT_SCALE, next.textScale)
            .putInt(KEY_INITIAL_RANGE, next.initialRangeKm)
            .putInt(KEY_SENSOR_PRECISION, next.sensorPrecision.ordinal)
            .putBoolean(KEY_PEAK_DATA_ASKED, next.peakDataAsked)
            .apply()
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            minElevationM = prefs.getInt(KEY_MIN_ELEVATION, d.minElevationM),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, d.keepScreenOn),
            maxPeaks = prefs.getInt(KEY_MAX_PEAKS, d.maxPeaks),
            textScale = prefs.getFloat(KEY_TEXT_SCALE, d.textScale),
            initialRangeKm = prefs.getInt(KEY_INITIAL_RANGE, d.initialRangeKm),
            sensorPrecision = SensorPrecision.entries.getOrNull(prefs.getInt(KEY_SENSOR_PRECISION, -1))
                ?: d.sensorPrecision,
            peakDataAsked = prefs.getBoolean(KEY_PEAK_DATA_ASKED, d.peakDataAsked),
        )
    }

    private companion object {
        const val KEY_MIN_ELEVATION = "min_elevation_m"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_MAX_PEAKS = "max_peaks"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_INITIAL_RANGE = "initial_range_km"
        const val KEY_SENSOR_PRECISION = "sensor_precision"
        const val KEY_PEAK_DATA_ASKED = "peak_data_asked"
    }
}
