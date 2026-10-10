package io.github.shohei0205.yamamuki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.pm.PackageInfoCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shohei0205.yamamuki.Features
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.core.InstalledPeakData
import io.github.shohei0205.yamamuki.core.byteSizeText
import io.github.shohei0205.yamamuki.data.CacheInfo
import io.github.shohei0205.yamamuki.settings.SensorPrecision
import io.github.shohei0205.yamamuki.settings.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 最低標高スライダーの上限と刻み。 */
private const val MAX_MIN_ELEVATION_M = 3000
private const val MIN_ELEVATION_STEP_M = 100

private const val MAX_PEAKS_STEP = 5

/** 設定画面。方位盤の左下の設定ボタンで開く。 */
@Composable
fun SettingsScreen(
    settings: Settings,
    cacheInfo: CacheInfo?,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    peakData: InstalledPeakData?,
    peakDataUpdating: Boolean,
    peakDataNotice: String?,
    onUpdatePeakData: () -> Unit,
    onOpen: () -> Unit,
    onClearCache: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { onOpen() }

    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
            }

            SectionTitle(stringResource(R.string.settings_section_peaks))
            StepSlider(
                value = settings.minElevationM,
                range = 0..MAX_MIN_ELEVATION_M,
                step = MIN_ELEVATION_STEP_M,
                label = { minElevationLabel(it) },
                description = stringResource(R.string.settings_min_elevation_description),
                onChange = { m -> onSettingsChange { it.copy(minElevationM = m) } },
            )
            StepSlider(
                value = settings.maxPeaks,
                range = Settings.MAX_PEAKS_RANGE,
                step = MAX_PEAKS_STEP,
                label = { stringResource(R.string.settings_max_peaks, it) },
                description = stringResource(R.string.settings_max_peaks_description),
                onChange = { n -> onSettingsChange { it.copy(maxPeaks = n) } },
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_display))
            Choice(
                title = stringResource(R.string.settings_text_size),
                options = Settings.TEXT_SCALES,
                selected = settings.textScale,
                label = { textScaleLabel(it) },
                onSelect = { v -> onSettingsChange { it.copy(textScale = v) } },
            )
            Choice(
                title = stringResource(R.string.settings_initial_range),
                options = Settings.INITIAL_RANGES_KM,
                selected = settings.initialRangeKm,
                // 6 つ並ぶと「10km」が収まらないので、単位は見出しに出す。
                label = { "$it" },
                description = stringResource(R.string.settings_initial_range_description),
                onSelect = { v -> onSettingsChange { it.copy(initialRangeKm = v) } },
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_battery))
            SwitchRow(
                title = stringResource(R.string.settings_keep_screen_on),
                description = stringResource(R.string.settings_keep_screen_on_description),
                checked = settings.keepScreenOn,
                onChange = { v -> onSettingsChange { it.copy(keepScreenOn = v) } },
            )
            StepSlider(
                value = settings.sensorPrecision.ordinal,
                range = 0..SensorPrecision.entries.lastIndex,
                step = 1,
                label = {
                    stringResource(R.string.settings_sensor_precision, stringResource(SensorPrecision.entries[it].label))
                },
                description = stringResource(R.string.settings_sensor_precision_description),
                onChange = { i -> onSettingsChange { it.copy(sensorPrecision = SensorPrecision.entries[i]) } },
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_peak_data))
            PeakDataSection(peakData, peakDataUpdating, peakDataNotice, onUpdatePeakData)
            CacheSection(cacheInfo, onClearCache)

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_about))
            AboutSection()
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    // build.gradle.kts の versionName / versionCode。
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_data_license), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun minElevationLabel(m: Int): String =
    if (m == 0) stringResource(R.string.settings_min_elevation_all) else stringResource(R.string.settings_min_elevation_value, m)

@Composable
private fun textScaleLabel(scale: Float): String = when (scale) {
    0.85f -> stringResource(R.string.settings_text_size_small)
    1.0f -> stringResource(R.string.settings_text_size_standard)
    1.2f -> stringResource(R.string.settings_text_size_large)
    1.4f -> stringResource(R.string.settings_text_size_extra_large)
    else -> "×$scale"
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** [step] 刻みのスライダー。ドラッグ中は画面内だけで値を動かし、指を離したときに保存する。見出しは [label] で作る。 */
@Composable
private fun StepSlider(
    value: Int,
    range: IntRange,
    step: Int,
    label: @Composable (Int) -> String,
    description: String,
    onChange: (Int) -> Unit,
) {
    val state = remember(value, step) { StepSliderState(value, step) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label(state.snapped), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = state.dragging,
            onValueChange = { state.dragging = it },
            // タップでは再描画より先に呼ばれるので、その時点の最新値を読む。
            onValueChangeFinished = { onChange(state.snapped) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            // つまみを端まで寄せたとき、OS の「戻る」スワイプ(画面端から)に取られないよう、
            // 余白を空け、スライダーの上ではシステムのジェスチャーを無効にする。
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .systemGestureExclusion(),
        )
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

/** 再描画を待たず、最後に操作した値を保存できるようにする。 */
internal class StepSliderState(value: Int, private val step: Int) {
    var dragging by mutableFloatStateOf(value.toFloat())
    val snapped: Int get() = (dragging / step).roundToInt() * step
}

@Composable
private fun <T> Choice(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    description: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    icon = {},
                ) { Text(label(option), maxLines = 1) }
            }
        }
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 全国の山データの取り込み状況と、取得(最新版の確認)のボタン。 */
@Composable
private fun PeakDataSection(data: InstalledPeakData?, updating: Boolean, notice: String?, onUpdate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_peak_data_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (data == null) {
                stringResource(R.string.settings_peak_data_none)
            } else {
                // 元データの日時は UTC なので、日付だけを出す。
                stringResource(
                    R.string.settings_peak_data_summary,
                    data.mountainCount,
                    data.sourceTimestamp.take(10).replace('-', '/'),
                    SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date(data.installedAtMillis)),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (notice != null) Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.settings_peak_data_description),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.padding(top = 4.dp))
        Button(onClick = onUpdate, enabled = !updating, modifier = Modifier.fillMaxWidth()) {
            if (updating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.padding(start = 8.dp))
                Text(stringResource(R.string.settings_peak_data_fetching))
            } else {
                Text(stringResource(if (data == null) R.string.settings_peak_data_fetch else R.string.settings_peak_data_check))
            }
        }
    }
}

@Composable
private fun CacheSection(info: CacheInfo?, onClear: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_cache_title), style = MaterialTheme.typography.bodyLarge)
        if (info == null) {
            Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(R.string.settings_cache_summary, info.mountainCount, info.tileCount, byteSizeText(info.sizeBytes)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            stringResource(R.string.settings_cache_description),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.padding(top = 4.dp))
        OutlinedButton(
            onClick = { confirming = true },
            enabled = info != null && info.tileCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_cache_clear)) }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_cache_clear_title)) },
            text = {
                Text(
                    stringResource(
                        if (Features.AREA_DOWNLOAD) {
                            R.string.settings_cache_clear_message_keep_areas
                        } else {
                            R.string.settings_cache_clear_message
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onClear()
                }) { Text(stringResource(R.string.settings_cache_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
