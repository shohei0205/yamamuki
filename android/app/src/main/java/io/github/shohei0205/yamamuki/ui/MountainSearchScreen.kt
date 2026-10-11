package io.github.shohei0205.yamamuki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.distanceText
import io.github.shohei0205.yamamuki.core.seenFrom

/**
 * 山の検索画面。方位盤の右下の検索ボタンで開く。入力した文字を含む山を現在地から近い順に並べ、
 * 選ぶとその山頂を中心にした方位盤(検索位置モード)にする。
 */
@Composable
fun MountainSearchScreen(
    search: SearchUiState,
    /** 結果に距離と方角を添える基準(実際の現在地)。分からなければ添えない。 */
    from: GeoPoint?,
    onQueryChange: (String) -> Unit,
    onSelect: (Mountain) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    // 入力中の文字は画面で持つ(日本語入力の変換中の文字を崩さないため)。開くたびに空から始める。
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChange(it)
                },
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            when {
                search.loading -> SearchNote(stringResource(R.string.search_loading))
                search.noData -> SearchNote(stringResource(R.string.search_no_data))
                query.isNotBlank() && search.results.isEmpty() -> SearchNote(stringResource(R.string.search_no_results))
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(search.results, key = { it.osmId }) { mountain ->
                        SearchResultRow(mountain, from, onClick = { onSelect(mountain) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
}

/** 検索結果の 1 行。山名とよみがな、標高と現在地からの方角・距離。 */
@Composable
private fun SearchResultRow(mountain: Mountain, from: GeoPoint?, onClick: () -> Unit) {
    val texts = rememberDialTexts()
    val seen = from?.let { mountain.seenFrom(it.latitude, it.longitude) }
    val elevation = elevationLabel(mountain.elevationM)
    val detail = seen?.let {
        stringResource(
            R.string.detail_group_item,
            elevation,
            stringResource(R.string.search_result_direction, texts.direction(it.bearingDeg), distanceText(it.distanceKm)),
        )
    } ?: elevation
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(mountain.name, style = MaterialTheme.typography.bodyLarge)
            mountain.nameReading?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(detail, style = MaterialTheme.typography.labelMedium)
    }
}
