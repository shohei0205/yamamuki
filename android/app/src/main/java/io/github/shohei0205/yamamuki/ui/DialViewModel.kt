package io.github.shohei0205.yamamuki.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.YamamukiApp
import io.github.shohei0205.yamamuki.core.DialGeometry
import io.github.shohei0205.yamamuki.core.GeoMath
import io.github.shohei0205.yamamuki.core.Heading
import io.github.shohei0205.yamamuki.core.InstalledPeakData
import io.github.shohei0205.yamamuki.core.MapCenter
import io.github.shohei0205.yamamuki.core.PanGeometry
import io.github.shohei0205.yamamuki.core.PlanOffset
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.PeakData
import io.github.shohei0205.yamamuki.core.PeakDataException
import io.github.shohei0205.yamamuki.core.PeakDataUpdater
import io.github.shohei0205.yamamuki.core.meetsMinElevation
import io.github.shohei0205.yamamuki.core.seenFrom
import io.github.shohei0205.yamamuki.core.summitAt
import io.github.shohei0205.yamamuki.data.CacheInfo
import io.github.shohei0205.yamamuki.sensor.connectivityUpdates
import io.github.shohei0205.yamamuki.settings.Settings
import kotlinx.coroutines.CancellationException
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
    /** 表示範囲の基準の地点。ヘディングアップ中は現在地、手動位置モードでは利用者が動かした地点。 */
    val location: GeoPoint? = null,
    /** 現在地。双眼鏡の位置と、山までの距離の基準。手動位置モードでも GPS に付いていく。 */
    val gpsLocation: GeoPoint? = null,
    val exploring: Boolean = false,
    /** 右下のボタンで現在地へ戻っている途中。[exploring] は戻り終わるまで true のまま。 */
    val returning: Boolean = false,
    val lockedHeading: Double? = null,
    /** 現在地から見た山。最低標高で絞り込んだもの。表示する山の選び方と順は方位盤で決める。現在地が変わるたびに計算し直す。 */
    val mountains: List<NearbyMountain> = emptyList(),
    /**
     * 現在地がほぼ山頂([io.github.shohei0205.yamamuki.core.SUMMIT_RADIUS_KM] 以内)のとき、その山。
     * 最低標高の絞り込みとは関係なく探し、[mountains] からは除く(現在地の位置に別のアイコンで出す)。
     */
    val summit: NearbyMountain? = null,
    /** 現在地から画面上端までの距離。 */
    val rangeKm: Double = DialGeometry.DEFAULT_RANGE_KM,
    /** 保存済みのデータから山を読み込んでいる。 */
    val loading: Boolean = false,
    /** 端末が通信できる状態か。圏外や機内モードでは false になり、山データの取得を試さない。 */
    val connected: Boolean = true,
    /** 取り込み済みの全国の山データ。まだ取得していなければ null。 */
    val peakData: InstalledPeakData? = null,
    /** 全国の山データを取得している。 */
    val peakDataUpdating: Boolean = false,
    /** 設定画面に出す、山データの取得の結果(「山データは最新です」など)。 */
    val peakDataNotice: String? = null,
    /** 山データの取得に失敗したときに画面中央で知らせる文言。閉じるまで保つ。 */
    val fetchErrorMessage: String? = null,
    /** 範囲内に一度も取得できていない地域がある。 */
    val incomplete: Boolean = false,
    val settings: Settings = Settings(),
    /** 設定画面に出すキャッシュの状況。読み込むまでは null。 */
    val cacheInfo: CacheInfo? = null,
)

/** 現在地と表示範囲に応じて保存済みの山データを読み、方位盤に出す山の一覧を保つ。全国の山データの取得も受け持つ。 */
class DialViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YamamukiApp
    private val repository = app.mountainRepository
    private val appSettings = app.settings
    private val cacheManager = app.cacheManager

    private val _state = MutableStateFlow(
        appSettings.settings.value.let {
            DialUiState(settings = it, rangeKm = it.initialRangeKm.toDouble(), peakData = app.peakDataStore.load())
        },
    )
    val state: StateFlow<DialUiState> = _state.asStateFlow()
    private var northUpJob: Job? = null
    /** 現在地へ戻る動きの通し番号。取り消された古い動きが、新しく始めた動きの [DialUiState.returning] を消さないようにする。 */
    private var returnGeneration = 0

    private var peaks: List<Mountain> = emptyList()
    private var fetchedCenter: GeoPoint? = null
    private var fetchedRadiusKm = 0.0
    private var fetchJob: Job? = null

    init {
        viewModelScope.launch { connectivityUpdates(application).collect(::onConnectivity) }
        // 事前ダウンロードで現在地の周辺が埋まったり消えたりしたら、表示を読み直す。
        viewModelScope.launch { app.cacheChanges.collect { reloadFromCache() } }
        rebuildPeakData()
    }

    /**
     * アプリの更新で山データの読み込み処理が新しくなっていたら、残した gz から通信せずに保存データを作り直す。
     * 作り直せなければ何もしない(次に更新を確かめたときに取り直す)。
     */
    private fun rebuildPeakData() {
        if (app.peakDataStore.load().let { it == null || it.readerVersion >= PeakData.READER_VERSION }) return
        viewModelScope.launch {
            _state.update { it.copy(peakDataUpdating = true) }
            try {
                val rebuilt = app.peakDataUpdater.rebuild(app.peakDataStore.load()) ?: return@launch
                app.peakDataStore.save(rebuilt)
                _state.update { it.copy(peakData = rebuilt) }
                reloadFromCache()
                refreshCacheInfo()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "山データの作り直しに失敗\n${e.stackTraceToString()}")
            } finally {
                _state.update { it.copy(peakDataUpdating = false) }
            }
        }
    }

    private fun onConnectivity(connected: Boolean) {
        if (connected == _state.value.connected) return
        _state.update { it.copy(connected = connected) }
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
            // 手動位置モードでは表示範囲を動かさず、双眼鏡と山までの距離だけを現在地に合わせる。
            it.copy(gpsLocation = point, location = if (it.exploring) it.location else point).withPeaksAt(point)
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

    /** 一本指のドラッグ。モードは右下のボタンだけで切り替えるので、ヘディングアップ中は何もしない。 */
    fun onPan(dxPx: Float, dyPx: Float, chartHeightPx: Float, headingDeg: Double) {
        if (!state.value.exploring) return
        northUpJob?.cancel()
        val here = state.value.location ?: return
        val next = PanGeometry.drag(MapCenter(here.latitude, here.longitude), dxPx.toDouble(), dyPx.toDouble(),
            chartHeightPx / state.value.rangeKm, state.value.lockedHeading ?: headingDeg)
        if (next.latitude == here.latitude && next.longitude == here.longitude) return
        val point = GeoPoint(next.latitude, next.longitude)
        _state.update { it.copy(location = point, exploring = true, lockedHeading = it.lockedHeading ?: headingDeg) }
        fetchForViewport()
    }

    /** 右下のボタンで手動位置モードにする。地図の向きは今の方位のまま止め、双眼鏡が上を向いたまま切り替わるようにする。 */
    fun enterManual(headingDeg: Double) {
        northUpJob?.cancel()
        if (state.value.location == null) return
        _state.update { it.copy(exploring = true, lockedHeading = headingDeg) }
    }

    fun resetCenter(compassHeading: () -> Double) {
        northUpJob?.cancel()
        val initial = state.value
        val startLocation = initial.location ?: return
        val startObserver = initial.gpsLocation ?: return
        val startHeading = initial.lockedHeading ?: compassHeading()
        // 双眼鏡は現在地に付いているので、表示範囲と方角だけを戻す。途中で止めても双眼鏡は現在地に残る。
        val initialOffset = PanGeometry.observerOffset(MapCenter(startObserver.latitude, startObserver.longitude),
            MapCenter(startLocation.latitude, startLocation.longitude), startHeading)
        val generation = ++returnGeneration
        _state.update { it.copy(returning = true) }
        northUpJob = viewModelScope.launch {
            try {
                val started = System.nanoTime()
                do {
                    val t = ((System.nanoTime() - started) / 500_000_000.0).coerceAtMost(1.0)
                    val fraction = t * t * (3 - 2 * t)
                    val target = state.value.gpsLocation ?: return@launch
                    val heading = PanGeometry.returnHeading(startHeading, compassHeading(), fraction)
                    val viewport = PanGeometry.returnViewport(MapCenter(target.latitude, target.longitude), initialOffset, heading, fraction)
                    _state.update { it.copy(
                        location = target.copy(latitude = viewport.latitude, longitude = viewport.longitude),
                        exploring = t < 1.0, lockedHeading = if (t < 1.0) heading else null,
                    ) }
                    if (t >= 1.0) break
                    delay(16)
                } while (true)
            } finally {
                if (generation == returnGeneration) _state.update { it.copy(returning = false) }
            }
            fetch()
        }
    }

    /** 双眼鏡が画面内なら双眼鏡、画面外なら画面中央を軸に、約0.5秒で北へ回す。 */
    fun faceNorth(headingDeg: Double, canvasWidth: Double, canvasHeight: Double) {
        if (!canvasHeight.isFinite() || canvasHeight <= DialGeometry.CHART_INSET_DP) return
        northUpJob?.cancel()
        val initial = state.value
        val here = initial.location ?: return
        val observer = initial.gpsLocation ?: here
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
                    lockedHeading = PanGeometry.northUpHeading(startHeading, progress),
                ) }
                if (progress >= 1.0) break
                delay(16)
            } while (true)
            fetchForViewport()
        }
    }

    fun onTransform(zoom: Float, rotationDeg: Float, previousMidpoint: PlanOffset,
        midpoint: PlanOffset, chartHeightPx: Float) {
        northUpJob?.cancel()
        val current = state.value
        val heading = current.lockedHeading
        if (!current.exploring || heading == null) { onZoom(zoom); return }
        val observer = current.gpsLocation ?: return
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

    /** 通信エラーの知らせを閉じる。 */
    fun dismissFetchError() = _state.update { it.copy(fetchErrorMessage = null) }

    /** 通信エラーの知らせから取り直す。利用者が求めたので通信する。 */
    fun retryAfterFetchError() {
        dismissFetchError()
        updatePeakData()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = _state.value.settings
        appSettings.update(transform)
        val after = appSettings.settings.value
        _state.update { it.copy(settings = after) }

        if (after.minElevationM != before.minElevationM) {
            _state.value.gpsLocation?.let { here -> _state.update { it.withPeaksAt(here) } }
        }
        // 起動時の範囲を変えたら、試しやすいよう今の表示にもすぐ反映する。
        if (after.initialRangeKm != before.initialRangeKm) {
            _state.update { it.copy(rangeKm = after.initialRangeKm.toDouble()) }
            if (DialGeometry.fetchRadiusKm(after.initialRangeKm.toDouble()) > fetchedRadiusKm) fetch()
        }
    }

    fun refreshCacheInfo() {
        viewModelScope.launch {
            val info = cacheManager.info()
            // DB を開いたときにキャッシュが作り直されていたら、取り込み済みの記録も消えているので読み直す。
            _state.update { it.copy(cacheInfo = info, peakData = app.peakDataStore.load()) }
        }
    }

    /**
     * 保存している山データを消して、現在地周辺を読み直す(通信はしない)。事前ダウンロードした地域は残す。
     * 全国の山データも消えるので、取り込み済みの記録も消し、設定画面から取り直せるようにする。
     */
    fun clearCache() {
        fetchJob?.cancel()
        viewModelScope.launch {
            cacheManager.clear(keep = app.savedAreas.tiles())
            app.peakDataStore.clear()
            peaks = emptyList()
            _state.update {
                it.copy(mountains = emptyList(), summit = null, cacheInfo = cacheManager.info(), peakData = null, peakDataNotice = null)
            }
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
            val result = repository.mountainsAround(
                here.latitude, here.longitude, radius, allowNetwork = false,
                ignoreMissing = PeakData.ignoresMissing(here.latitude, here.longitude),
            )
            peaks = result.mountains.map { it.mountain }
            _state.update { it.withPeaksAt(it.gpsLocation ?: here).copy(incomplete = result.incomplete) }
        }
    }

    /**
     * 今の表示範囲の山を、保存済みのデータから読み込む。方位盤からは通信しない
     * (山データは初回の問い合わせか設定画面で、yamamuki-data から全国分をまとめて取得する)。
     */
    private fun fetch() {
        val here = _state.value.location ?: return
        val radius = DialGeometry.fetchRadiusKm(_state.value.rangeKm)
        fetchedCenter = here
        fetchedRadiusKm = radius
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // 手動移動中の連続した読み込みをまとめる。
            if (_state.value.exploring) delay(250)
            val result = repository.mountainsAround(
                here.latitude, here.longitude, radius, allowNetwork = false,
                ignoreMissing = PeakData.ignoresMissing(here.latitude, here.longitude),
            )
            peaks = result.mountains.map { it.mountain }
            _state.update { it.withPeaksAt(it.gpsLocation ?: here).copy(loading = false, incomplete = result.incomplete) }
        }
    }

    /**
     * 全国の山データの最新版を確かめ、新しければ取得して取り込む。初回の問い合わせと設定画面のボタンから呼ぶ。
     * 圏外と分かっていれば通信を試さずに知らせる。失敗しても、取り込み済みのデータはそのまま残る。
     */
    fun updatePeakData() {
        if (_state.value.peakDataUpdating) return
        if (!_state.value.connected) {
            _state.update { it.copy(fetchErrorMessage = offlineNotice(peaks.isNotEmpty()), peakDataNotice = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(peakDataUpdating = true, peakDataNotice = null) }
            try {
                // 画面の状態ではなく保存した記録を使う(キャッシュが作り直されて記録が消えていることがある)。
                val result = app.peakDataUpdater.update(app.peakDataStore.load())
                app.peakDataStore.save(result.installed)
                val updated = result is PeakDataUpdater.Result.Updated
                _state.update {
                    it.copy(peakData = result.installed, peakDataNotice = app.getString(if (updated) R.string.peak_data_updated else R.string.peak_data_up_to_date))
                }
                if (updated) {
                    reloadFromCache()
                    refreshCacheInfo()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Log.w(tag, msg, tr) は UnknownHostException を含むと何も出さないので文字列にして渡す。
                Log.w(TAG, "山データの取得に失敗\n${e.stackTraceToString()}")
                _state.update { it.copy(fetchErrorMessage = errorNotice(e, peaks.isNotEmpty())) }
            } finally {
                _state.update { it.copy(peakDataUpdating = false) }
            }
        }
    }

    /** 初回起動時の「山データを取得しますか」への答え。いいえなら、あとで設定画面から取得できる。 */
    fun answerPeakDataPrompt(allow: Boolean) {
        updateSettings { it.copy(peakDataAsked = true) }
        if (allow) updatePeakData()
    }

    /** [p] から見た山の一覧と、山頂にいるならその山を入れた状態。 */
    private fun DialUiState.withPeaksAt(p: GeoPoint): DialUiState {
        val all = peaks.map { it.seenFrom(p.latitude, p.longitude) }
        val summit = summitAt(all)
        val minElevation = settings.minElevationM
        return copy(
            mountains = all
                .filter { it.mountain.osmId != summit?.mountain?.osmId && it.mountain.meetsMinElevation(minElevation) },
            summit = summit,
        )
    }

    /** 通信エラーの知らせの文言。端末がつながっていないのか、サーバー側の問題かで案内を変える。 */
    private fun errorNotice(error: Throwable, hasCache: Boolean): String {
        if (isOffline(error)) return offlineNotice(hasCache)
        if (error is PeakDataException) {
            return withCacheNote(app.getString(R.string.peak_data_error_invalid, error.message.orEmpty()), hasCache)
        }
        return withCacheNote(app.getString(R.string.peak_data_error_no_response), hasCache)
    }

    private fun offlineNotice(hasCache: Boolean): String =
        withCacheNote(app.getString(R.string.peak_data_error_offline), hasCache)

    private fun withCacheNote(cause: String, hasCache: Boolean): String =
        if (hasCache) app.getString(R.string.peak_data_error_with_cache, cause) else cause

    private companion object {
        /** 端末が通信できない状態で失敗したか。HTTP クライアントが包んだ例外も、原因をたどって見る。 */
        fun isOffline(error: Throwable): Boolean = when (error) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> true
            else -> error.cause?.let(::isOffline) ?: false
        }

        /** これ以上移動したら、保存済みのデータを読み直す。 */
        const val REFETCH_DISTANCE_KM = 1.0

        const val TAG = "DialViewModel"
    }
}
