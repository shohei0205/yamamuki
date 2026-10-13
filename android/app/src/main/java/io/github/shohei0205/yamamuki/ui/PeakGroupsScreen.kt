package io.github.shohei0205.yamamuki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.core.TagGroup

/** 山のグループを選ぶ画面。設定画面の「山のグループ」の行から開く。 */
@Composable
fun PeakGroupsScreen(
    groups: List<TagGroup>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
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
                Text(stringResource(R.string.settings_peak_groups), style = MaterialTheme.typography.headlineSmall)
            }
            PeakGroupsList(groups, selected, onChange)
        }
    }
}

/**
 * 山のグループのチェック欄と、すべてのチェックを外すボタン。チェックが無いときは、すべての山を表示する。
 * ボタンは、グループが増えても下までスクロールせずに押せるよう、一覧の上に置く。
 * 方位盤からシートで開く入口を足すときも、この部品をそのまま使う。
 */
@Composable
fun PeakGroupsList(groups: List<TagGroup>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_peak_groups_description), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { onChange(emptySet()) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.padding(vertical = 4.dp),
        ) { Text(stringResource(R.string.settings_peak_groups_clear)) }
        groups.forEach { group ->
            val checked = group.name in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        role = Role.Checkbox,
                        onValueChange = { on -> onChange(if (on) selected + group.name else selected - group.name) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(group.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                Text(stringResource(R.string.settings_peak_group_count, group.count), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 設定画面の行に出す、今の絞り込み。「すべての山」「日本百名山」「九州百名山 ほか 1」。[groups] は名前順。 */
@Composable
fun peakGroupsSummary(groups: List<String>): String = when (groups.size) {
    0 -> stringResource(R.string.settings_peak_groups_all)
    1 -> groups[0]
    else -> stringResource(R.string.settings_peak_groups_more, groups[0], groups.size - 1)
}
