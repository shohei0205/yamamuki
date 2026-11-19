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
import io.github.shohei0205.yamamuki.core.OnlineSurface
import io.github.shohei0205.yamamuki.core.SurfaceDataset
import io.github.shohei0205.yamamuki.core.byteSizeText
import io.github.shohei0205.yamamuki.settings.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 地表データの種類の名前。 */
@get:StringRes
val SurfaceDataset.label: Int
    get() = when (this) {
        SurfaceDataset.ESA_WORLDCOVER_Z9 -> R.string.surface_dataset_esa_worldcover_z9
        SurfaceDataset.ESA_WORLDCOVER_Z10 -> R.string.surface_dataset_esa_worldcover_z10
        SurfaceDataset.ESA_WORLDCOVER_Z11 -> R.string.surface_dataset_esa_worldcover_z11
        SurfaceDataset.ESA_WORLDCOVER_Z12 -> R.string.surface_dataset_esa_worldcover_z12
    }

/** 通信して取る地図の種類の名前。 */
@get:StringRes
val OnlineSurface.label: Int
    get() = when (this) {
        OnlineSurface.GSI_PALE -> R.string.online_surface_gsi_pale
        OnlineSurface.GSI_STD -> R.string.online_surface_gsi_std
        OnlineSurface.GSI_HILLSHADE -> R.string.online_surface_gsi_hillshade
        OnlineSurface.GSI_RELIEF -> R.string.online_surface_gsi_relief
        OnlineSurface.GSI_PHOTO -> R.string.online_surface_gsi_photo
    }

/** 地表データの選択画面で選んでいるもの。 */
sealed interface SurfaceChoice {
    /** 表示しない。 */
    data object None : SurfaceChoice

    /** オフラインデータ(yamamuki-data の地表データ)。 */
    data class Offline(val dataset: SurfaceDataset) : SurfaceChoice

    /** オンラインデータ(地理院タイル)。 */
    data class Online(val surface: OnlineSurface) : SurfaceChoice

    companion object {
        /** 設定から、選んでいるものを決める。取得していない地表データは「表示しない」とする(方位盤にも描かない)。 */
        fun of(settings: Settings, state: SurfaceUiState): SurfaceChoice {
            if (!settings.showSurface) return None
            settings.onlineSurface?.let { return Online(it) }
            return if (state.installed(settings.surfaceDataset)) Offline(settings.surfaceDataset) else None
        }
    }
}

/**
 * 地表データの選択画面([SurfaceDataScreen])の中身。方位盤の背景に描くものを、「表示しない」、オフラインデータ
 * (yamamuki-data の地表データ)、オンラインデータ(地理院タイル)から 1 つ選ぶ。
 * オフラインデータは取得済みの種類とカタログで取得できる種類を並べ、選ぶとその種類を表示する(取得していなければ
 * ダウンロードするかを聞く)。取得済みの種類は更新と削除ができ、陰影を重ねるかもここで切り替える。
 * オンラインデータはダウンロードせず、選ぶとすぐ表示する。
 */
@Composable
fun SurfaceDataSection(
    state: SurfaceUiState,
    settings: Settings,
    onChooseNone: () -> Unit,
    onChoose: (SurfaceDataset) -> Unit,
    onChooseOnline: (OnlineSurface) -> Unit,
    onHillshadeChange: (Boolean) -> Unit,
    onUpdate: (SurfaceDataset) -> Unit,
    onDelete: (SurfaceDataset) -> Unit,
    onAnswerOffer: (Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    var deleting by remember { mutableStateOf<SurfaceDataset?>(null) }
    val choice = SurfaceChoice.of(settings, state)
    val enabled = state.updating == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.surface_data_section_description), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        ChoiceRow(stringResource(R.string.surface_none), selected = choice == SurfaceChoice.None, enabled = enabled, onClick = onChooseNone)

        // yamamuki-data の地表データはまだ試験的に配っているので、山データの試験版と同じ印を見出しに付ける。
        SectionHeading(
            stringResource(R.string.surface_offline_section_title),
            stringResource(R.string.surface_offline_section_description),
            badge = stringResource(R.string.peak_data_list_experimental),
        )
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
                selected = choice == SurfaceChoice.Offline(entry.dataset),
                enabled = enabled,
                progress = state.progress.takeIf { state.updating == entry.dataset },
                onChoose = { onChoose(entry.dataset) },
                onUpdate = { onUpdate(entry.dataset) },
                onDelete = { deleting = entry.dataset },
            )
        }
        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.settings_show_hillshade),
            description = stringResource(R.string.settings_show_hillshade_description),
            checked = settings.showHillshade,
            onChange = onHillshadeChange,
        )

        SectionHeading(stringResource(R.string.online_surface_section_title), stringResource(R.string.online_surface_section_description))
        OnlineSurface.entries.forEach { surface ->
            HorizontalDivider()
            ChoiceRow(stringResource(surface.label), selected = choice == SurfaceChoice.Online(surface), enabled = enabled) { onChooseOnline(surface) }
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

/** 節の見出しと説明。[badge] があれば、見出しの右に印を付ける。 */
@Composable
private fun SectionHeading(title: String, description: String, badge: String? = null) {
    Row(
        Modifier.padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (badge != null) Badge(badge)
    }
    Text(description, style = MaterialTheme.typography.bodySmall)
}

/** 名前だけの選択肢の行。選んでいれば「使用中」の印を付ける。 */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Badge(stringResource(R.string.peak_data_list_in_use))
    }
}
