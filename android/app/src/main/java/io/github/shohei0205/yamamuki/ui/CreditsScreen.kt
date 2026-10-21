package io.github.shohei0205.yamamuki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.core.InstalledPeakData
import io.github.shohei0205.yamamuki.core.LinkText
import io.github.shohei0205.yamamuki.core.PeakDataCredits

/** OpenStreetMap の著作権とライセンスの説明ページ。山データの出典が読めないときに添える。 */
private const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

/** manifest を読み終えるまでの状態。 */
private sealed interface CreditsLoad {
    data object Loading : CreditsLoad
    data class Loaded(val credits: PeakDataCredits?) : CreditsLoad
}

/**
 * 山データの出典とライセンスの画面。設定画面の「データの出典とライセンス」の行から開く。
 * 取り込み済みの山データの manifest([loadCredits])から、OSM の表示と、補足に使った資料をすべて出す
 * (manifest 版 6 は、元データも含めたすべての出典を「出典」として出す)。
 * 山データが無いときや出典が読めないときも、OpenStreetMap の著作者表示は出す。
 */
@Composable
fun CreditsScreen(
    peakData: InstalledPeakData?,
    loadCredits: suspend () -> PeakDataCredits?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    // 山データを取り直したら読み直す。
    val load by produceState<CreditsLoad>(CreditsLoad.Loading, peakData) {
        value = CreditsLoad.Loaded(if (peakData == null) null else loadCredits())
    }
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                Text(stringResource(R.string.settings_credits), style = MaterialTheme.typography.headlineSmall)
            }
            val credits = (load as? CreditsLoad.Loaded)?.credits
            SectionTitle(stringResource(R.string.credits_section_data))
            if (credits != null) {
                CreditsCard(
                    title = null,
                    items = listOf(
                        R.string.credits_release to credits.releaseDate,
                        R.string.credits_license to credits.license,
                        R.string.credits_attribution to credits.attribution,
                        R.string.credits_source_url to credits.sourceUrl,
                    ),
                )
                // manifest 版 6 は元データと補足の区別が無いので、すべての出典を「出典」の見出しの下に並べる。
                SectionTitle(
                    stringResource(if (credits.listsAllSources) R.string.credits_section_sources else R.string.credits_section_supplements),
                )
                if (credits.supplements.isEmpty()) {
                    Note(stringResource(R.string.credits_no_supplements))
                }
                credits.supplements.forEach { s ->
                    CreditsCard(
                        title = s.id,
                        items = listOf(
                            R.string.credits_source to s.source,
                            R.string.credits_license to s.license,
                            R.string.credits_attribution to s.attribution,
                            R.string.credits_changes to s.changes,
                        ),
                    )
                }
            } else if (load is CreditsLoad.Loaded) {
                // 山データが無い・出典が残っていないときも、アプリが持つ OSM の表示は出す。
                CreditsCard(
                    title = null,
                    items = listOf(R.string.credits_attribution to "${stringResource(R.string.credits_osm_attribution)} $OSM_COPYRIGHT_URL"),
                )
                Note(stringResource(if (peakData == null) R.string.credits_not_installed else R.string.credits_missing))
            }
        }
    }
}

/** 見出しと、「ライセンス」などの項目を並べた枠。値の無い項目は出さない。 */
@Composable
private fun CreditsCard(title: String?, items: List<Pair<Int, String?>>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            items.forEach { (label, value) ->
                if (value == null) return@forEach
                Column {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(linkified(value), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/** 文中の URL を、タップするとブラウザで開くリンクにする。 */
@Composable
private fun linkified(text: String): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        LinkText.split(text).forEach { part ->
            if (part.isUrl) withLink(LinkAnnotation.Url(part.text, style)) { append(part.text) } else append(part.text)
        }
    }
}
