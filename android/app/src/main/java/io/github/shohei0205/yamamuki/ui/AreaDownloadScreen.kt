package io.github.shohei0205.yamamuki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.core.DownloadStatus
import io.github.shohei0205.yamamuki.core.Prefecture
import io.github.shohei0205.yamamuki.data.SavedArea
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 山データの事前ダウンロード画面。方位盤の左下のダウンロードボタンで開く。 */
@Composable
fun AreaDownloadScreen(
    state: AreaDownloadUiState,
    onStart: (Prefecture, Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismissNotice: () -> Unit,
    onDelete: (SavedArea) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    // ダウンロードを確かめる都道府県と、取り直しか。
    var confirming by remember { mutableStateOf<Pair<Prefecture, Boolean>?>(null) }
    var deleting by remember { mutableStateOf<SavedArea?>(null) }
    val busy = state.running != null
    val canStart = !busy && state.connected

    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.area_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
            }
            Text(
                stringResource(R.string.area_description),
                style = MaterialTheme.typography.bodyMedium,
            )

            state.running?.let { RunningCard(it, onCancel) }
            state.notice?.let { notice ->
                NoticeCard(
                    notice = notice,
                    canResume = canStart,
                    onResume = { notice.resume?.let { onStart(it, notice.resumeRefresh) } },
                    onDismiss = onDismissNotice,
                )
            }
            if (!state.connected) {
                Text(
                    stringResource(R.string.area_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.savedAreas.isNotEmpty()) {
                SectionTitle(stringResource(R.string.area_section_saved))
                state.savedAreas.forEach { area ->
                    SavedAreaRow(
                        area = area,
                        refreshEnabled = canStart,
                        // 削除は通信しないので、圏外でも(山で容量を空けたいときなど)できるようにする。
                        deleteEnabled = !busy,
                        onRefresh = { confirming = area.prefecture to true },
                        onDelete = { deleting = area },
                    )
                }
                HorizontalDivider()
            }

            SectionTitle(stringResource(R.string.area_section_choose))
            val saved = state.savedAreas.map { it.prefecture.code }.toSet()
            Prefecture.ALL.groupBy { it.region }.forEach { (region, prefectures) ->
                Text(region, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                prefectures.forEach { p ->
                    PrefectureRow(
                        prefecture = p,
                        saved = p.code in saved,
                        enabled = canStart,
                        onClick = { confirming = p to false },
                    )
                }
            }
        }
    }

    confirming?.let { (prefecture, refresh) ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(
                    stringResource(
                        if (refresh) R.string.area_confirm_refresh_title else R.string.area_confirm_download_title,
                        prefecture.name,
                    ),
                )
            },
            text = { Text(stringResource(R.string.area_confirm_message, prefecture.tiles.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onStart(prefecture, refresh)
                }) { Text(stringResource(if (refresh) R.string.area_refresh else R.string.area_download)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    deleting?.let { area ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.area_delete_title, area.prefecture.name)) },
            text = { Text(stringResource(R.string.area_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(area)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun RunningCard(running: RunningDownload, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    R.string.area_running,
                    running.prefecture.name,
                    running.progress.doneTiles,
                    running.progress.totalTiles,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            // 区画数が増えない間も止まっていないことが分かるよう、待っている秒数を 1 秒ごとに出す。
            val now by produceState(SystemClock.elapsedRealtime(), running) {
                while (true) {
                    value = SystemClock.elapsedRealtime()
                    delay(1_000)
                }
            }
            Text(
                statusText(running.progress.status(now - running.sinceMillis)),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(progress = { running.progress.fraction }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.area_cancel)) }
        }
    }
}

/** 今の区画が何を待っているかの文言。「サーバーの応答を待っています（35 秒）」など。 */
@Composable
private fun statusText(status: DownloadStatus): String = when (status) {
    is DownloadStatus.RetryingIn -> stringResource(R.string.area_status_retrying, status.seconds, status.retry)
    is DownloadStatus.Waiting ->
        if (status.retry > 0) {
            stringResource(R.string.area_status_waiting_retry, status.seconds, status.retry)
        } else {
            stringResource(R.string.area_status_waiting, status.seconds)
        }
}

@Composable
private fun NoticeCard(notice: DownloadNotice, canResume: Boolean, onResume: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(notice.message, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (notice.resume != null) {
                    TextButton(onClick = onResume, enabled = canResume) { Text(stringResource(R.string.area_resume)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            }
        }
    }
}

@Composable
private fun SavedAreaRow(
    area: SavedArea,
    refreshEnabled: Boolean,
    deleteEnabled: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(area.prefecture.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.area_saved_summary, dateText(area.downloadedAtMillis), area.mountainCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onRefresh, enabled = refreshEnabled) { Text(stringResource(R.string.area_update)) }
        TextButton(onClick = onDelete, enabled = deleteEnabled) { Text(stringResource(R.string.common_delete)) }
    }
}

@Composable
private fun PrefectureRow(prefecture: Prefecture, saved: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !saved, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(prefecture.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            if (saved) stringResource(R.string.area_saved) else stringResource(R.string.area_tiles, prefecture.tiles.size),
            style = MaterialTheme.typography.bodySmall,
            color = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd")

private fun dateText(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE_FORMAT)
