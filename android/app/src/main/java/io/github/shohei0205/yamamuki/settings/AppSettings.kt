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
    /** 方位盤の 1 画面に出す山名の上限。山名を出さない山はアイコンだけで描く。 */
    val maxPeaks: Int = 15,
    /** 方位盤の文字の大きさ(標準 = 1.0 に対する倍率)。 */
    val textScale: Float = 1.0f,
    /** 方位盤に引く補助線の種類。 */
    val gridStyle: GridStyle = GridStyle.RINGS,
    /** 起動時の表示範囲(現在地から画面上端までの距離)。 */
    val initialRangeKm: Int = DialGeometry.DEFAULT_RANGE_KM.toInt(),
    /** 現在地と方位をどのくらいこまめに測るか。こまめなほど電池を使う。 */
    val sensorPrecision: SensorPrecision = SensorPrecision.STANDARD,
    /** 初回起動時の「山データを取得しますか」に答えた。答えるまでは位置情報の許可を求めない(ダイアログを重ねない)。 */
    val peakDataAsked: Boolean = false,
    /** 方位盤に出す山のグループ(地点のタグ)。どれかを持つ山だけを出す。空なら絞り込まない。 */
    val peakGroups: Set<String> = emptySet(),
    /** アプリを開いたときに、新しい山データがあるかを自動で確かめる(30 日に 1 回、取り込みはしない)。 */
    val autoCheckPeakData: Boolean = false,
) {
    companion object {
        val TEXT_SCALES = listOf(0.85f, 1.0f, 1.2f, 1.4f)
        val INITIAL_RANGES_KM = listOf(5, 10, 15, 20, 30, 50)
        val MAX_PEAKS_RANGE = 5..30
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

/** 方位盤に引く補助線の種類。設定画面ではこの順に並ぶ。 */
enum class GridStyle(@StringRes val label: Int) {
    /** 距離の円(同心円)と距離の数字。 */
    RINGS(R.string.settings_grid_style_rings),
    /** 同心円に、45° ごとの方位線と、端末を向けている方向の線を足す。 */
    BEARING_LINES(R.string.settings_grid_style_bearing_lines),
    /** 同心円の内側に 10° ごとの刻みを入れる。 */
    RING_TICKS(R.string.settings_grid_style_ring_ticks),
    /** 補助線を引かない(距離の帯の色分けだけ残す)。 */
    NONE(R.string.settings_grid_style_none),
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
            .putString(KEY_GRID_STYLE, next.gridStyle.name)
            .putInt(KEY_INITIAL_RANGE, next.initialRangeKm)
            .putInt(KEY_SENSOR_PRECISION, next.sensorPrecision.ordinal)
            .putBoolean(KEY_PEAK_DATA_ASKED, next.peakDataAsked)
            .putStringSet(KEY_PEAK_GROUPS, next.peakGroups)
            .putBoolean(KEY_AUTO_CHECK_PEAK_DATA, next.autoCheckPeakData)
            .apply()
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            minElevationM = prefs.getInt(KEY_MIN_ELEVATION, d.minElevationM),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, d.keepScreenOn),
            // 以前は山の数(10〜100)だった。山名の数に変えたので、今の範囲に収める。
            maxPeaks = prefs.getInt(KEY_MAX_PEAKS, d.maxPeaks).coerceIn(Settings.MAX_PEAKS_RANGE),
            textScale = prefs.getFloat(KEY_TEXT_SCALE, d.textScale),
            gridStyle = GridStyle.entries.firstOrNull { it.name == prefs.getString(KEY_GRID_STYLE, null) } ?: d.gridStyle,
            initialRangeKm = prefs.getInt(KEY_INITIAL_RANGE, d.initialRangeKm),
            sensorPrecision = SensorPrecision.entries.getOrNull(prefs.getInt(KEY_SENSOR_PRECISION, -1))
                ?: d.sensorPrecision,
            peakDataAsked = prefs.getBoolean(KEY_PEAK_DATA_ASKED, d.peakDataAsked),
            // getStringSet が返す集合は書き換えてはいけないので写す。
            peakGroups = prefs.getStringSet(KEY_PEAK_GROUPS, null)?.toSet() ?: d.peakGroups,
            autoCheckPeakData = prefs.getBoolean(KEY_AUTO_CHECK_PEAK_DATA, d.autoCheckPeakData),
        )
    }

    private companion object {
        const val KEY_MIN_ELEVATION = "min_elevation_m"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_MAX_PEAKS = "max_peaks"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_GRID_STYLE = "grid_style"
        const val KEY_INITIAL_RANGE = "initial_range_km"
        const val KEY_SENSOR_PRECISION = "sensor_precision"
        const val KEY_PEAK_DATA_ASKED = "peak_data_asked"
        const val KEY_PEAK_GROUPS = "peak_groups"
        const val KEY_AUTO_CHECK_PEAK_DATA = "auto_check_peak_data"
    }
}
