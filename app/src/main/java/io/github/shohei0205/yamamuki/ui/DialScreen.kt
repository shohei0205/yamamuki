package io.github.shohei0205.yamamuki.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.shohei0205.yamamuki.Features
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.core.DialGeometry
import io.github.shohei0205.yamamuki.core.Heading
import io.github.shohei0205.yamamuki.core.PlanOffset
import io.github.shohei0205.yamamuki.core.HeadingFilter
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.coordinateText
import io.github.shohei0205.yamamuki.core.distanceText
import io.github.shohei0205.yamamuki.core.elevationText
import io.github.shohei0205.yamamuki.sensor.headingAccuracyLowUpdates
import io.github.shohei0205.yamamuki.sensor.locationUpdates
import io.github.shohei0205.yamamuki.sensor.magneticHeadingUpdates
import io.github.shohei0205.yamamuki.sensor.mslAltitudeM
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 方位センサーの精度が低いときに、上部の情報ラベルの下に出す案内。 */
private const val HEADING_ACCURACY_LOW_MESSAGE = "方位がずれているかもしれません。端末を 8 の字に動かしてください"

/** これより小さい方位の変化は画面に反映しない。 */
private const val MIN_HEADING_CHANGE_DEG = 0.1

/** ヘディングアップと手動位置モードを切り替えるときの、方位目盛り・コンパス・視野の扇の動きの長さ。 */
private const val MODE_ANIMATION_MS = 350

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** 方位盤の画面。位置情報の権限、現在地、方位センサーをつないで [DialCanvas] に渡す。 */
@Composable
fun DialScreen(
    viewModel: DialViewModel = viewModel(),
    downloadViewModel: AreaDownloadViewModel = viewModel(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var canvasHeight by remember { mutableStateOf(0.0) }
    var canvasWidth by remember { mutableStateOf(0.0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download by downloadViewModel.state.collectAsStateWithLifecycle()

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
    // 方位センサーの精度が低いと、方位が数十度ずれたまま別の山の名前を出してしまうので、上部で知らせる。
    val headingAccuracyLow by remember(context) {
        headingAccuracyLowUpdates(context).distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val location = state.gpsLocation
    val declination = remember(location) {
        location?.let {
            GeomagneticField(
                it.latitude.toFloat(), it.longitude.toFloat(), it.altitudeM.toFloat(), System.currentTimeMillis(),
            ).declination.toDouble()
        } ?: 0.0
    }
    val compassHeading = magneticHeading?.let { Heading.normalize(it + declination) }
    val heading = state.lockedHeading ?: compassHeading
    val currentHeading by rememberUpdatedState(heading ?: 0.0)
    val currentCompassHeading by rememberUpdatedState(compassHeading ?: heading ?: 0.0)

    // 手動位置モードの表示(上部の目盛りを隠し、向きの表示とコンパスを出す)。現在地へ戻り始めたらすぐ戻す。
    val manualChrome = state.exploring && !state.returning
    val tapeHidden by animateFloatAsState(if (manualChrome) 1f else 0f, tween(MODE_ANIMATION_MS), label = "tapeHidden")
    // 視野の扇は、現在地に戻り終えてから出す。
    val viewFanAlpha by animateFloatAsState(if (state.exploring) 0f else 1f, tween(MODE_ANIMATION_MS), label = "viewFan")

    // 選んだ山は ID で持ち、表示中の一覧から引く。歩いて現在地が変わると距離も更新される。
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDownload by rememberSaveable { mutableStateOf(false) }
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
            .onSizeChanged {
                canvasHeight = it.height / density.density.toDouble()
                canvasWidth = it.width / density.density.toDouble()
            }
            .pointerInput(showSettings) {
                if (showSettings) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var dragging = false
                    var pendingPan = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val count = event.changes.count { it.pressed }
                        if (count == 1 && !multiTouch) {
                            val pan = event.calculatePan()
                            pendingPan += pan
                            val distance = pendingPan.getDistance()
                            val started = !dragging && distance > viewConfiguration.touchSlop
                            if (started) dragging = true
                            if (dragging) {
                                val delta = if (started) pendingPan else pan
                                viewModel.onPan(delta.x, delta.y, size.height - DialGeometry.CHART_INSET_DP.dp.toPx(), currentHeading)
                                event.changes.forEach { it.consume() }
                            }
                        } else if (count >= 2) {
                            multiTouch = true
                            val zoom = event.calculateZoom()
                            if (count == 2 && event.changes.count { it.pressed && it.previousPressed } == 2) {
                                val origin = Offset(size.width / 2f, size.height - DialGeometry.ORIGIN_BOTTOM_DP.dp.toPx())
                                val previous = event.calculateCentroid(useCurrent = false) - origin
                                val current = event.calculateCentroid(useCurrent = true) - origin
                                viewModel.onTransform(zoom, event.calculateRotation(),
                                    PlanOffset(previous.x.toDouble(), previous.y.toDouble()),
                                    PlanOffset(current.x.toDouble(), current.y.toDouble()), size.height - DialGeometry.CHART_INSET_DP.dp.toPx())
                            } else if (zoom != 1f) viewModel.onZoom(zoom)
                            event.changes.forEach { it.consume() }
                        } else if (multiTouch || dragging) {
                            // Finish a pinch without turning the remaining finger into a drag/tap.
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        DialCanvas(
            headingDeg = heading ?: 0.0,
            compassHeadingDeg = compassHeading ?: heading ?: 0.0,
            headingUp = !state.exploring,
            tapeHidden = tapeHidden,
            viewFanAlpha = viewFanAlpha,
            bottomBleed = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() },
            mountains = state.mountains,
            rangeKm = state.rangeKm,
            modifier = Modifier.fillMaxSize(),
            onMountainTap = { selectedId = it.mountain.osmId },
            onObserverTap = { showObserver = true },
            summit = state.summit,
            altitudeM = location?.mslAltitudeM,
            maxPeaks = state.settings.maxPeaks,
            textScale = state.settings.textScale,
            latitude = location?.latitude,
            longitude = location?.longitude,
            viewportLatitude = state.location?.latitude,
            viewportLongitude = state.location?.longitude,
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
            Column(Modifier.align(Alignment.TopCenter).padding(top = DialGeometry.CHART_TOP_DP.dp, end = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StatusLine(
                    message = if (headingAccuracyLow && compassHeading != null) HEADING_ACCURACY_LOW_MESSAGE else null,
                    actionLabel = null,
                    onAction = {},
                )
                StatusLine(
                    message = statusMessage(state, headingAvailable = compassHeading != null),
                    // 手動取得モードでは左下の更新ボタンで取り直すので、ここには出さない。
                    actionLabel = if (state.offline && state.connected && !state.loading && !state.settings.manualFetch) "再取得" else null,
                    onAction = viewModel::retry,
                )
            }
        }

        // 手動位置モードでは、方位目盛りの代わりに左上の向きの表示と右上のコンパスを左右から出す。
        AnimatedVisibility(
            visible = manualChrome,
            enter = slideInHorizontally(tween(MODE_ANIMATION_MS)) { -it } + fadeIn(tween(MODE_ANIMATION_MS)),
            exit = slideOutHorizontally(tween(MODE_ANIMATION_MS)) { -it } + fadeOut(tween(MODE_ANIMATION_MS)),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp),
        ) {
            HeadingLabel(
                headingDeg = compassHeading ?: heading,
                altitudeM = location?.mslAltitudeM,
                textScale = state.settings.textScale,
            )
        }
        AnimatedVisibility(
            visible = manualChrome,
            enter = slideInHorizontally(tween(MODE_ANIMATION_MS)) { it } + fadeIn(tween(MODE_ANIMATION_MS)),
            exit = slideOutHorizontally(tween(MODE_ANIMATION_MS)) { it } + fadeOut(tween(MODE_ANIMATION_MS)),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
        ) {
            CompassIndicator(
                heading = heading,
                onClick = { viewModel.faceNorth(heading ?: 0.0, canvasWidth, canvasHeight) },
                enabled = state.location != null && canvasHeight > DialGeometry.CHART_INSET_DP,
            )
        }

        MapModeButton(
            manual = state.exploring,
            enabled = hasPermission && state.gpsLocation != null && canvasHeight > DialGeometry.CHART_INSET_DP,
            onClick = {
                if (state.exploring) viewModel.resetCenter { currentCompassHeading }
                else viewModel.enterManual(heading ?: 0.0)
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 36.dp),
        )

        // 左下: 設定、事前ダウンロード、手動取得モードなら山データの取得。屋外で押しやすいよう既定(40dp)より大きくする。
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = { showSettings = true }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "設定", Modifier.size(28.dp))
            }
            if (Features.AREA_DOWNLOAD) {
                FilledTonalIconButton(onClick = { showDownload = true }, modifier = Modifier.size(52.dp)) {
                    // ダウンロード中は画面を閉じていても進み具合が分かるよう、ボタンに出す。
                    val running = download.running
                    if (running != null && running.progress.doneTiles > 0) {
                        CircularProgressIndicator(
                            progress = { running.progress.fraction },
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            // 進みの少ないうちも輪の形が見えるよう、下地を描く。
                            trackColor = LocalContentColor.current.copy(alpha = 0.25f),
                        )
                    } else if (running != null) {
                        // 最初の区画が終わるまでは進みが 0 なので、回り続ける表示にする。
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_area_download),
                            contentDescription = "山データの事前ダウンロード",
                            Modifier.size(28.dp),
                        )
                    }
                }
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

        if (Features.AREA_DOWNLOAD && showDownload) {
            AreaDownloadScreen(
                state = download,
                onStart = downloadViewModel::start,
                onCancel = downloadViewModel::cancel,
                onDismissNotice = downloadViewModel::dismissNotice,
                onDelete = downloadViewModel::delete,
                onClose = { showDownload = false },
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

    val overlay = showSettings || showDownload
    if (selected != null && !overlay) {
        MountainDetailDialog(selected, onDismiss = { selectedId = null })
    }

    // 現在地を取れる前は出す値がないので開かない。開いている間も歩けば値が更新される。
    if (showObserver && location != null && !overlay) {
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

private fun statusMessage(state: DialUiState, headingAvailable: Boolean): String? {
    // 取得半径(表示範囲より広い)の中に未取得の区画があると incomplete になる。欠けているのはたいてい取得半径の外縁なので、周辺に保存済みの山があれば「周辺の一部」と言う。
    val missing = if (state.mountains.isEmpty() && state.summit == null) "この付近の山データがありません" else "周辺の一部の山データがありません"
    return when {
        state.location == null -> "現在地を取得しています…"
        !headingAvailable -> "方位センサーの値を待っています…"
        state.loading -> "山データを取得中…"
        !state.connected && state.incomplete -> "圏外のため、$missing"
        !state.connected -> "圏外: 保存済みのデータで表示中"
        state.offline && state.incomplete -> "通信できず、$missing"
        state.offline -> "オフライン: 保存済みのデータで表示中"
        state.settings.manualFetch && state.incomplete -> "$missing。左下の更新ボタンで取得できます"
        else -> null
    }
}

/**
 * 手動位置モードの左上に出す、端末の向きと現在地の標高。コンパスと縦の中心をそろえる。
 * ヘディングアップの方位目盛りの下の札と同じ見た目(淡い白の札、方位は濃い色、標高は灰色)にする。
 */
@Composable
private fun HeadingLabel(headingDeg: Double?, altitudeM: Double?, textScale: Float) {
    val text = buildAnnotatedString {
        if (headingDeg == null) {
            append("方位を取得中")
        } else {
            val (direction, altitude) = readoutParts(headingDeg, altitudeM)
            append("向き $direction")
            withStyle(SpanStyle(color = TapeSubtle)) { append(altitude) }
        }
    }
    Box(Modifier.height(56.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text,
            color = TapeInk,
            fontSize = 15.sp * textScale,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 3.dp),
        )
    }
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
