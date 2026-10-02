package io.github.shohei0205.yamamuki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import io.github.shohei0205.yamamuki.Features
import io.github.shohei0205.yamamuki.core.byteSizeText
import io.github.shohei0205.yamamuki.data.CacheInfo
import io.github.shohei0205.yamamuki.settings.Settings
import java.util.Locale
import kotlin.math.roundToInt

/** 最低標高スライダーの上限と刻み。 */
private const val MAX_MIN_ELEVATION_M = 3000
private const val MIN_ELEVATION_STEP_M = 100

private const val MAX_PEAKS_STEP = 10

/** 設定画面。方位盤の左下の設定ボタンで開く。 */
@Composable
fun SettingsScreen(
    settings: Settings,
    cacheInfo: CacheInfo?,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
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
                Text("設定", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("閉じる") }
            }

            SectionTitle("表示する山")
            StepSlider(
                value = settings.minElevationM,
                range = 0..MAX_MIN_ELEVATION_M,
                step = MIN_ELEVATION_STEP_M,
                label = ::minElevationLabel,
                description = "0 m ですべての山を表示します。絞り込み中は標高不明の山を表示しません。",
                onChange = { m -> onSettingsChange { it.copy(minElevationM = m) } },
            )
            StepSlider(
                value = settings.maxPeaks,
                range = Settings.MAX_PEAKS_RANGE,
                step = MAX_PEAKS_STEP,
                label = { "一度に表示する山 最大 $it 件" },
                description = "多いと画面が混み合い、少ないと高い山だけになります。重なる山は標高の低いほうを省きます。",
                onChange = { n -> onSettingsChange { it.copy(maxPeaks = n) } },
            )

            HorizontalDivider()
            SectionTitle("表示")
            Choice(
                title = "文字の大きさ",
                options = Settings.TEXT_SCALES,
                selected = settings.textScale,
                label = { textScaleLabel(it) },
                onSelect = { v -> onSettingsChange { it.copy(textScale = v) } },
            )
            Choice(
                title = "起動時の表示範囲（km）",
                options = Settings.INITIAL_RANGES_KM,
                selected = settings.initialRangeKm,
                // 6 つ並ぶと「10km」が収まらないので、単位は見出しに出す。
                label = { "$it" },
                description = "現在地から画面上端までの距離。起動後はピンチで変えられます。",
                onSelect = { v -> onSettingsChange { it.copy(initialRangeKm = v) } },
            )

            HorizontalDivider()
            SectionTitle("画面")
            SwitchRow(
                title = "画面を常に点灯",
                description = "方位盤を表示している間は画面を消しません。電池の減りが早くなります。",
                checked = settings.keepScreenOn,
                onChange = { v -> onSettingsChange { it.copy(keepScreenOn = v) } },
            )

            HorizontalDivider()
            SectionTitle("通信とキャッシュ")
            Choice(
                title = "取得したデータを使う期間",
                options = Settings.CACHE_MAX_AGE_DAYS,
                selected = settings.cacheMaxAgeDays,
                // 5 つ並ぶと「180日」が収まらないので、長い期間は「半年」「1年」と書く。
                label = {
                    when (it) {
                        180 -> "半年"
                        365 -> "1年"
                        else -> "${it}日"
                    }
                },
                description = "この期間を過ぎた地域は取り直します。山データはめったに変わらないので、長くすると通信が減ります。",
                onSelect = { v -> onSettingsChange { it.copy(cacheMaxAgeDays = v) } },
            )
            CacheSection(cacheInfo, onClearCache)

            HorizontalDivider()
            SectionTitle("このアプリについて")
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
        Text("バージョン $version", style = MaterialTheme.typography.bodyLarge)
        Text("山データ © OpenStreetMap contributors (ODbL)", style = MaterialTheme.typography.bodySmall)
    }
}

private fun minElevationLabel(m: Int): String =
    if (m == 0) "すべての山を表示" else String.format(Locale.US, "標高 %,d m 以上の山だけ表示", m)

private fun textScaleLabel(scale: Float): String = when (scale) {
    0.85f -> "小"
    1.0f -> "標準"
    1.2f -> "大"
    1.4f -> "特大"
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
    label: (Int) -> String,
    description: String,
    onChange: (Int) -> Unit,
) {
    var dragging by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val snapped = (dragging / step).roundToInt() * step
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label(snapped), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onChange(snapped) },
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

@Composable
private fun <T> Choice(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
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

@Composable
private fun CacheSection(info: CacheInfo?, onClear: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("保存しているデータ", style = MaterialTheme.typography.bodyLarge)
        if (info == null) {
            Text("読み込み中…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                String.format(Locale.US, "山 %,d 件（取得済みの区画 %d 個）・容量 %s", info.mountainCount, info.tileCount, byteSizeText(info.sizeBytes)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "消去すると現在地の周辺を取り直すので、通信が発生します。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.padding(top = 4.dp))
        OutlinedButton(
            onClick = { confirming = true },
            enabled = info != null && info.tileCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("キャッシュを消去") }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("キャッシュを消去しますか？") },
            text = {
                Text(
                    if (Features.AREA_DOWNLOAD) {
                        "保存している山データを消去し、現在地の周辺を取り直します。事前ダウンロードした地域は残ります。"
                    } else {
                        "保存している山データをすべて消去し、現在地の周辺を取り直します。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onClear()
                }) { Text("消去") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("キャンセル") } },
        )
    }
}
