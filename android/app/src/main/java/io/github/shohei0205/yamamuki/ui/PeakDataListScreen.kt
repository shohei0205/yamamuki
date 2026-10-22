package io.github.shohei0205.yamamuki.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.core.PeakDataset
import io.github.shohei0205.yamamuki.core.byteSizeText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 山データの種類の名前。manifest の name は比べる名前として使いにくいので、アプリが知っている種類ごとに持つ。 */
@get:StringRes
val PeakDataset.label: Int
    get() = when (this) {
        PeakDataset.OSM_PEAKS -> R.string.peak_dataset_osm_peaks
        PeakDataset.GSI_PEAKS -> R.string.peak_dataset_gsi_peaks
    }

/** 方位盤の右下に出す、短い著作者表示。 */
@get:StringRes
val PeakDataset.attribution: Int
    get() = when (this) {
        PeakDataset.OSM_PEAKS -> R.string.osm_attribution
        PeakDataset.GSI_PEAKS -> R.string.gsi_attribution
    }

/** 出典の画面で、manifest が読めないときに出す著作者表示とライセンス。 */
@get:StringRes
val PeakDataset.creditsAttribution: Int
    get() = when (this) {
        PeakDataset.OSM_PEAKS -> R.string.credits_osm_attribution
        PeakDataset.GSI_PEAKS -> R.string.credits_gsi_attribution
    }

/**
 * 山データの選択画面。設定画面の「山データ」の、選んでいる山データの名前の行から開く。取得済みの種類と、カタログで取得できる種類を並べ、
 * 選ぶと切り替える(取得していなければダウンロードするかを聞く)。取得済みの種類は削除もできる。
 */
@Composable
fun PeakDataListScreen(
    list: PeakDataListUiState,
    selected: PeakDataset,
    /** 山データを取得している。終わるまで選び直しと削除をできなくする。 */
    updating: Boolean,
    onChoose: (PeakDataset) -> Unit,
    onDelete: (PeakDataset) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var deleting by remember { mutableStateOf<PeakDataset?>(null) }

    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                Text(stringResource(R.string.peak_data_list_title), style = MaterialTheme.typography.headlineSmall)
            }
            Text(stringResource(R.string.peak_data_list_description), style = MaterialTheme.typography.bodySmall)
            if (list.offline) {
                Text(stringResource(R.string.peak_data_list_offline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (list.checking || updating) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(if (updating) R.string.settings_peak_data_fetching else R.string.peak_data_list_checking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            list.entries.forEach { entry ->
                HorizontalDivider()
                PeakDataRow(
                    entry = entry,
                    selected = entry.dataset == selected,
                    enabled = !updating,
                    onChoose = { onChoose(entry.dataset) },
                    onDelete = { deleting = entry.dataset },
                )
            }
        }
    }

    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.peak_data_delete_title, stringResource(d.label))) },
            text = {
                Text(stringResource(if (d == selected) R.string.peak_data_delete_message_in_use else R.string.peak_data_delete_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(d)
                }) { Text(stringResource(R.string.peak_data_list_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** 種類ごとの行。名前と印(試験版・使用中・更新あり)、取得済みなら件数と取得日、まだならダウンロードする大きさを出す。 */
@Composable
private fun PeakDataRow(entry: PeakDataEntry, selected: Boolean, enabled: Boolean, onChoose: () -> Unit, onDelete: () -> Unit) {
    val installed = entry.installed
    val available = entry.available
    // 取得しておらず、カタログでも確かめられなかった種類は選べない。
    val choosable = enabled && (installed != null || available != null)
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = choosable, role = Role.RadioButton, onClick = onChoose)
            .alpha(if (choosable || selected) 1f else DISABLED_ALPHA)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = choosable || selected)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(entry.dataset.label), style = MaterialTheme.typography.bodyLarge)
                if (entry.experimental) Badge(stringResource(R.string.peak_data_list_experimental))
                if (selected && installed != null) Badge(stringResource(R.string.peak_data_list_in_use))
                if (entry.updateFound) Badge(stringResource(R.string.peak_data_list_update_found))
            }
            val summary = when {
                installed != null -> {
                    val installedDate = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date(installed.installedAtMillis))
                    installed.releaseDate?.let { stringResource(R.string.settings_peak_data_summary, installed.mountainCount, it, installedDate) }
                        ?: stringResource(R.string.settings_peak_data_summary_no_release, installed.mountainCount, installedDate)
                }
                available != null -> {
                    val size = byteSizeText(available.sizeBytes)
                    available.releaseDate?.let { stringResource(R.string.peak_data_list_available, available.mountainCount, it, size) }
                        ?: stringResource(R.string.peak_data_list_available_no_release, available.mountainCount, size)
                }
                else -> stringResource(R.string.settings_peak_data_none)
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
        if (installed != null) {
            TextButton(onClick = onDelete, enabled = enabled) { Text(stringResource(R.string.peak_data_list_delete)) }
        }
    }
}

/** 名前の横に出す小さな印。 */
@Composable
private fun Badge(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** 押せない行の不透明度。Material の無効な部品と同じ値。 */
private const val DISABLED_ALPHA = 0.38f
