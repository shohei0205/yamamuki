// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.core.SurfaceDataset

/**
 * 地表データの選択画面。山データの選択画面([PeakDataListScreen])とは別に、設定画面の「地表データ」の行から開く。
 * 取得済みの種類と、カタログで取得できる種類を並べ、選ぶとその種類を使う(取得していなければダウンロードするかを聞く)。
 */
@Composable
fun SurfaceDataScreen(
    surface: SurfaceDataViewModel,
    selected: SurfaceDataset,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    // 開くたびに、取得できる版を確かめ直す。
    LaunchedEffect(surface) { surface.open() }
    val state by surface.state.collectAsStateWithLifecycle()

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
                Text(stringResource(R.string.surface_data_section_title), style = MaterialTheme.typography.headlineSmall)
            }
            SurfaceDataSection(
                state = state,
                selected = selected,
                onChoose = surface::choose,
                onUpdate = surface::offerDownload,
                onDelete = surface::delete,
                onAnswerOffer = surface::answerOffer,
                onDismissError = surface::dismissError,
            )
        }
    }
}
