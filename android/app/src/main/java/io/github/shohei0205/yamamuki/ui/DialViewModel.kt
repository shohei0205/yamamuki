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
import io.github.shohei0205.yamamuki.core.HeadingTurn
import io.github.shohei0205.yamamuki.core.InstalledPeakData
import io.github.shohei0205.yamamuki.core.MapCenter
import io.github.shohei0205.yamamuki.core.PanGeometry
import io.github.shohei0205.yamamuki.core.PlanOffset
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.MountainSearch
import io.github.shohei0205.yamamuki.core.TagGroup
import io.github.shohei0205.yamamuki.core.TagGroups
import io.github.shohei0205.yamamuki.core.inTagGroups
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.PeakData
import io.github.shohei0205.yamamuki.core.PeakDataException
import io.github.shohei0205.yamamuki.core.PeakDataOffer
import io.github.shohei0205.yamamuki.core.PeakDataUpdater
import io.github.shohei0205.yamamuki.core.PeakManifest
import io.github.shohei0205.yamamuki.core.meetsMinElevation
import io.github.shohei0205.yamamuki.core.seenFrom
import io.github.shohei0205.yamamuki.core.summitAt
import io.github.shohei0205.yamamuki.data.CacheInfo
import io.github.shohei0205.yamamuki.sensor.connectivityUpdates
import io.github.shohei0205.yamamuki.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /**
     * 検索位置モードで選んだ山。null でなければ、現在地の代わりにこの山頂を距離と方位の基準にする([observer])。
     * 検索位置モードの間は [exploring] も true にする。
     */
    val viewpoint: Mountain? = null,
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
    /** 新しい山データがあり、ダウンロードするかを聞いているときの、その版の manifest(作った日と大きさを出す)。聞いていなければ null。 */
    val peakDataOffer: PeakManifest? = null,
    /** 設定画面に出す、山データの取得の結果(「山データは最新です」など)。 */
    val peakDataNotice: String? = null,
    /** 山データの取得に失敗したときに画面中央で知らせる文言。閉じるまで保つ。 */
    val fetchErrorMessage: String? = null,
    /** 範囲内に一度も取得できていない地域がある。 */
    val incomplete: Boolean = false,
    val settings: Settings = Settings(),
    /** 設定画面に出すキャッシュの状況。読み込むまでは null。 */
    val cacheInfo: CacheInfo? = null,
    /** 山の検索画面の状態。開いていなければ null。 */
    val search: SearchUiState? = null,
    /** 保存している山データにある山のグループ(名前順)。読み込むまでは null。 */
    val peakGroups: List<TagGroup>? = null,
) {
    /** 双眼鏡の位置で、山までの距離と方位の基準。検索位置モードでは選んだ山頂(高さは山頂の標高)、それ以外は現在地。 */
    val observer: GeoPoint?
        get() = viewpoint?.let { GeoPoint(it.latitude, it.longitude, it.elevationM ?: 0.0, it.elevationM) } ?: gpsLocation
}

/** 山の検索画面の状態。 */
data class SearchUiState(
    /** 端末に保存している山を読み込んでいる。 */
    val loading: Boolean = true,
    /** 端末に山データが 1 件も無い(まだ取得していない)。 */
    val noData: Boolean = false,
    /** 入力した文字を含む山。先頭が一致した山と途中に含む山に分け、それぞれ現在地から近い順(現在地が分からなければ標高の高い順)。 */
    val results: MountainSearch.Results = MountainSearch.Results.EMPTY,
    /** 設定の「山のグループ」で絞り込んでいるときの、探す対象のグループ(名前順)。空ならすべての山から探す。 */
    val groups: List<String> = emptyList(),
)

/**
 * 山データの取り直しが要る。保存データの読み込み処理の版がアプリより古く、起動時の作り直しもできなかったとき
 * (gz を残す前の版のアプリで取り込んだときなど)。取得中は出さない。
 */
val DialUiState.peakDataRefreshNeeded: Boolean
    get() = !peakDataUpdating && peakData != null && peakData.readerVersion < PeakData.READER_VERSION

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
    /** 確認した、まだ取り込んでいない山データの版。ダウンロードするかの答えを待っている間だけ持つ。 */
    private var peakDataOffer: PeakDataOffer? = null
    /** ダウンロードすると答えたのに取り込めなかった版。「もう一度取得」では確認を飛ばして、この版を取り直す。 */
    private var failedOffer: PeakDataOffer? = null

    /** 検索画面を開いている間だけ持つ、全件の山の検索の下ごしらえ。 */
    private var searchIndex: MountainSearch? = null
    private var searchQuery = ""
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { connectivityUpdates(application).collect(::onConnectivity) }
        // 事前ダウンロードで現在地の周辺が埋まったり消えたりしたら、表示を読み直す。
        viewModelScope.launch {
            app.cacheChanges.collect {
                reloadFromCache()
                refreshPeakGroups()
            }
        }
        refreshPeakGroups()
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
            // 検索位置モードでは双眼鏡は山頂に置いたままなので、現在地を覚えるだけにする。
            val next = it.copy(gpsLocation = point, location = if (it.exploring) it.location else point)
            if (it.viewpoint != null) next else next.withPeaksAt(point)
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
        // 回している間に端末の向きが戻り先の真後ろをまたいでも、途中で逆回りしないようにする。
        val turn = HeadingTurn(startHeading, compassHeading())
        _state.update { it.copy(returning = true) }
        northUpJob = viewModelScope.launch {
            try {
                val started = System.nanoTime()
                do {
                    val t = ((System.nanoTime() - started) / 500_000_000.0).coerceAtMost(1.0)
                    val fraction = t * t * (3 - 2 * t)
                    val target = state.value.gpsLocation ?: return@launch
                    val heading = turn.headingAt(compassHeading(), t)
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

    /**
     * 検索で選んだ山を、検索位置モードで表示する。双眼鏡の代わりにその山頂を距離と方位の基準にし、画面の中央に置く。
     * 向きは今の向きのまま止める(手動位置モードに入るときと同じ)。[canvasHeight] は dp。
     */
    fun showFromMountain(mountain: Mountain, headingDeg: Double, canvasHeight: Double) {
        northUpJob?.cancel()
        closeSearch()
        val heading = state.value.lockedHeading ?: headingDeg
        val viewport = PanGeometry.centeredViewport(
            MapCenter(mountain.latitude, mountain.longitude), heading, state.value.rangeKm, canvasHeight,
        )
        // 読み込みが終わるまでは、前の場所の山を残さず、選んだ山頂だけを出す。
        peaks = listOf(mountain)
        fetchedCenter = null
        _state.update {
            it.copy(
                viewpoint = mountain, location = GeoPoint(viewport.latitude, viewport.longitude),
                exploring = true, returning = false, lockedHeading = heading,
            ).withPeaksAt(GeoPoint(mountain.latitude, mountain.longitude))
        }
        fetch()
    }

    /**
     * 検索位置モードをやめ、現在地のヘディングアップに戻す。
     * 画面が急に切り替わらないよう、手動位置モードから戻るときと同じく、約0.5秒で地図を現在地へ滑らせて端末の向きに回す。
     * 現在地がまだ分からないときは、動かす先が無いのでそのままヘディングアップにする。
     */
    fun leaveViewpoint(compassHeading: () -> Double) {
        northUpJob?.cancel()
        val gps = state.value.gpsLocation
        if (gps == null) {
            peaks = emptyList()
            fetchedCenter = null
            _state.update {
                it.copy(viewpoint = null, location = null, exploring = false, returning = false,
                    lockedHeading = null, mountains = emptyList(), summit = null)
            }
            return
        }
        // 双眼鏡を現在地に戻してから、手動位置モードと同じ動きで現在地へ戻す。山は戻り終えたときに読み直す。
        _state.update { it.copy(viewpoint = null, exploring = true, returning = false).withPeaksAt(gps) }
        resetCenter(compassHeading)
    }

    /** 山の検索画面を開き、端末に保存している山を読み込む。 */
    fun openSearch() {
        searchJob?.cancel()
        searchQuery = ""
        _state.update { it.copy(search = SearchUiState()) }
        searchJob = viewModelScope.launch {
            val all = app.mountainCache.allMountains()
            // 方位盤と同じく、設定の「山のグループ」で選んだグループの山だけから探す。
            val groups = TagGroups.effective(_state.value.settings.peakGroups, TagGroups.of(all.map { it.tags }))
            val targets = all.filter { it.inTagGroups(groups) }
            val index = withContext(Dispatchers.Default) { MountainSearch(targets) }
            searchIndex = index
            _state.update { s ->
                s.search?.let { s.copy(search = it.copy(loading = false, noData = all.isEmpty(), groups = groups)) } ?: s
            }
            runSearch()
        }
    }

    /** 検索画面の入力が変わった。 */
    fun onSearchQuery(query: String) {
        searchQuery = query
        runSearch()
    }

    /** 検索画面を閉じる。読み込んだ山は持ち続けない。 */
    fun closeSearch() {
        searchJob?.cancel()
        searchIndex = null
        searchQuery = ""
        _state.update { it.copy(search = null) }
    }

    private fun runSearch() {
        val index = searchIndex ?: return
        // 並びは実際の現在地から近い順(検索位置モードでも山頂からではなく現在地から)。
        val here = _state.value.gpsLocation
        val results = index.search(searchQuery, here?.latitude, here?.longitude)
        _state.update { s -> s.search?.let { s.copy(search = it.copy(results = results)) } ?: s }
    }

    /** 双眼鏡が画面内なら双眼鏡、画面外なら画面中央を軸に、約0.5秒で北へ回す。 */
    fun faceNorth(headingDeg: Double, canvasWidth: Double, canvasHeight: Double) =
        turnMap(state.value.lockedHeading ?: headingDeg, { 0.0 }, canvasWidth, canvasHeight)

    /** 手動位置モードのまま、約0.5秒で地図を端末の向きに合わせる。合わせたあとは端末の向きに付いていかない。 */
    fun faceDevice(compassHeading: () -> Double, canvasWidth: Double, canvasHeight: Double) {
        val startHeading = state.value.takeIf { it.exploring }?.lockedHeading ?: return
        turnMap(startHeading, compassHeading, canvasWidth, canvasHeight)
    }

    /**
     * 双眼鏡が画面内なら双眼鏡、画面外なら画面中央を軸に、約0.5秒で地図を [target] の向きへ回す。
     * 回している間も端末は動くので、行き先の向きは毎回読み直す。
     */
    private fun turnMap(startHeading: Double, target: () -> Double, canvasWidth: Double, canvasHeight: Double) {
        if (!canvasHeight.isFinite() || canvasHeight <= DialGeometry.CHART_INSET_DP) return
        northUpJob?.cancel()
        val initial = state.value
        val here = initial.location ?: return
        val observer = initial.observer ?: here
        val aroundCenter = !PanGeometry.isObserverVisible(
            MapCenter(observer.latitude, observer.longitude), MapCenter(here.latitude, here.longitude),
            startHeading, initial.rangeKm, canvasWidth, canvasHeight,
        )
        val turn = HeadingTurn(startHeading, target())
        northUpJob = viewModelScope.launch {
            val started = System.nanoTime()
            do {
                val progress = ((System.nanoTime() - started) / 500_000_000.0).coerceAtMost(1.0)
                val heading = turn.headingAt(target(), progress)
                val next = PanGeometry.rotateViewport(
                    MapCenter(observer.latitude, observer.longitude), MapCenter(here.latitude, here.longitude),
                    startHeading, heading, initial.rangeKm, canvasHeight, aroundCenter,
                )
                _state.update { it.copy(
                    location = if (next.latitude == here.latitude && next.longitude == here.longitude) here
                        else GeoPoint(next.latitude, next.longitude),
                    exploring = true,
                    lockedHeading = heading,
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
        val observer = current.observer ?: return
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

    /**
     * 通信エラーの知らせから取り直す。利用者が求めたので通信する。
     * ダウンロードに失敗したあとなら、もう答えをもらっているので、大きさを聞き直さずに同じ版を取り直す。
     */
    fun retryAfterFetchError() {
        dismissFetchError()
        failedOffer?.let(::installPeakData) ?: updatePeakData()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = _state.value.settings
        appSettings.update(transform)
        val after = appSettings.settings.value
        _state.update { it.copy(settings = after) }

        if (after.minElevationM != before.minElevationM || after.peakGroups != before.peakGroups) {
            _state.value.observer?.let { here -> _state.update { it.withPeaksAt(here) } }
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
        refreshPeakGroups()
    }

    /** 保存している山データから山のグループの一覧を作り直し、方位盤の絞り込みにも反映する。 */
    private fun refreshPeakGroups() {
        viewModelScope.launch {
            val groups = app.mountainCache.peakGroups()
            _state.update { s -> s.copy(peakGroups = groups).let { n -> n.observer?.let { n.withPeaksAt(it) } ?: n } }
        }
    }

    /**
     * 保存している山データを消して、現在地周辺を読み直す(通信はしない)。事前ダウンロードした地域は残す。
     * 全国の山データも消えるので、取り込み済みの記録も消し、設定画面から取り直せるようにする。
     */
    fun clearCache() {
        fetchJob?.cancel()
        viewModelScope.launch {
            // 記録を先に消す。消している途中でアプリが終わっても、記録だけが残って「最新です」となり、
            // 取り直せなくなることがないように。
            app.peakDataStore.clear()
            cacheManager.clear(keep = app.savedAreas.tiles())
            peaks = emptyList()
            _state.update {
                it.copy(mountains = emptyList(), summit = null, cacheInfo = cacheManager.info(), peakData = null, peakDataNotice = null)
            }
            refreshPeakGroups()
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
            _state.update { it.withPeaksAt(it.observer ?: here).copy(incomplete = result.incomplete) }
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
            _state.update { it.withPeaksAt(it.observer ?: here).copy(loading = false, incomplete = result.incomplete) }
        }
    }

    /**
     * 全国の山データの最新版を確かめる。初回の問い合わせと設定画面のボタンから呼ぶ。
     * manifest だけを取り、新しい版があれば大きさを見せて、ダウンロードするかを聞く([answerPeakDataOffer])。
     * 圏外と分かっていれば通信を試さずに知らせる。失敗しても、取り込み済みのデータはそのまま残る。
     */
    fun updatePeakData() {
        failedOffer = null
        runPeakDataTask {
            // 画面の状態ではなく保存した記録を使う(キャッシュが作り直されて記録が消えていることがある)。
            // 記録があっても保存している山が 0 件なら、未取得として取り直す(消去の途中でアプリが終わったときなど)。
            val installed = app.peakDataStore.load()?.takeIf { cacheManager.info().mountainCount > 0 }
            when (val check = app.peakDataUpdater.check(installed)) {
                is PeakDataUpdater.Check.UpToDate -> {
                    app.peakDataStore.save(check.installed)
                    _state.update { it.copy(peakData = check.installed, peakDataNotice = app.getString(R.string.peak_data_up_to_date)) }
                }
                is PeakDataUpdater.Check.Available -> {
                    peakDataOffer = check.offer
                    _state.update { it.copy(peakDataOffer = check.offer.manifest) }
                }
            }
        }
    }

    /** 「山データをダウンロードしますか」への答え。はいなら、確認した版のデータ本体を取得して取り込む。 */
    fun answerPeakDataOffer(download: Boolean) {
        val offer = peakDataOffer ?: return
        peakDataOffer = null
        _state.update { it.copy(peakDataOffer = null) }
        if (download) installPeakData(offer)
    }

    /** 確認した版のデータ本体を取得して取り込む。取り込めなければ、「もう一度取得」で取り直せるように残す。 */
    private fun installPeakData(offer: PeakDataOffer) {
        failedOffer = offer
        runPeakDataTask {
            val installed = app.peakDataUpdater.install(offer)
            failedOffer = null
            app.peakDataStore.save(installed)
            _state.update { it.copy(peakData = installed, peakDataNotice = app.getString(R.string.peak_data_updated)) }
            reloadFromCache()
            refreshCacheInfo()
        }
    }

    /** 山データの通信を、取得中の表示を出して行う。圏外なら通信せずに知らせ、失敗したら画面中央で知らせる。 */
    private fun runPeakDataTask(task: suspend () -> Unit) {
        if (_state.value.peakDataUpdating) return
        if (!_state.value.connected) {
            _state.update { it.copy(fetchErrorMessage = offlineNotice(peaks.isNotEmpty()), peakDataNotice = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(peakDataUpdating = true, peakDataNotice = null) }
            try {
                task()
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
        val groups = TagGroups.effective(settings.peakGroups, peakGroups)
        return copy(
            mountains = all.filter {
                it.mountain.osmId != summit?.mountain?.osmId &&
                    it.mountain.meetsMinElevation(minElevation) &&
                    it.mountain.inTagGroups(groups)
            },
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
