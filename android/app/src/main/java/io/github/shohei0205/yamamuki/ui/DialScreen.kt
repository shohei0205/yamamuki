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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
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
import io.github.shohei0205.yamamuki.core.degreeText
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

    // 初回は「山データを取得しますか」を先に聞き、答えてから位置情報の許可を求める(ダイアログを重ねない)。
    val peakDataAsked = state.settings.peakDataAsked
    LaunchedEffect(peakDataAsked) {
        if (peakDataAsked && !hasPermission) permissionLauncher.launch(LOCATION_PERMISSIONS)
    }
    // 設定の「位置と方位の精度」を変えたら、測り方を変えて頼み直す。
    val precision = state.settings.sensorPrecision
    LaunchedEffect(hasPermission, precision) {
        if (!hasPermission) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            locationUpdates(context, precision.locationIntervalMs).collect {
                val msl = withContext(Dispatchers.IO) { mslAltitudeM(context, it) }
                viewModel.onLocation(GeoPoint(it.latitude, it.longitude, it.altitude, msl))
            }
        }
    }

    // センサーは磁北基準なので、現在地の偏角(日本ではおよそ西へ 7〜10°)を足して真北基準にする。
    val magneticHeading by remember(context, precision) {
        // 値が届く間隔を変えても、平滑化で追いつくまでの時間は変わらないようにする。
        val filter = HeadingFilter(HeadingFilter.alphaForPeriod(precision.headingPeriodMs.toDouble()))
        magneticHeadingUpdates(context, precision.headingPeriodMs * 1_000)
            .map { filter.update(it) }
            // センサーは高精度なら毎秒 50 回ほど届く。端末を止めているときの細かな揺れで画面全体を描き直さないよう、
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
    // 重なる山をまとめた代表の山をタップしたときの一覧。ID で持ち、表示中の一覧から引く。
    var groupIds by remember { mutableStateOf<List<Long>?>(null) }
    val group = groupIds?.mapNotNull { id -> state.mountains.firstOrNull { it.mountain.osmId == id } }?.takeIf { it.isNotEmpty() }
    val selected = state.mountains.firstOrNull { it.mountain.osmId == selectedId }
        ?: state.summit?.takeIf { it.mountain.osmId == selectedId }
    // 取り直しで一覧から消えたら選択も解く。残しておくと、その山が一覧に戻ったときにダイアログが勝手に開く。
    val selectionLost = selectedId != null && selected == null
    LaunchedEffect(selectionLost) {
        if (selectionLost) selectedId = null
    }
    val groupLost = groupIds != null && group == null
    LaunchedEffect(groupLost) {
        if (groupLost) groupIds = null
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
            onGroupTap = { peaks -> groupIds = peaks.map { it.mountain.osmId } },
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
            stringResource(R.string.osm_attribution),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )

        if (!hasPermission) {
            PermissionRequest(
                onRequest = { permissionLauncher.launch(LOCATION_PERMISSIONS) },
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Column(Modifier.align(Alignment.TopCenter).padding(top = DialGeometry.CHART_TOP_DP.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // 方位センサーの精度が低いときは、上部の情報ラベルの下に案内を出す。
                StatusLine(
                    message = if (headingAccuracyLow && compassHeading != null) stringResource(R.string.dial_heading_accuracy_low) else null,
                    // 距離の円が文字の後ろを通っても読めるよう、方位の札と同じ淡い白の札にする。
                    labeled = true,
                )
                StatusLine(message = statusMessage(state, headingAvailable = compassHeading != null))
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

        // 左下: 設定と事前ダウンロード。右下のモード切替ボタンと同じ見た目・同じ高さにそろえる。
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundMapButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title), Modifier.size(28.dp))
            }
            if (Features.AREA_DOWNLOAD) {
                RoundMapButton(onClick = { showDownload = true }) {
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
                            contentDescription = stringResource(R.string.dial_area_download),
                            Modifier.size(28.dp),
                        )
                    }
                }
            }
        }

        if (showSettings) {
            SettingsScreen(
                settings = state.settings,
                cacheInfo = state.cacheInfo,
                onSettingsChange = viewModel::updateSettings,
                peakData = state.peakData,
                peakDataUpdating = state.peakDataUpdating,
                peakDataNotice = state.peakDataNotice,
                onUpdatePeakData = viewModel::updatePeakData,
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

    if (!peakDataAsked) {
        PeakDataPrompt(onAnswer = viewModel::answerPeakDataPrompt)
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
    } else if (group != null && !overlay) {
        PeakGroupDialog(
            group,
            onSelect = {
                groupIds = null
                selectedId = it.mountain.osmId
            },
            onDismiss = { groupIds = null },
        )
    }

    // 現在地を取れる前は出す値がないので開かない。開いている間も歩けば値が更新される。
    if (showObserver && location != null && !overlay) {
        ObserverDetailDialog(location, onDismiss = { showObserver = false })
    }
}

/** 初回起動時に、全国の山データを取得するかを聞く。どちらかを選ぶまで閉じない。 */
@Composable
private fun PeakDataPrompt(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.peak_data_prompt_title)) },
        text = { Text(stringResource(R.string.peak_data_prompt_message)) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.peak_data_prompt_fetch)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.peak_data_prompt_later)) } },
    )
}

/** 山データの取得に失敗したことを知らせ、再取得できるようにする。 */
@Composable
private fun FetchErrorDialog(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.peak_data_error_title)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onRetry) { Text(stringResource(R.string.peak_data_error_retry)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

/** タップした山の詳細。 */
@Composable
private fun MountainDetailDialog(nearby: NearbyMountain, onDismiss: () -> Unit) {
    val m = nearby.mountain
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        title = { Text(m.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(stringResource(R.string.detail_elevation), elevationLabel(m.elevationM))
                DetailRow(stringResource(R.string.detail_coordinate), coordinateLabel(m.latitude, m.longitude))
                DetailRow(stringResource(R.string.detail_distance), distanceText(nearby.distanceKm))
            }
        },
    )
}

/** 重なる山をまとめた代表の山をタップしたときの一覧。山を選ぶと、その山の詳細を開く。 */
@Composable
private fun PeakGroupDialog(peaks: List<NearbyMountain>, onSelect: (NearbyMountain) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        title = { Text(stringResource(R.string.detail_group_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (nearby in peaks) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(nearby) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(nearby.mountain.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(
                                R.string.detail_group_item,
                                elevationLabel(nearby.mountain.elevationM),
                                distanceText(nearby.distanceKm),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        },
    )
}

/** 双眼鏡(現在地)をタップしたときの詳細。距離は常に 0 なので出さない。 */
@Composable
private fun ObserverDetailDialog(location: GeoPoint, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        title = { Text(stringResource(R.string.detail_observer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(stringResource(R.string.detail_coordinate), coordinateLabel(location.latitude, location.longitude))
                DetailRow(stringResource(R.string.detail_elevation), elevationLabel(location.mslAltitudeM))
            }
        },
    )
}

/** 詳細表示の標高。「1,212 m」、不明なら「不明」。山と現在地で共通に使う。 */
@Composable
private fun elevationLabel(elevationM: Double?): String =
    elevationText(elevationM) ?: stringResource(R.string.common_unknown)

/** 詳細表示の緯度経度。狭い画面で途中で折り返さないよう、緯度と経度を改行で分ける。山と現在地で共通に使う。 */
@Composable
private fun coordinateLabel(latitude: Double, longitude: Double): String {
    val lat = stringResource(
        if (latitude >= 0) R.string.detail_latitude_north else R.string.detail_latitude_south,
        degreeText(latitude),
    )
    val lon = stringResource(
        if (longitude >= 0) R.string.detail_longitude_east else R.string.detail_longitude_west,
        degreeText(longitude),
    )
    return "$lat\n$lon"
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun statusMessage(state: DialUiState, headingAvailable: Boolean): String? {
    // 取得半径(表示範囲より広い)の中に未取得の区画があると incomplete になる。欠けているのはたいてい取得半径の外縁なので、周辺に保存済みの山があれば「周辺の一部」と言う。
    val missing = stringResource(
        if (state.mountains.isEmpty() && state.summit == null) R.string.dial_status_no_data_here else R.string.dial_status_partial_data,
    )
    return when {
        state.location == null -> stringResource(R.string.dial_status_locating)
        !headingAvailable -> stringResource(R.string.dial_status_waiting_heading)
        state.peakDataUpdating -> stringResource(R.string.dial_status_fetching)
        state.loading -> stringResource(R.string.dial_status_loading)
        state.incomplete && state.peakData == null -> stringResource(R.string.dial_status_fetch_hint, missing)
        state.incomplete -> missing
        else -> null
    }
}

/**
 * 手動位置モードの左上に出す、端末の向きと現在地の標高。コンパスと縦の中心をそろえる。
 * ヘディングアップの方位目盛りの下の札と同じ見た目(淡い白の札、方位は濃い色、標高は灰色)にする。
 */
@Composable
private fun HeadingLabel(headingDeg: Double?, altitudeM: Double?, textScale: Float) {
    val texts = rememberDialTexts()
    val parts = headingDeg?.let { texts.readoutParts(it, altitudeM) }
    val heading = if (parts == null) stringResource(R.string.dial_heading_loading) else stringResource(R.string.dial_heading, parts.first)
    val text = buildAnnotatedString {
        append(heading)
        if (parts != null) withStyle(SpanStyle(color = TapeSubtle)) { append(parts.second) }
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
private fun StatusLine(message: String?, modifier: Modifier = Modifier, labeled: Boolean = false) {
    if (message == null) return
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp).then(
            if (labeled) {
                Modifier
                    .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 3.dp)
            } else {
                Modifier
            },
        ),
    )
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.dial_permission_message_with_hint), textAlign = TextAlign.Center)
        Button(onClick = onRequest) { Text(stringResource(R.string.dial_permission_allow)) }
    }
}
