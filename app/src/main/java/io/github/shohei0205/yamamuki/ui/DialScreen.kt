package io.github.shohei0205.yamamuki.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.shohei0205.yamamuki.core.Heading
import io.github.shohei0205.yamamuki.core.HeadingFilter
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.coordinateText
import io.github.shohei0205.yamamuki.core.distanceText
import io.github.shohei0205.yamamuki.core.elevationText
import io.github.shohei0205.yamamuki.sensor.locationUpdates
import io.github.shohei0205.yamamuki.sensor.magneticHeadingUpdates
import io.github.shohei0205.yamamuki.sensor.mslAltitudeM
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** これより小さい方位の変化は画面に反映しない。 */
private const val MIN_HEADING_CHANGE_DEG = 0.1

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** 方位盤の画面。位置情報の権限、現在地、方位センサーをつないで [DialCanvas] に渡す。 */
@Composable
fun DialScreen(viewModel: DialViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            LOCATION_PERMISSIONS.any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> hasPermission = granted.values.any { it } }

    // 初回は「山データを取得してよいか」を先に聞き、答えてから位置情報の許可を求める(ダイアログを重ねない)。
    val consentAsked = state.settings.networkConsentAsked
    LaunchedEffect(consentAsked) {
        if (consentAsked && !hasPermission) permissionLauncher.launch(LOCATION_PERMISSIONS)
    }
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            locationUpdates(context).collect {
                val msl = withContext(Dispatchers.IO) { mslAltitudeM(context, it) }
                viewModel.onLocation(GeoPoint(it.latitude, it.longitude, it.altitude, msl))
            }
        }
    }

    // センサーは磁北基準なので、現在地の偏角(日本ではおよそ西へ 7〜10°)を足して真北基準にする。
    val magneticHeading by remember(context) {
        val filter = HeadingFilter()
        magneticHeadingUpdates(context)
            .map { filter.update(it) }
            // センサーは毎秒 50 回ほど届く。端末を止めているときの細かな揺れで画面全体を描き直さないよう、
            // 画面上でほぼ動かない変化(表示範囲の上端でも数 px)は流さない。
            .distinctUntilChanged { old, new -> abs(Heading.delta(old, new)) < MIN_HEADING_CHANGE_DEG }
    }.collectAsStateWithLifecycle<Double?>(initialValue = null)
    val location = state.location
    val declination = remember(location) {
        location?.let {
            GeomagneticField(
                it.latitude.toFloat(), it.longitude.toFloat(), it.altitudeM.toFloat(), System.currentTimeMillis(),
            ).declination.toDouble()
        } ?: 0.0
    }
    val heading = magneticHeading?.let { Heading.normalize(it + declination) }

    // 選んだ山は ID で持ち、表示中の一覧から引く。歩いて現在地が変わると距離も更新される。
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showObserver by remember { mutableStateOf(false) }
    val selected = state.mountains.firstOrNull { it.mountain.osmId == selectedId }
        ?: state.summit?.takeIf { it.mountain.osmId == selectedId }
    // 取り直しで一覧から消えたら選択も解く。残しておくと、その山が一覧に戻ったときにダイアログが勝手に開く。
    val selectionLost = selectedId != null && selected == null
    LaunchedEffect(selectionLost) {
        if (selectionLost) selectedId = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(DialBeige)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> viewModel.onZoom(zoom) }
            },
    ) {
        DialCanvas(
            headingDeg = heading ?: 0.0,
            mountains = state.mountains,
            rangeKm = state.rangeKm,
            modifier = Modifier.fillMaxSize(),
            onMountainTap = { selectedId = it.mountain.osmId },
            onObserverTap = { showObserver = true },
            summit = state.summit,
            altitudeM = state.location?.mslAltitudeM,
            maxPeaks = state.settings.maxPeaks,
            textScale = state.settings.textScale,
        )
        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )

        if (!hasPermission) {
            PermissionRequest(
                onRequest = { permissionLauncher.launch(LOCATION_PERMISSIONS) },
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            StatusLine(
                message = statusMessage(state, headingAvailable = heading != null),
                // 手動取得モードでは左下の更新ボタンで取り直すので、ここには出さない。
                actionLabel = if (state.offline && state.connected && !state.loading && !state.settings.manualFetch) "再取得" else null,
                onAction = viewModel::retry,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp),
            )
        }

        // 左下: 設定と、手動取得モードなら山データの取得。屋外で押しやすいよう既定(40dp)より大きくする。
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = { showSettings = true }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "設定", Modifier.size(28.dp))
            }
            if (hasPermission && state.settings.manualFetch) {
                FilledTonalIconButton(
                    onClick = viewModel::fetchManually,
                    enabled = state.location != null && !state.loading,
                    modifier = Modifier.size(52.dp),
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "山データを取得", Modifier.size(28.dp))
                    }
                }
            }
        }

        if (showSettings) {
            SettingsScreen(
                settings = state.settings,
                cacheInfo = state.cacheInfo,
                onSettingsChange = viewModel::updateSettings,
                onOpen = viewModel::refreshCacheInfo,
                onClearCache = viewModel::clearCache,
                onClose = { showSettings = false },
            )
        }
    }

    // 屋外で山を見比べている間に画面が消えないようにする(設定で選んだときだけ)。
    val view = LocalView.current
    val keepScreenOn = state.settings.keepScreenOn
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    if (!consentAsked) {
        NetworkConsentDialog(onAnswer = viewModel::answerNetworkConsent)
    }

    // 通信の失敗は、方位を待つ表示などに隠れて気づけないことがないよう、画面中央で知らせる。
    state.fetchErrorMessage?.let { message ->
        FetchErrorDialog(
            message = message,
            onRetry = viewModel::retryAfterFetchError,
            onDismiss = viewModel::dismissFetchError,
        )
    }

    if (selected != null && !showSettings) {
        MountainDetailDialog(selected, onDismiss = { selectedId = null })
    }

    // 現在地を取れる前は出す値がないので開かない。開いている間も歩けば値が更新される。
    if (showObserver && location != null && !showSettings) {
        ObserverDetailDialog(location, onDismiss = { showObserver = false })
    }
}

/** 初回起動時に、山データを自動で取得してよいかを聞く。どちらかを選ぶまで閉じない。 */
@Composable
private fun NetworkConsentDialog(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("山データの取得") },
        text = {
            Text(
                "周辺の山の名前・位置・標高を OpenStreetMap（Overpass API）から取得します。" +
                    "問い合わせには現在地周辺の範囲が含まれます。" +
                    "通信量は 1 回あたり数十 KB 程度で、取得したデータは端末に保存して使い回します。\n\n" +
                    "自動で取得してよいですか？\n" +
                    "「いいえ」なら、画面左下の更新ボタンを押したときだけ通信します。設定はあとから変更できます。",
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("はい") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("いいえ") } },
    )
}

/** 山データの取得に失敗したことを知らせ、再取得できるようにする。 */
@Composable
private fun FetchErrorDialog(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("山データを取得できませんでした") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onRetry) { Text("再取得") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** タップした山の詳細。 */
@Composable
private fun MountainDetailDialog(nearby: NearbyMountain, onDismiss: () -> Unit) {
    val m = nearby.mountain
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text(m.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("標高", m.elevationText())
                DetailRow("緯度経度", m.coordinateText())
                DetailRow("現在地からの距離", distanceText(nearby.distanceKm))
            }
        },
    )
}

/** 双眼鏡(現在地)をタップしたときの詳細。距離は常に 0 なので出さない。 */
@Composable
private fun ObserverDetailDialog(location: GeoPoint, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text("現在地") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("緯度経度", coordinateText(location.latitude, location.longitude))
                DetailRow("標高", elevationText(location.mslAltitudeM))
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun statusMessage(state: DialUiState, headingAvailable: Boolean): String? = when {
    state.location == null -> "現在地を取得しています…"
    !headingAvailable -> "方位センサーの値を待っています…"
    state.loading -> "山データを取得中…"
    !state.connected && state.incomplete -> "圏外のため、この付近の山データがありません"
    !state.connected -> "圏外: 保存済みのデータで表示中"
    state.offline && state.incomplete -> "通信できず、この付近の山データがありません"
    state.offline -> "オフライン: 保存済みのデータで表示中"
    state.settings.manualFetch && state.incomplete -> "この付近の山データがありません。左下の更新ボタンで取得できます"
    else -> null
}

@Composable
private fun StatusLine(
    message: String?,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    Row(
        modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall)
        if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "周辺の山を表示するには、位置情報の許可が必要です。\n" +
                "許可の画面が出ないときは、端末の設定アプリから許可してください。",
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text("許可する") }
    }
}
