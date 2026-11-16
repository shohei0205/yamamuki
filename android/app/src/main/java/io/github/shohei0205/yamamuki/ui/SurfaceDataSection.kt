// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import io.github.shohei0205.yamamuki.core.SurfaceDataset
import io.github.shohei0205.yamamuki.core.byteSizeText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 地表データの種類の名前。 */
@get:StringRes
val SurfaceDataset.label: Int
    get() = when (this) {
        SurfaceDataset.ESA_WORLDCOVER_Z9 -> R.string.surface_dataset_esa_worldcover_z9
    }

/**
 * 山データの選択画面の「地表データ」の節。取得済みの種類と、カタログで取得できる種類を並べる。
 * 選ぶとその種類を使い(取得していなければダウンロードするかを聞く)、取得済みの種類は更新と削除ができる。
 * 方位盤に描くかどうかは、設定の「地表を表示」で切り替える。
 */
@Composable
fun SurfaceDataSection(
    state: SurfaceUiState,
    selected: SurfaceDataset,
    onChoose: (SurfaceDataset) -> Unit,
    onUpdate: (SurfaceDataset) -> Unit,
    onDelete: (SurfaceDataset) -> Unit,
    onAnswerOffer: (Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    var deleting by remember { mutableStateOf<SurfaceDataset?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.surface_data_section_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.surface_data_section_description), style = MaterialTheme.typography.bodySmall)
        if (state.checking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.surface_data_checking), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (state.entries.isEmpty()) {
            Text(stringResource(R.string.surface_data_none), style = MaterialTheme.typography.bodyMedium)
        }
        state.entries.forEach { entry ->
            HorizontalDivider()
            SurfaceDataRow(
                entry = entry,
                selected = entry.dataset == selected,
                enabled = state.updating == null,
                progress = state.progress.takeIf { state.updating == entry.dataset },
                onChoose = { onChoose(entry.dataset) },
                onUpdate = { onUpdate(entry.dataset) },
                onDelete = { deleting = entry.dataset },
            )
        }
    }

    state.offer?.let { offer ->
        val size = byteSizeText(offer.manifest.sizeBytes)
        val detail = offer.manifest.releaseDate?.let { stringResource(R.string.peak_data_offer_detail, it, size) }
            ?: stringResource(R.string.peak_data_offer_size, size)
        AlertDialog(
            onDismissRequest = { onAnswerOffer(false) },
            title = { Text(stringResource(R.string.surface_data_offer_title)) },
            text = { Text(stringResource(R.string.surface_data_offer_message, stringResource(offer.dataset.label), detail)) },
            confirmButton = { TextButton(onClick = { onAnswerOffer(true) }) { Text(stringResource(R.string.peak_data_offer_download)) } },
            dismissButton = { TextButton(onClick = { onAnswerOffer(false) }) { Text(stringResource(R.string.peak_data_offer_later)) } },
        )
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.surface_data_error_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.common_close)) } },
        )
    }

    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.surface_data_delete_title, stringResource(d.label))) },
            text = { Text(stringResource(R.string.surface_data_delete_message)) },
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

/** 種類ごとの行。名前と印(試験版・使用中・更新あり)、リリース日・取得日・大きさ、ダウンロード中は進み具合を出す。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurfaceDataRow(
    entry: SurfaceDataEntry,
    selected: Boolean,
    enabled: Boolean,
    progress: Float?,
    onChoose: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    val installed = entry.installed
    val available = entry.available
    val inUse = selected && installed != null
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = inUse, enabled = enabled, role = Role.RadioButton, onClick = onChoose)
            .alpha(if (enabled || progress != null) 1f else DISABLED_ALPHA)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = inUse, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(entry.dataset.label), style = MaterialTheme.typography.bodyLarge)
                val badges = listOfNotNull(
                    R.string.peak_data_list_experimental.takeIf { entry.experimental },
                    R.string.peak_data_list_in_use.takeIf { inUse },
                    R.string.peak_data_list_update_found.takeIf { entry.updateFound },
                )
                if (badges.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        badges.forEach { Badge(stringResource(it)) }
                    }
                }
            }
            val summary = when {
                installed != null -> {
                    val installedDate = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date(installed.installedAtMillis))
                    val size = byteSizeText(installed.sizeBytes)
                    installed.releaseDate?.let { stringResource(R.string.surface_data_summary_installed, it, installedDate, size) }
                        ?: stringResource(R.string.surface_data_summary_installed_no_release, installedDate, size)
                }
                available != null -> {
                    val size = byteSizeText(available.sizeBytes)
                    available.releaseDate?.let { stringResource(R.string.surface_data_summary_available, it, size) }
                        ?: stringResource(R.string.peak_data_offer_size, size)
                }
                else -> ""
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            if (progress != null) {
                Text(stringResource(R.string.surface_data_fetching, (progress * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (installed != null && progress == null) {
            Column(horizontalAlignment = Alignment.End) {
                if (entry.updateFound) TextButton(onClick = onUpdate, enabled = enabled) { Text(stringResource(R.string.surface_data_update)) }
                TextButton(onClick = onDelete, enabled = enabled) { Text(stringResource(R.string.peak_data_list_delete)) }
            }
        }
    }
}

/** 押せない行の不透明度。Material の無効な部品と同じ値。 */
private const val DISABLED_ALPHA = 0.38f
