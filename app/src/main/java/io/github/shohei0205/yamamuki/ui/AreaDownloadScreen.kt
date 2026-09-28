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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.shohei0205.yamamuki.core.Prefecture
import io.github.shohei0205.yamamuki.data.SavedArea
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
                Text("事前ダウンロード", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("閉じる") }
            }
            Text(
                "目的地周辺の山データを、電波の届く場所で事前に端末へ保存することができます。\n" +
                    "事前に保存した山データは、キャッシュを消去しても残ります。",
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
                    "圏外のため、今はダウンロードできません。電波の届く場所で開いてください。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.savedAreas.isNotEmpty()) {
                SectionTitle("保存済みの地域")
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

            SectionTitle("都道府県を選択")
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
            title = { Text(if (refresh) "${prefecture.name}を取り直しますか？" else "${prefecture.name}をダウンロードしますか？") },
            text = {
                Text(
                    "${prefecture.tiles.size} 区画の山データを OpenStreetMap（Overpass API）から取得します。" +
                        "サーバーの混み具合によっては数分かかります。途中で中断でき、この画面を閉じてもダウンロードは継続します。" +
                        "アプリを終了すると一時停止しますが、次に開いた時に再開できます。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onStart(prefecture, refresh)
                }) { Text(if (refresh) "取り直す" else "ダウンロード") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("キャンセル") } },
        )
    }

    deleting?.let { area ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("${area.prefecture.name}を削除しますか？") },
            text = { Text("保存した山データを端末から消します。ほかの保存済みの地域と重なる部分は残ります。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(area)
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun RunningCard(running: RunningDownload, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${running.prefecture.name}をダウンロード中（${running.progress.doneTiles} / ${running.progress.totalTiles} 区画）",
                style = MaterialTheme.typography.titleMedium,
            )
            if (running.progress.retry > 0) {
                Text(
                    "通信に失敗したため、取り直しています（${running.progress.retry} 回目）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LinearProgressIndicator(progress = { running.progress.fraction }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("中断") }
        }
    }
}

@Composable
private fun NoticeCard(notice: DownloadNotice, canResume: Boolean, onResume: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(notice.message, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (notice.resume != null) {
                    TextButton(onClick = onResume, enabled = canResume) { Text("続きから再開") }
                }
                TextButton(onClick = onDismiss) { Text("閉じる") }
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
                String.format(Locale.US, "%s 取得・山 %,d 件", dateText(area.downloadedAtMillis), area.mountainCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onRefresh, enabled = refreshEnabled) { Text("更新") }
        TextButton(onClick = onDelete, enabled = deleteEnabled) { Text("削除") }
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
            if (saved) "保存済み" else "${prefecture.tiles.size} 区画",
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
