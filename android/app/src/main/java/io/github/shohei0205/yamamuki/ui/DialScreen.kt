// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

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
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.semantics.Role
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
import io.github.shohei0205.yamamuki.ads.AdBanner
import io.github.shohei0205.yamamuki.core.DialGeometry
import io.github.shohei0205.yamamuki.core.Heading
import io.github.shohei0205.yamamuki.core.PanGeometry
import io.github.shohei0205.yamamuki.core.PlanOffset
import io.github.shohei0205.yamamuki.core.HeadingFilter
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.PeakDataset
import io.github.shohei0205.yamamuki.core.PeakManifest
import io.github.shohei0205.yamamuki.core.byteSizeText
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
    // アプリが前面に出るたびに、設定で選んでいれば新しい山データがあるかを確かめる(前回から 30 日たっていなければ何もしない)。
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.autoCheckPeakData() }
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
    // 双眼鏡の位置。検索位置モードでは選んだ山頂。
    val location = state.observer
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
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDownload by rememberSaveable { mutableStateOf(false) }
    val showSearch = state.search != null
    var showObserver by remember { mutableStateOf(false) }
    // 重なる山をまとめた代表の山をタップしたときの一覧。ID で持ち、表示中の一覧から引く。
    var groupIds by remember { mutableStateOf<List<String>?>(null) }
    val group = groupIds?.mapNotNull { id -> state.mountains.firstOrNull { it.mountain.id == id } }?.takeIf { it.isNotEmpty() }
    val selected = state.mountains.firstOrNull { it.mountain.id == selectedId }
        ?: state.summit?.takeIf { it.mountain.id == selectedId }
    // 取り直しで一覧から消えたら選択も解く。残しておくと、その山が一覧に戻ったときにダイアログが勝手に開く。
    val selectionLost = selectedId != null && selected == null
    LaunchedEffect(selectionLost) {
        if (selectionLost) selectedId = null
    }
    val groupLost = groupIds != null && group == null
    LaunchedEffect(groupLost) {
        if (groupLost) groupIds = null
    }

    val safeDrawing = WindowInsets.safeDrawing
    Box(
        Modifier
            .fillMaxSize()
            .background(DialGround)
            // ステータスバーの裏は、ヘッダーの空の上端と同じ色で塗り、空がつながって見えるようにする。
            .drawBehind { drawRect(HeaderSkyTop, size = Size(size.width, safeDrawing.getTop(this).toFloat())) }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onSizeChanged {
                // 検索画面でキーボードが出ている間は、方位盤の大きさを変えない(選んだ山頂を中央に置く計算がずれるため)。
                if (showSearch) return@onSizeChanged
                canvasHeight = it.height / density.density.toDouble()
                canvasWidth = it.width / density.density.toDouble()
            }
            .pointerInput(showSettings, showSearch) {
                if (showSettings || showSearch) return@pointerInput
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
            onMountainTap = { selectedId = it.mountain.id },
            onGroupTap = { peaks -> groupIds = peaks.map { it.mountain.id } },
            onObserverTap = { showObserver = true },
            summit = state.summit,
            summitLabel = state.viewpoint == null,
            altitudeM = location?.mslAltitudeM,
            maxPeaks = state.settings.maxPeaks,
            textScale = state.settings.textScale,
            gridStyle = state.settings.gridStyle,
            latitude = location?.latitude,
            longitude = location?.longitude,
            viewportLatitude = state.location?.latitude,
            viewportLongitude = state.location?.longitude,
        )
        // 配布版では、ヘッダーの中央にバナー広告(320×50)を出す。広告を入れないビルドでは何も出さない(build.gradle.kts の ads)。
        AdBanner(Modifier.align(Alignment.TopCenter).padding(top = ((DialGeometry.HEADER_HEIGHT_DP - 50) / 2).dp))
        Text(
            stringResource(state.settings.peakDataset.attribution),
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
                // 古いデータのままでも使えるので、自動では取り直さず、いつ取りに行くかは利用者に任せる。
                // どちらも設定の「更新を確認」と同じ流れにする(新しい版の大きさを見せて確認する。圏外なら通信せずに知らせる)。
                if (state.peakDataRefreshNeeded) {
                    PeakDataLabel(stringResource(R.string.peak_data_refresh_needed), onClick = viewModel::updatePeakData)
                } else if (state.peakDataUpdateLabelShown) {
                    // 設定で自動の確認をオンにしたときに、新しい版が見つかったら出す。
                    PeakDataLabel(stringResource(R.string.peak_data_update_found), onClick = viewModel::updatePeakData)
                }
            }
        }

        // 手動位置モードでは、方位目盛りの代わりに左上の向きの表示と右上のコンパスを左右から出す。どちらもヘッダーの下に置く。
        AnimatedVisibility(
            visible = manualChrome,
            enter = slideInHorizontally(tween(MODE_ANIMATION_MS)) { -it } + fadeIn(tween(MODE_ANIMATION_MS)),
            exit = slideOutHorizontally(tween(MODE_ANIMATION_MS)) { -it } + fadeOut(tween(MODE_ANIMATION_MS)),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = DialGeometry.HEADER_HEIGHT_DP.dp + 8.dp),
        ) {
            // 検索位置モードでは、手動位置モードと見分けられるよう、向きの代わりに選んだ山の札を出す。
            val viewpoint = state.viewpoint
            if (viewpoint != null) {
                ViewpointLabel(mountain = viewpoint, headingDeg = compassHeading ?: heading, textScale = state.settings.textScale)
            } else {
                HeadingLabel(
                    headingDeg = compassHeading ?: heading,
                    altitudeM = location?.mslAltitudeM,
                    textScale = state.settings.textScale,
                )
            }
        }
        AnimatedVisibility(
            visible = manualChrome,
            enter = slideInHorizontally(tween(MODE_ANIMATION_MS)) { it } + fadeIn(tween(MODE_ANIMATION_MS)),
            exit = slideOutHorizontally(tween(MODE_ANIMATION_MS)) { it } + fadeOut(tween(MODE_ANIMATION_MS)),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = DialGeometry.HEADER_HEIGHT_DP.dp + 8.dp, end = 8.dp),
        ) {
            // 北が上なら地図を端末の向きに合わせ、それ以外なら北を上にする。方位が未取得なら北を上にするだけ。
            val tapFacesDevice = compassHeading != null && PanGeometry.compassTapFacesDevice(heading ?: 0.0)
            CompassIndicator(
                heading = heading,
                tapFacesDevice = tapFacesDevice,
                onClick = {
                    if (tapFacesDevice) viewModel.faceDevice({ currentCompassHeading }, canvasWidth, canvasHeight)
                    else viewModel.faceNorth(heading ?: 0.0, canvasWidth, canvasHeight)
                },
                enabled = state.location != null && canvasHeight > DialGeometry.CHART_INSET_DP,
            )
        }

        // 右下: 山の検索と、モードの切り替え。検索はどちらのモードからでも使える。
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundMapButton(
                onClick = viewModel::openSearch,
                enabled = canvasHeight > DialGeometry.CHART_INSET_DP,
                iconColor = SEARCH_BUTTON_ICON_COLOR,
            ) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_open), Modifier.size(28.dp))
            }
            MapModeButton(
                manual = state.exploring,
                searching = state.viewpoint != null,
                // 検索位置モードからは、現在地が分からなくても抜けられるようにする。
                enabled = state.viewpoint != null ||
                    (hasPermission && state.gpsLocation != null && canvasHeight > DialGeometry.CHART_INSET_DP),
                onClick = {
                    // 検索位置モードは手動位置モードへ、手動位置モードはヘディングアップへ、ヘディングアップは手動位置モードへ。
                    if (state.viewpoint != null) viewModel.leaveViewpoint { currentCompassHeading }
                    else if (state.exploring) viewModel.resetCenter { currentCompassHeading }
                    else viewModel.enterManual(heading ?: 0.0)
                },
            )
        }

        // 左下: 設定と事前ダウンロード。右下のモード切替ボタンと同じ見た目・同じ高さにそろえる。
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundMapButton(onClick = { showSettings = true }, iconColor = SETTINGS_BUTTON_ICON_COLOR) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title), Modifier.size(28.dp))
            }
            if (Features.AREA_DOWNLOAD) {
                RoundMapButton(onClick = { showDownload = true }, iconColor = DOWNLOAD_BUTTON_ICON_COLOR) {
                    // ダウンロード中は画面を閉じていても進み具合が分かるよう、ボタンに出す。
                    val running = download.running
                    if (running != null && running.progress.doneTiles > 0) {
                        CircularProgressIndicator(
                            progress = { running.progress.fraction },
                            modifier = Modifier.size(28.dp),
                            color = LocalContentColor.current,
                            strokeWidth = 3.dp,
                            // 進みの少ないうちも輪の形が見えるよう、下地を描く。
                            trackColor = LocalContentColor.current.copy(alpha = 0.25f),
                        )
                    } else if (running != null) {
                        // 最初の区画が終わるまでは進みが 0 なので、回り続ける表示にする。
                        CircularProgressIndicator(Modifier.size(28.dp), color = LocalContentColor.current, strokeWidth = 3.dp)
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
                onSettingsChange = viewModel::updateSettings,
                peakData = state.peakData,
                peakDataUpdating = state.peakDataUpdating,
                peakDataNotice = state.peakDataNotice,
                onUpdatePeakData = viewModel::updatePeakData,
                onOpen = viewModel::refreshPeakData,
                onClose = {
                    viewModel.closePeakDataList()
                    showSettings = false
                },
                peakGroups = state.peakGroups,
                loadCredits = viewModel::peakDataCredits,
                peakDataList = state.peakDataList,
                onOpenPeakDataList = viewModel::openPeakDataList,
                onClosePeakDataList = viewModel::closePeakDataList,
                onChoosePeakData = viewModel::choosePeakData,
                onDeletePeakData = viewModel::deletePeakData,
            )
        }

        state.search?.let { search ->
            MountainSearchScreen(
                search = search,
                from = state.gpsLocation,
                onQueryChange = viewModel::onSearchQuery,
                onSelect = { viewModel.showFromMountain(it, heading ?: 0.0, canvasHeight) },
                onClose = viewModel::closeSearch,
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

    // 新しい山データの大きさを見せて、ダウンロードするかを聞く(初回の問い合わせのあとと、設定画面の確認から)。
    state.peakDataOffer?.let { manifest ->
        PeakDataOfferDialog(manifest = manifest, first = state.peakDataOfferFirst, onAnswer = viewModel::answerPeakDataOffer)
    }

    // 通信の失敗は、方位を待つ表示などに隠れて気づけないことがないよう、画面中央で知らせる。
    state.fetchErrorMessage?.let { message ->
        FetchErrorDialog(
            message = message,
            onRetry = viewModel::retryAfterFetchError,
            onDismiss = viewModel::dismissFetchError,
        )
    }

    val overlay = showSettings || showDownload || showSearch
    if (selected != null && !overlay) {
        MountainDetailDialog(
            selected,
            viewpoint = state.viewpoint,
            onGoToSummit = {
                selectedId = null
                viewModel.showFromMountain(selected.mountain, heading ?: 0.0, canvasHeight)
            },
            onDismiss = { selectedId = null },
        )
    } else if (group != null && !overlay) {
        PeakGroupDialog(
            group,
            onSelect = {
                groupIds = null
                selectedId = it.mountain.id
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

/**
 * 新しい山データを作った日と大きさを知らせ、ダウンロードするかを聞く。どちらかを選ぶまで閉じない。
 * まだ取得していない種類を初めて取得するとき([first])は、更新のように読めないよう、種類の名前を出して聞く。
 */
@Composable
private fun PeakDataOfferDialog(manifest: PeakManifest, first: PeakDataset?, onAnswer: (Boolean) -> Unit) {
    val size = byteSizeText(manifest.sizeBytes)
    // データを作った日が版の文字列から読めなければ、大きさだけを出す。
    val date = manifest.releaseDate
    val detail = if (date == null) {
        stringResource(R.string.peak_data_offer_size, size)
    } else {
        stringResource(R.string.peak_data_offer_detail, date, size)
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.peak_data_offer_title)) },
        text = {
            Text(
                if (first == null) {
                    stringResource(R.string.peak_data_offer_message, detail)
                } else {
                    stringResource(R.string.peak_data_offer_message_first, stringResource(first.label), detail)
                },
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.peak_data_offer_download)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.peak_data_offer_later)) } },
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

/**
 * タップした山の詳細。検索位置モード([viewpoint] が非 null)では、距離は選んだ山頂から測ったもの。
 * 「山頂へ移動」で、その山頂を中心にした検索位置モードに移る([onGoToSummit])。
 */
@Composable
private fun MountainDetailDialog(
    nearby: NearbyMountain,
    viewpoint: Mountain?,
    onGoToSummit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val m = nearby.mountain
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        title = { Text(m.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(stringResource(R.string.detail_elevation), elevationLabel(m.elevationM))
                DetailRow(stringResource(R.string.detail_coordinate), coordinateLabel(m.latitude, m.longitude))
                when {
                    viewpoint == null -> DetailRow(stringResource(R.string.detail_distance), distanceText(nearby.distanceKm))
                    // 選んだ山頂そのものは、山頂からの距離が 0 なので出さない。
                    viewpoint.id != m.id ->
                        DetailRow(stringResource(R.string.detail_distance_from_summit), distanceText(nearby.distanceKm))
                }
                // 選んだ山頂にいるときは、移る先が今の場所と同じなので出さない。
                if (viewpoint?.id != m.id) {
                    TextButton(onClick = onGoToSummit, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.detail_go_to_summit))
                    }
                }
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
                                elevationListLabel(nearby.mountain.elevationM),
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
internal fun elevationLabel(elevationM: Double?): String =
    elevationText(elevationM) ?: stringResource(R.string.common_unknown)

/** 一覧や札に出す標高。「1,212 m」、不明なら「標高不明」(見出しが無いので、何が不明かを書く)。 */
@Composable
internal fun elevationListLabel(elevationM: Double?): String =
    elevationText(elevationM) ?: stringResource(R.string.elevation_unknown)

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
    val heading = if (parts == null) stringResource(R.string.dial_heading_loading) else parts.first
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

/**
 * 検索位置モードの左上に出す、選んだ山の札。山頂の標識をまねた木の横板に、山名と標高、緯度経度を書き、
 * 支柱の横の白い小札に端末の向きを出す。向きは手動位置モードの左上の表示と同じく、端末が向いている方位にする。
 * そのまま画面を撮っても、どの山から見た方位盤かが分かるようにする。
 */
@Composable
private fun ViewpointLabel(mountain: Mountain, headingDeg: Double?, textScale: Float) {
    val texts = rememberDialTexts()
    val lat = stringResource(
        if (mountain.latitude >= 0) R.string.detail_latitude_north else R.string.detail_latitude_south,
        degreeText(mountain.latitude),
    )
    val lon = stringResource(
        if (mountain.longitude >= 0) R.string.detail_longitude_east else R.string.detail_longitude_west,
        degreeText(mountain.longitude),
    )
    val heading = headingDeg?.let { texts.readoutParts(it, null).first }
        ?: stringResource(R.string.dial_heading_loading)
    val elevation = elevationListLabel(mountain.elevationM)
    val boardShape = RoundedCornerShape(6.dp)
    // 山名の長さによらず、札の幅は決まった幅にする。撮った画面の見た目がそろう。長い山名は折り返す。
    Column(Modifier.width(260.dp)) {
        // 木の板。木目と、左右のビスを描く。
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(4.dp, boardShape)
                .background(Brush.verticalGradient(listOf(WoodLight, WoodDark)), boardShape)
                .drawBehind { drawWoodGrain() }
                .border(2.dp, WoodEdge, boardShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, letterSpacing = 0.12.em)) {
                        append(mountain.name)
                    }
                    withStyle(SpanStyle(fontSize = 12.5.sp * textScale, fontWeight = FontWeight.Bold)) { append("  $elevation") }
                },
                color = WoodInk,
                fontSize = 21.sp * textScale,
            )
            Text("$lat  $lon", color = WoodInkSubtle, fontSize = 11.sp * textScale, fontWeight = FontWeight.Medium)
        }
        // 支柱と、その横の向きの小札。
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .padding(start = 24.dp)
                    .size(width = 10.dp, height = 14.dp)
                    .background(Brush.horizontalGradient(listOf(PostDark, PostLight)))
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawRect(WoodEdge, size = Size(w, size.height))
                        drawRect(WoodEdge, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height))
                    },
            )
            Text(
                heading,
                color = TapeInk,
                fontSize = 12.sp * textScale,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(start = 10.dp, top = 3.dp)
                    .shadow(2.dp, RoundedCornerShape(50))
                    .background(Color.White, RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 1.dp),
            )
        }
    }
}

/** 札の板の木目(細い縦の筋)と、左右の端のビス。 */
private fun DrawScope.drawWoodGrain() {
    val step = 5.dp.toPx()
    var x = step / 2
    var i = 0
    while (x < size.width) {
        // 筋の濃さと間隔を少しずつ変えて、木目らしくする。文字が読みにくくならないよう、筋は薄くする。
        val light = i % 3 == 0
        drawRect(
            if (light) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.025f),
            topLeft = Offset(x, 0f),
            size = Size(if (light) 3.dp.toPx() else 2.dp.toPx(), size.height),
        )
        x += step + (i % 4) * 1.dp.toPx()
        i++
    }
    val r = 2.5f.dp.toPx()
    val inset = 6.5f.dp.toPx()
    for (cx in listOf(inset, size.width - inset)) {
        drawCircle(WoodScrew, radius = r, center = Offset(cx, size.height / 2))
        drawCircle(Color.White.copy(alpha = 0.3f), radius = r / 2.5f, center = Offset(cx - r / 3, size.height / 2 - r / 3))
    }
}

private val WoodLight = Color(0xFFE9CFA2)
private val WoodDark = Color(0xFFDDBB86)
private val WoodEdge = Color(0xFF7A5230)
private val WoodScrew = Color(0xFF5B3A1E)
private val WoodInk = Color(0xFF1E140A)
private val WoodInkSubtle = Color(0xFF2B1D10)
private val PostDark = Color(0xFF8A6036)
private val PostLight = Color(0xFFA57646)

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

/**
 * 山データの取り直しが要ることや、新しい山データがあることを知らせるラベル。タップで更新を確かめる。
 * ほかの知らせと見分けやすいよう、濃い白の札にする。
 */
@Composable
private fun PeakDataLabel(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.9f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = DOWNLOAD_BUTTON_ICON_COLOR, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = TapeInk,
            fontWeight = FontWeight.Bold,
        )
    }
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
