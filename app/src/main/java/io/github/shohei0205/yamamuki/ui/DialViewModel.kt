package io.github.shohei0205.yamamuki.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.shohei0205.yamamuki.YamamukiApp
import io.github.shohei0205.yamamuki.core.DialGeometry
import io.github.shohei0205.yamamuki.core.GeoMath
import io.github.shohei0205.yamamuki.core.Heading
import io.github.shohei0205.yamamuki.core.MapCenter
import io.github.shohei0205.yamamuki.core.PanGeometry
import io.github.shohei0205.yamamuki.core.PlanOffset
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.OverpassException
import io.github.shohei0205.yamamuki.core.displayPriority
import io.github.shohei0205.yamamuki.core.meetsMinElevation
import io.github.shohei0205.yamamuki.core.seenFrom
import io.github.shohei0205.yamamuki.core.summitAt
import io.github.shohei0205.yamamuki.data.CacheInfo
import io.github.shohei0205.yamamuki.sensor.connectivityUpdates
import io.github.shohei0205.yamamuki.settings.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    /** GPS の高さ(楕円体高)。磁気偏角の計算に使う。 */
    val altitudeM: Double = 0.0,
    /** 標高(海抜)。求められないときは null。 */
    val mslAltitudeM: Double? = null,
)

data class DialUiState(
    val location: GeoPoint? = null,
    val gpsLocation: GeoPoint? = null,
    val exploring: Boolean = false,
    val observerLocation: GeoPoint? = null,
    val lockedHeading: Double? = null,
    /** 現在地から見た山。最低標高で絞り込み、表示の優先順(標高の高い順)に並べたもの。現在地が変わるたびに計算し直す。 */
    val mountains: List<NearbyMountain> = emptyList(),
    /**
     * 現在地がほぼ山頂([io.github.shohei0205.yamamuki.core.SUMMIT_RADIUS_KM] 以内)のとき、その山。
     * 最低標高の絞り込みとは関係なく探し、[mountains] からは除く(現在地の位置に別のアイコンで出す)。
     */
    val summit: NearbyMountain? = null,
    /** 現在地から画面上端までの距離。 */
    val rangeKm: Double = DialGeometry.DEFAULT_RANGE_KM,
    val loading: Boolean = false,
    /** 通信に失敗した、または圏外で取得を控えたため、キャッシュだけで表示している。 */
    val offline: Boolean = false,
    /** 端末が通信できる状態か。圏外や機内モードでは false になり、取得を控えて保存済みのデータで表示する。 */
    val connected: Boolean = true,
    /** 通信に失敗したときに画面中央で知らせる文言。閉じるまで保つ。 */
    val fetchErrorMessage: String? = null,
    /** 範囲内に一度も取得できていない地域がある。 */
    val incomplete: Boolean = false,
    /** 手動取得モードのため、未取得または古い地域があっても通信しなかった。 */
    val networkSkipped: Boolean = false,
    val settings: Settings = Settings(),
    /** 設定画面に出すキャッシュの状況。読み込むまでは null。 */
    val cacheInfo: CacheInfo? = null,
)

/** 現在地と表示範囲に応じて山データを取得し、方位盤に出す山の一覧を保つ。 */
class DialViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YamamukiApp
    private val repository = app.mountainRepository
    private val appSettings = app.settings
    private val cacheManager = app.cacheManager

    private val _state = MutableStateFlow(
        appSettings.settings.value.let { DialUiState(settings = it, rangeKm = it.initialRangeKm.toDouble()) },
    )
    val state: StateFlow<DialUiState> = _state.asStateFlow()
    private var northUpJob: Job? = null
    private var headingSwipeAroundCenter = false

    private var peaks: List<Mountain> = emptyList()
    private var fetchedCenter: GeoPoint? = null
    private var fetchedRadiusKm = 0.0
    private var fetchJob: Job? = null

    /** 圏外のため取得を控えた。値は手動の取得だったか。つながったらその続きを取得する。 */
    private var skippedWhileDisconnected: Boolean? = null

    init {
        viewModelScope.launch { connectivityUpdates(application).collect(::onConnectivity) }
        // 事前ダウンロードで現在地の周辺が埋まったり消えたりしたら、表示を読み直す(通信はしない)。
        viewModelScope.launch { app.cacheChanges.collect { reloadFromCache() } }
    }

    private fun onConnectivity(connected: Boolean) {
        if (connected == _state.value.connected) return
        _state.update { it.copy(connected = connected) }
        // 圏外で控えていた取得を、つながったところで行う。
        val manual = skippedWhileDisconnected
        if (connected && manual != null) {
            skippedWhileDisconnected = null
            fetch(manual = manual)
        }
    }

    fun onLocation(newPoint: GeoPoint) {
        // GPS とネットワーク位置が交互に届くと、高さを持たない位置で標高の表示が消えたり出たりする。
        // 高さが無い位置では直前の標高を引き継ぐ。
        val point = if (newPoint.mslAltitudeM == null) {
            newPoint.copy(mslAltitudeM = _state.value.gpsLocation?.mslAltitudeM)
        } else {
            newPoint
        }
        _state.update {
            if (it.exploring) it.copy(gpsLocation = point)
            else it.copy(gpsLocation = point, location = point, observerLocation = point).withPeaksAt(point)
        }
        if (_state.value.exploring) return
        val center = fetchedCenter
        if (center == null ||
            GeoMath.distanceKm(center.latitude, center.longitude, point.latitude, point.longitude) > REFETCH_DISTANCE_KM
        ) {
            fetch()
        }
    }

    fun onZoom(zoom: Float) {
        northUpJob?.cancel()
        _state.update { it.copy(rangeKm = DialGeometry.zoomedRange(it.rangeKm, zoom)) }
        if (DialGeometry.fetchRadiusKm(_state.value.rangeKm) > fetchedRadiusKm) fetch()
    }

    fun onPan(dxPx: Float, dyPx: Float, chartHeightPx: Float, headingDeg: Double) {
        northUpJob?.cancel()
        val here = state.value.location ?: return
        val next = PanGeometry.drag(MapCenter(here.latitude, here.longitude), dxPx.toDouble(), dyPx.toDouble(),
            chartHeightPx / state.value.rangeKm, state.value.lockedHeading ?: headingDeg)
        if (next.latitude == here.latitude && next.longitude == here.longitude) return
        val point = GeoPoint(next.latitude, next.longitude)
        _state.update { it.copy(location = point, exploring = true,
            observerLocation = it.observerLocation ?: here, lockedHeading = it.lockedHeading ?: headingDeg) }
        fetchForViewport()
    }

    fun resetCenter(compassHeading: () -> Double) {
        northUpJob?.cancel()
        val initial = state.value
        val startLocation = initial.location ?: return
        val startObserver = initial.observerLocation ?: startLocation
        if (initial.gpsLocation == null) return
        val startHeading = initial.lockedHeading ?: compassHeading()
        val initialOffset = PanGeometry.observerOffset(MapCenter(startObserver.latitude, startObserver.longitude),
            MapCenter(startLocation.latitude, startLocation.longitude), startHeading)
        northUpJob = viewModelScope.launch {
            val started = System.nanoTime()
            do {
                val t = ((System.nanoTime() - started) / 500_000_000.0).coerceAtMost(1.0)
                val fraction = t * t * (3 - 2 * t)
                val target = state.value.gpsLocation ?: return@launch
                fun interpolate(from: GeoPoint): GeoPoint {
                    val point = PanGeometry.interpolateCenter(MapCenter(from.latitude, from.longitude),
                        MapCenter(target.latitude, target.longitude), fraction)
                    return target.copy(latitude = point.latitude, longitude = point.longitude)
                }
                val observer = interpolate(startObserver)
                val heading = Heading.normalize(startHeading + Heading.delta(startHeading, compassHeading()) * fraction)
                val viewport = PanGeometry.returnViewport(MapCenter(observer.latitude, observer.longitude), initialOffset, heading, fraction)
                _state.update { it.copy(
                    location = target.copy(latitude = viewport.latitude, longitude = viewport.longitude), observerLocation = observer,
                    exploring = t < 1.0, lockedHeading = if (t < 1.0) heading else null,
                ).withPeaksAt(observer) }
                if (t >= 1.0) break
                delay(16)
            } while (true)
            fetch()
        }
    }

    /** 双眼鏡が画面内なら双眼鏡、画面外なら画面中央を軸に、約0.5秒で北へ回す。 */
    fun faceNorth(headingDeg: Double, canvasWidth: Double, canvasHeight: Double) {
        if (!canvasHeight.isFinite() || canvasHeight <= 128) return
        northUpJob?.cancel()
        val initial = state.value
        val here = initial.location ?: return
        val observer = initial.observerLocation ?: here
        val startHeading = initial.lockedHeading ?: headingDeg
        val aroundCenter = !PanGeometry.isObserverVisible(
            MapCenter(observer.latitude, observer.longitude), MapCenter(here.latitude, here.longitude),
            startHeading, initial.rangeKm, canvasWidth, canvasHeight,
        )
        northUpJob = viewModelScope.launch {
            val started = System.nanoTime()
            do {
                val progress = ((System.nanoTime() - started) / 500_000_000.0).coerceAtMost(1.0)
                val next = PanGeometry.northUpViewport(
                    MapCenter(observer.latitude, observer.longitude), MapCenter(here.latitude, here.longitude),
                    startHeading, initial.rangeKm, canvasHeight, progress, aroundCenter,
                )
                _state.update { it.copy(
                    location = if (next.latitude == here.latitude && next.longitude == here.longitude) here
                        else GeoPoint(next.latitude, next.longitude),
                    exploring = true,
                    observerLocation = observer,
                    lockedHeading = PanGeometry.northUpHeading(startHeading, progress),
                ) }
                if (progress >= 1.0) break
                delay(16)
            } while (true)
            fetchForViewport()
        }
    }

    fun onHeadingSwipe(dxPx: Float, widthPx: Float, headingDeg: Double,
        canvasWidth: Double, canvasHeight: Double, started: Boolean) {
        northUpJob?.cancel()
        if (!dxPx.isFinite() || widthPx <= 0 || canvasHeight <= 128) return
        val current = state.value
        val here = current.location ?: return
        val observer = current.observerLocation ?: here
        val oldHeading = current.lockedHeading ?: headingDeg
        val observerPoint = MapCenter(observer.latitude, observer.longitude)
        val viewport = MapCenter(here.latitude, here.longitude)
        if (started) headingSwipeAroundCenter = !PanGeometry.isObserverVisible(
            observerPoint, viewport, oldHeading, current.rangeKm, canvasWidth, canvasHeight)
        val nextHeading = DialGeometry.swipedHeading(oldHeading, dxPx.toDouble(), widthPx.toDouble())
        val next = PanGeometry.rotateViewport(observerPoint, viewport, oldHeading, nextHeading,
            current.rangeKm, canvasHeight, headingSwipeAroundCenter)
        _state.update { it.copy(exploring = true, observerLocation = observer,
            location = if (next == viewport) here else GeoPoint(next.latitude, next.longitude), lockedHeading = nextHeading) }
        fetchForViewport()
    }

    fun onTransform(zoom: Float, rotationDeg: Float, previousMidpoint: PlanOffset,
        midpoint: PlanOffset, chartHeightPx: Float) {
        northUpJob?.cancel()
        val current = state.value
        val heading = current.lockedHeading
        if (!current.exploring || heading == null) { onZoom(zoom); return }
        val observer = current.observerLocation ?: return
        val viewport = current.location ?: return
        if (!rotationDeg.isFinite() || chartHeightPx <= 0) return
        val range = DialGeometry.zoomedRange(current.rangeKm, zoom)
        val nextHeading = Heading.normalize(heading - rotationDeg)
        val next = PanGeometry.transformViewport(MapCenter(observer.latitude, observer.longitude),
            MapCenter(viewport.latitude, viewport.longitude), previousMidpoint, midpoint,
            chartHeightPx / current.rangeKm, chartHeightPx / range, heading, nextHeading)
        val point = GeoPoint(next.latitude, next.longitude)
        _state.update { it.copy(location = point, rangeKm = range, lockedHeading = nextHeading) }
        fetchForViewport()
    }

    private fun fetchForViewport() {
        val here = _state.value.location ?: return
        val center = fetchedCenter
        if (center == null ||
            GeoMath.distanceKm(center.latitude, center.longitude, here.latitude, here.longitude) > REFETCH_DISTANCE_KM ||
            DialGeometry.fetchRadiusKm(_state.value.rangeKm) > fetchedRadiusKm
        ) fetch()
    }

    fun retry() = fetch(forceRefresh = true)

    /** 通信エラーの知らせを閉じる。 */
    fun dismissFetchError() = _state.update { it.copy(fetchErrorMessage = null) }

    /** 通信エラーの知らせから取り直す。利用者が求めたので、手動取得モードでも通信する。 */
    fun retryAfterFetchError() {
        dismissFetchError()
        fetch(manual = true)
    }

    /** 左下の更新ボタン(山データを取得)。今の表示範囲のうち、未取得または古い地域を取得する。 */
    fun fetchManually() = fetch(manual = true)

    /** 初回起動時の「山データを自動で取得してよいか」への答え。いいえなら手動取得モードにする。 */
    fun answerNetworkConsent(allow: Boolean) {
        updateSettings { it.copy(networkConsentAsked = true, manualFetch = !allow) }
        if (allow) fetch()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = _state.value.settings
        appSettings.update(transform)
        val after = appSettings.settings.value
        _state.update { it.copy(settings = after) }

        if (after.minElevationM != before.minElevationM) {
            _state.value.observerLocation?.let { here -> _state.update { it.withPeaksAt(here) } }
        }
        // 起動時の範囲を変えたら、試しやすいよう今の表示にもすぐ反映する。
        if (after.initialRangeKm != before.initialRangeKm) {
            _state.update { it.copy(rangeKm = after.initialRangeKm.toDouble()) }
            if (DialGeometry.fetchRadiusKm(after.initialRangeKm.toDouble()) > fetchedRadiusKm) fetch()
        }
        // 手動取得をやめたら、控えていた分をすぐ取得する。
        if (before.manualFetch && !after.manualFetch && _state.value.networkSkipped) fetch()
    }

    fun refreshCacheInfo() {
        viewModelScope.launch {
            val info = cacheManager.info()
            _state.update { it.copy(cacheInfo = info) }
        }
    }

    /** キャッシュを消して、現在地周辺を取り直す。事前ダウンロードした地域は残す。 */
    fun clearCache() {
        fetchJob?.cancel()
        viewModelScope.launch {
            cacheManager.clear(keep = app.savedAreas.tiles())
            peaks = emptyList()
            _state.update { it.copy(mountains = emptyList(), summit = null, cacheInfo = cacheManager.info()) }
            fetch()
        }
    }

    /**
     * 通信せず、保存済みのデータだけで今の周辺を読み直す。取得中の通信や、出ている知らせには触れない。
     */
    private fun reloadFromCache() {
        val here = _state.value.location ?: return
        val radius = DialGeometry.fetchRadiusKm(_state.value.rangeKm)
        viewModelScope.launch {
            val result = repository.mountainsAround(here.latitude, here.longitude, radius, allowNetwork = false)
            peaks = result.mountains.map { it.mountain }
            _state.update { it.withPeaksAt(it.location ?: here).copy(incomplete = result.incomplete) }
        }
    }

    private fun fetch(forceRefresh: Boolean = false, manual: Boolean = false) {
        val here = _state.value.location ?: return
        val radius = DialGeometry.fetchRadiusKm(_state.value.rangeKm)
        val settings = _state.value.settings
        // 初回の問い合わせに答えるまでは、キャッシュだけで表示して通信しない。
        val wantsNetwork = manual || (!settings.manualFetch && settings.networkConsentAsked)
        // 圏外と分かっていれば通信を試さない(失敗を待たず、エラーの知らせも出さない)。
        val connected = _state.value.connected
        val allowNetwork = wantsNetwork && connected
        fetchedCenter = here
        fetchedRadiusKm = radius
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // 手動移動中の連続した取得要求をまとめる。
            if (_state.value.exploring && !manual && !forceRefresh) delay(250)
            val result = repository.mountainsAround(
                here.latitude,
                here.longitude,
                radius,
                forceRefresh = forceRefresh,
                allowNetwork = allowNetwork,
                maxAgeMillis = settings.cacheMaxAgeMillis,
            )
            // Log.w(tag, msg, tr) は UnknownHostException を含むと何も出さないので文字列にして渡す。
            result.error?.let { Log.w(TAG, "山データの取得に失敗\n${it.stackTraceToString()}") }
            peaks = result.mountains.map { it.mountain }
            val skippedOffline = wantsNetwork && !connected && result.networkSkipped
            if (skippedOffline) {
                skippedWhileDisconnected = manual
            } else if (allowNetwork) {
                skippedWhileDisconnected = null
            }
            val hasCache = peaks.isNotEmpty()
            val error = result.error
            _state.update {
                it.withPeaksAt(it.observerLocation ?: here).copy(
                    loading = false,
                    offline = error != null || skippedOffline,
                    incomplete = result.incomplete,
                    networkSkipped = result.networkSkipped,
                    fetchErrorMessage = when {
                        error != null -> errorNotice(error, hasCache)
                        // 自分で取得を押したときだけ、圏外で取得できなかったことを知らせる。
                        skippedOffline && manual -> offlineNotice(hasCache)
                        skippedOffline -> it.fetchErrorMessage
                        else -> null
                    },
                )
            }
        }
    }

    /** [p] から見た山の一覧と、山頂にいるならその山を入れた状態。 */
    private fun DialUiState.withPeaksAt(p: GeoPoint): DialUiState {
        val all = peaks.map { it.seenFrom(p.latitude, p.longitude) }
        val summit = summitAt(all)
        val minElevation = settings.minElevationM
        return copy(
            mountains = all
                .filter { it.mountain.osmId != summit?.mountain?.osmId && it.mountain.meetsMinElevation(minElevation) }
                .sortedWith(displayPriority),
            summit = summit,
        )
    }

    private companion object {
        /** 通信エラーの知らせの文言。端末がつながっていないのか、サーバー側の問題かで案内を変える。 */
        fun errorNotice(error: Throwable, hasCache: Boolean): String {
            if (isOffline(error)) return offlineNotice(hasCache)
            return withCacheNote("山データのサーバーが混み合っているか、応答がありません。しばらくしてから再取得してください。", hasCache)
        }

        fun offlineNotice(hasCache: Boolean): String =
            withCacheNote("インターネットに接続されていません。電波の届く場所で再取得してください。", hasCache)

        private fun withCacheNote(cause: String, hasCache: Boolean): String =
            if (hasCache) "$cause\n\n保存済みのデータで表示しています。" else cause

        /** 端末が通信できない状態で失敗したか。Overpass はエンドポイントごとの失敗を原因と suppressed にまとめるので、すべてを見る。 */
        fun isOffline(error: Throwable): Boolean = when (error) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> true
            is OverpassException -> {
                val causes = listOfNotNull(error.cause) + error.suppressed
                causes.isNotEmpty() && causes.all(::isOffline)
            }
            else -> false
        }

        /** これ以上移動したら取り直す。取得済みの地域ならキャッシュから読むだけで通信しない。 */
        const val REFETCH_DISTANCE_KM = 1.0

        const val TAG = "DialViewModel"
    }
}
