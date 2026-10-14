import Combine
import CoreLocation
import Foundation
import os
import YamamukiCore

struct GeoPoint: Equatable {
    let latitude: Double
    let longitude: Double
    /// 標高(海抜)。求められないときは nil。
    let mslAltitudeM: Double?
}

/// 山の検索画面の状態。
struct SearchState {
    /// 端末に保存している山を読み込んでいる。
    var loading = true
    /// 端末に山データが 1 件も無い(まだ取得していない)。
    var noData = false
    /// 入力した文字を含む山。先頭が一致した山と途中に含む山に分け、それぞれ現在地から近い順(現在地が分からなければ標高の高い順)。
    var results = MountainSearch.Results.empty
    /// 設定の「フィルター」で絞り込んでいるときの、探す対象のグループ(名前順)。空ならすべての山から探す。
    var groups: [String] = []
}

/// 現在地と表示範囲に応じて保存済みの山データを読み、方位盤に出す山の一覧を保つ。全国の山データの取得も受け持つ
/// (Android 版の DialViewModel に相当)。
final class DialModel: ObservableObject {
    /// 表示範囲の基準の地点。ヘディングアップ中は現在地、手動位置モードでは利用者が動かした地点。
    @Published private(set) var location: GeoPoint?
    /// 現在地。双眼鏡の位置と、山までの距離の基準。手動位置モードでも GPS に付いていく。
    @Published private(set) var gpsLocation: GeoPoint?
    /// 検索位置モードで選んだ山。nil でなければ、現在地の代わりにこの山頂を距離と方位の基準にする([observer])。
    /// 検索位置モードの間は [exploring] も true にする。
    @Published private(set) var viewpoint: Mountain?
    /// 双眼鏡の位置で、山までの距離と方位の基準。検索位置モードでは選んだ山頂(高さは山頂の標高)、それ以外は現在地。
    var observer: GeoPoint? {
        viewpoint.map { GeoPoint(latitude: $0.latitude, longitude: $0.longitude, mslAltitudeM: $0.elevationM) } ?? gpsLocation
    }
    @Published private(set) var exploring = false
    /// 右下のボタンで現在地へ戻っている途中。[exploring] は戻り終わるまで true のまま。
    @Published private(set) var returning = false
    @Published private(set) var lockedHeading: Double?
    var displayHeading: Double { lockedHeading ?? heading ?? 0 }

    /// 現在地から見た山。最低標高で絞り込んだもの。表示する山の選び方と順は方位盤で決める。現在地が変わるたびに計算し直す。
    @Published private(set) var mountains: [NearbyMountain] = []
    /// 現在地がほぼ山頂([summitRadiusKm] 以内)のとき、その山。
    /// 最低標高の絞り込みとは関係なく探し、[mountains] からは除く(現在地の位置に別のアイコンで出す)。
    @Published private(set) var summit: NearbyMountain?
    /// 端末を向けている方位(真北基準)。センサーの値が届くまでは nil。
    @Published private(set) var heading: Double?
    /// 方位センサーの精度が低く、方位がずれているかもしれない。
    @Published private(set) var headingAccuracyLow = false
    /// 現在地から画面上端までの距離。
    @Published private(set) var rangeKm: Double
    /// 保存済みのデータから山を読み込んでいる。
    @Published private(set) var loading = false
    /// 端末が通信できる状態か。圏外や機内モードでは false になり、山データの取得を試さない。
    @Published private(set) var isConnected = true
    /// 取り込み済みの全国の山データ。まだ取得していなければ nil。
    @Published private(set) var peakData: InstalledPeakData?
    /// 全国の山データを取得している。
    @Published private(set) var peakDataUpdating = false
    /// 新しい山データがあり、ダウンロードするかを聞いているときの、確認した版。聞いていなければ nil。
    @Published private(set) var peakDataOffer: PeakDataOffer?
    /// ダウンロードすると答えたのに取り込めなかった版。「もう一度取得」では確認を飛ばして、この版を取り直す。
    private var failedOffer: PeakDataOffer?
    /// 設定画面に出す、山データの取得の結果(「山データは最新です」など)。
    @Published private(set) var peakDataNotice: String?
    /// 山データの取得に失敗したときに画面中央で知らせる文言。閉じるまで保つ。
    @Published private(set) var fetchErrorMessage: String?
    /// 範囲内に一度も取得できていない地域がある。
    @Published private(set) var incomplete = false
    @Published private(set) var settings: Settings
    /// 設定画面に出すキャッシュの状況。読み込むまでは nil。
    @Published private(set) var cacheInfo: CacheInfo?
    @Published private(set) var authorization: CLAuthorizationStatus = .notDetermined
    /// 山の検索画面の状態。開いていなければ nil。
    @Published private(set) var search: SearchState?
    /// 保存している山データにある山のグループ(名前順)。読み込むまでは nil。
    @Published private(set) var peakGroups: [TagGroup]?

    var hasLocationPermission: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    /// 山データの取り直しが要る。保存データの読み込み処理の版がアプリより古く、起動時の作り直しもできなかったとき
    /// (gz を残す前の版のアプリで取り込んだときなど)。取得中は出さない。
    var peakDataRefreshNeeded: Bool {
        guard let peakData, !peakDataUpdating else { return false }
        return peakData.readerVersion < PeakData.readerVersion
    }

    private let cache: FileMountainCache
    private let repository: MountainRepository
    /// yamamuki-data が配る全国の山データを取得して、キャッシュに取り込む。
    private let peakDataUpdater: PeakDataUpdater
    private let peakDataStore = PeakDataStore()
    /// 山データの事前ダウンロード。画面を閉じても続くよう、ここで持つ。
    let areaDownload: AreaDownloadModel
    private let settingsStore = SettingsStore()
    private let locationService = LocationService()
    private let networkMonitor = NetworkMonitor()
    private var peaks: [Mountain] = []
    /// 現在地へ戻る動きの通し番号。取り消された古い動きが、新しく始めた動きの [returning] を消さないようにする。
    private var returnGeneration = 0
    private var fetchedCenter: GeoPoint?
    private var fetchedRadiusKm = 0.0
    private var fetchTask: Task<Void, Never>?
    private var northUpTask: Task<Void, Never>?
    /// 検索画面を開いている間だけ持つ、全件の山の検索の下ごしらえ。
    private var searchIndex: MountainSearch?
    private var searchQuery = ""
    private var searchTask: Task<Void, Never>?
    private let logger = Logger(subsystem: "io.github.shohei0205.yamamuki", category: "DialModel")

    /// これ以上移動したら、保存済みのデータを読み直す。
    private static let refetchDistanceKm = 1.0

    /// 山データの読み先。Release は参照先ファイルの stable、Debug は既定で参照先ファイルの dev(無ければ stable)。
    /// Debug のビルドで PEAK_DATA_SOURCE を dev にしたときだけ、project.yml で PEAK_DATA_DEV が付き、yamamuki-data の開発版を直接読む。
    #if PEAK_DATA_DEV
    private static let peakDataChannel = PeakDataChannel.dataDev
    #elseif DEBUG
    private static let peakDataChannel = PeakDataChannel.dev
    #else
    private static let peakDataChannel = PeakDataChannel.stable
    #endif

    init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("mountains", isDirectory: true)
        cache = FileMountainCache(directory: directory)
        let userAgent = "yamamuki-ios/0.1 (+https://github.com/shohei0205/yamamuki)"
        repository = MountainRepository(remote: OverpassClient(userAgent: userAgent), cache: cache)
        peakDataUpdater = PeakDataUpdater(
            source: PeakData.source(Self.peakDataChannel, userAgent: userAgent),
            cache: cache,
            archive: peakDataStore.archive
        )
        peakData = peakDataStore.load()
        areaDownload = AreaDownloadModel(repository: repository, cache: cache)
        let saved = SettingsStore().load()
        settings = saved
        rangeKm = Double(saved.initialRangeKm)
        authorization = locationService.authorization
        locationService.apply(saved.sensorPrecision)

        locationService.onLocation = { [weak self] in self?.onLocation($0) }
        locationService.onHeading = { [weak self] in self?.heading = $0 }
        locationService.onHeadingAccuracyLow = { [weak self] in self?.headingAccuracyLow = $0 }
        networkMonitor.onChange = { [weak self] in self?.onConnectivity($0) }
        // 事前ダウンロードで現在地の周辺が埋まったり消えたりしたら、表示を読み直す。
        areaDownload.onCacheChanged = { [weak self] in
            self?.reloadFromCache()
            self?.refreshPeakGroups()
        }
        locationService.onAuthorizationChange = { [weak self] status in
            guard let self else { return }
            authorization = status
            if hasLocationPermission { locationService.start() }
        }
        rebuildPeakData()
        refreshPeakGroups()
    }

    /// 画面が前面に出たとき。初回は「山データを取得しますか」に答えてから位置情報の許可を求める(ダイアログを重ねない)。
    func start() {
        if settings.peakDataAsked && authorization == .notDetermined {
            locationService.requestAuthorization()
        }
        locationService.start()
    }

    func stop() {
        // 北向き・現在地への復帰のアニメーションは止めない。コントロールセンターを引き出すなど
        // 非アクティブになっただけで途中の位置と方角に取り残されないよう、戻ったら最後まで進める。
        locationService.stop()
    }

    func requestLocationPermission() {
        locationService.requestAuthorization()
    }

    private func onLocation(_ loc: CLLocation) {
        // 高さを持たない位置(verticalAccuracy が負)では、標高の表示が消えないよう直前の標高を引き継ぐ。
        let msl = loc.verticalAccuracy >= 0 ? loc.altitude : gpsLocation?.mslAltitudeM
        let point = GeoPoint(latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude, mslAltitudeM: msl)
        gpsLocation = point
        // 手動位置モードでは表示範囲を動かさず、双眼鏡と山までの距離だけを現在地に合わせる。
        // 検索位置モードでは双眼鏡は山頂に置いたままなので、現在地を覚えるだけにする。
        if viewpoint == nil { updatePeaks(at: point) }
        guard !exploring else { return }
        location = point
        if let center = fetchedCenter,
           GeoMath.distanceKm(center.latitude, center.longitude, point.latitude, point.longitude) <= Self.refetchDistanceKm {
            return
        }
        fetch()
    }

    private func onConnectivity(_ connected: Bool) {
        guard connected != isConnected else { return }
        isConnected = connected
    }

    /// ピンチの倍率(前回からの変化分)。
    func onZoom(_ zoom: Double) {
        northUpTask?.cancel()
        rangeKm = DialGeometry.zoomedRange(rangeKm, zoom: zoom)
        if DialGeometry.fetchRadiusKm(rangeKm) > fetchedRadiusKm { fetch() }
    }

    /// 一本指のドラッグ。モードは右下のボタンだけで切り替えるので、ヘディングアップ中は何もしない。
    func onPan(dx: Double, dy: Double, chartHeight: Double) {
        guard exploring else { return }
        northUpTask?.cancel()
        guard let here = location else { return }
        let next = PanGeometry.drag(MapCenter(here.latitude, here.longitude), dx: dx, dy: dy,
            scale: chartHeight / rangeKm, heading: displayHeading)
        guard next != MapCenter(here.latitude, here.longitude) else { return }
        beginExploring()
        location = GeoPoint(latitude: next.latitude, longitude: next.longitude, mslAltitudeM: nil)
        fetchForViewport()
    }

    /// 右下のボタンで手動位置モードにする。地図の向きは今の方位のまま止め、双眼鏡が上を向いたまま切り替わるようにする。
    func enterManual() {
        northUpTask?.cancel()
        guard location != nil else { return }
        beginExploring()
    }

    private func beginExploring() {
        lockedHeading = displayHeading
        exploring = true
    }

    /// 双眼鏡が画面内なら双眼鏡、画面外なら画面中央を軸に、約0.5秒で北へ回す。
    func faceNorth(canvasWidth: Double, canvasHeight: Double) {
        turnMap(from: displayHeading, facingDevice: false, canvasWidth: canvasWidth, canvasHeight: canvasHeight)
    }

    /// 手動位置モードのまま、約0.5秒で地図を端末の向きに合わせる。合わせたあとは端末の向きに付いていかない。
    func faceDevice(canvasWidth: Double, canvasHeight: Double) {
        guard exploring, let startHeading = lockedHeading else { return }
        turnMap(from: startHeading, facingDevice: true, canvasWidth: canvasWidth, canvasHeight: canvasHeight)
    }

    /// 双眼鏡が画面内なら双眼鏡、画面外なら画面中央を軸に、約0.5秒で地図を北(facingDevice なら端末の向き)へ回す。
    /// 回している間も端末は動くので、端末の向きは毎回読み直す。
    private func turnMap(from startHeading: Double, facingDevice: Bool, canvasWidth: Double, canvasHeight: Double) {
        guard let viewport = location, canvasHeight.isFinite, canvasHeight > DialGeometry.chartInset else { return }
        northUpTask?.cancel()
        let observer = self.observer ?? viewport
        let aroundCenter = !PanGeometry.isObserverVisible(MapCenter(observer.latitude, observer.longitude),
            viewport: MapCenter(viewport.latitude, viewport.longitude), heading: startHeading,
            rangeKm: rangeKm, canvasWidth: canvasWidth, canvasHeight: canvasHeight)
        let range = rangeKm
        beginExploring()
        northUpTask = Task { @MainActor [weak self] in
            let started = ProcessInfo.processInfo.systemUptime
            var turn = HeadingTurn(start: startHeading, target: facingDevice ? self?.heading ?? startHeading : 0)
            while !Task.isCancelled {
                guard let self else { return }
                let progress = min(1, (ProcessInfo.processInfo.systemUptime - started) / 0.5)
                let nextHeading = turn.heading(target: facingDevice ? heading ?? startHeading : 0, progress: progress)
                let next = PanGeometry.rotateViewport(MapCenter(observer.latitude, observer.longitude),
                    viewport: MapCenter(viewport.latitude, viewport.longitude), heading: startHeading,
                    nextHeading: nextHeading, rangeKm: range, canvasHeight: canvasHeight, aroundCenter: aroundCenter)
                location = next == MapCenter(viewport.latitude, viewport.longitude) ? viewport
                    : GeoPoint(latitude: next.latitude, longitude: next.longitude, mslAltitudeM: nil)
                lockedHeading = nextHeading
                if progress >= 1 { break }
                do { try await Task.sleep(nanoseconds: 16_000_000) } catch { return }
            }
            if !Task.isCancelled { self?.fetchForViewport() }
        }
    }

    func onTransform(zoom: Double, rotation: Double, previous: PlanOffset, midpoint: PlanOffset, chartHeight: Double) {
        northUpTask?.cancel()
        guard exploring, let oldHeading = lockedHeading else { onZoom(zoom); return }
        guard let observer, let viewport = location,
              rotation.isFinite, chartHeight.isFinite, chartHeight > 0 else { return }
        let range = DialGeometry.zoomedRange(rangeKm, zoom: zoom)
        let nextHeading = Heading.normalize(oldHeading - rotation)
        let next = PanGeometry.transformViewport(MapCenter(observer.latitude, observer.longitude),
            viewport: MapCenter(viewport.latitude, viewport.longitude), previous: previous, midpoint: midpoint,
            oldScale: chartHeight / rangeKm, newScale: chartHeight / range,
            oldHeading: oldHeading, newHeading: nextHeading)
        location = GeoPoint(latitude: next.latitude, longitude: next.longitude, mslAltitudeM: nil)
        rangeKm = range
        lockedHeading = nextHeading
        fetchForViewport()
    }

    func resetCenter() {
        northUpTask?.cancel()
        guard let startLocation = location, let startObserver = gpsLocation else { return }
        let startHeading = displayHeading
        // 双眼鏡は現在地に付いているので、表示範囲と方角だけを戻す。途中で止めても双眼鏡は現在地に残る。
        let initialOffset = PanGeometry.observerOffset(MapCenter(startObserver.latitude, startObserver.longitude),
            viewport: MapCenter(startLocation.latitude, startLocation.longitude), heading: startHeading)
        returnGeneration += 1
        let generation = returnGeneration
        returning = true
        northUpTask = Task { @MainActor [weak self] in
            defer { if self?.returnGeneration == generation { self?.returning = false } }
            let started = ProcessInfo.processInfo.systemUptime
            // 回している間に端末の向きが戻り先の真後ろをまたいでも、途中で逆回りしないようにする。
            var turn = HeadingTurn(start: startHeading, target: self?.heading ?? startHeading)
            while !Task.isCancelled {
                guard let self, let target = gpsLocation else { return }
                let t = min(1, (ProcessInfo.processInfo.systemUptime - started) / 0.5)
                let fraction = t * t * (3 - 2 * t)
                let nextHeading = turn.heading(target: heading ?? startHeading, progress: t)
                let viewport = PanGeometry.returnViewport(MapCenter(target.latitude, target.longitude),
                    initialOffset: initialOffset, heading: nextHeading, fraction: fraction)
                location = GeoPoint(latitude: viewport.latitude, longitude: viewport.longitude, mslAltitudeM: target.mslAltitudeM)
                lockedHeading = t < 1 ? nextHeading : nil
                exploring = t < 1
                if t >= 1 { break }
                do { try await Task.sleep(nanoseconds: 16_000_000) } catch { return }
            }
            if !Task.isCancelled { self?.fetch() }
        }
    }

    /// 検索で選んだ山を、検索位置モードで表示する。双眼鏡の代わりにその山頂を距離と方位の基準にし、画面の中央に置く。
    /// 向きは今の向きのまま止める(手動位置モードに入るときと同じ)。canvasHeight は pt。
    func showFromMountain(_ mountain: Mountain, canvasHeight: Double) {
        northUpTask?.cancel()
        closeSearch()
        let heading = displayHeading
        let viewport = PanGeometry.centeredViewport(MapCenter(mountain.latitude, mountain.longitude),
            heading: heading, rangeKm: rangeKm, canvasHeight: canvasHeight)
        viewpoint = mountain
        location = GeoPoint(latitude: viewport.latitude, longitude: viewport.longitude, mslAltitudeM: nil)
        lockedHeading = heading
        exploring = true
        returning = false
        // 読み込みが終わるまでは、前の場所の山を残さず、選んだ山頂だけを出す。
        peaks = [mountain]
        updatePeaks(at: GeoPoint(latitude: mountain.latitude, longitude: mountain.longitude, mslAltitudeM: mountain.elevationM))
        fetchedCenter = nil
        fetch()
    }

    /// 検索位置モードをやめ、現在地のヘディングアップに戻す。
    /// 画面が急に切り替わらないよう、手動位置モードから戻るときと同じく、約0.5秒で地図を現在地へ滑らせて端末の向きに回す。
    /// 現在地がまだ分からないときは、動かす先が無いのでそのままヘディングアップにする。
    func leaveViewpoint() {
        northUpTask?.cancel()
        viewpoint = nil
        returning = false
        guard let gps = gpsLocation else {
            location = nil
            lockedHeading = nil
            exploring = false
            peaks = []
            mountains = []
            summit = nil
            fetchedCenter = nil
            return
        }
        // 双眼鏡を現在地に戻してから、手動位置モードと同じ動きで現在地へ戻す。山は戻り終えたときに読み直す。
        exploring = true
        updatePeaks(at: gps)
        resetCenter()
    }

    /// 山の検索画面を開き、端末に保存している山を読み込む。
    func openSearch() {
        searchTask?.cancel()
        searchQuery = ""
        search = SearchState()
        searchTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let all = await cache.allMountains()
            // 方位盤と同じく、設定の「フィルター」で選んだグループの山だけから探す。
            let groups = TagGroups.effective(selected: settings.peakGroups, groups: TagGroups.of(all.map(\.tags)))
            let targets = all.filter { $0.inTagGroups(groups) }
            let index = await Task.detached { MountainSearch(targets) }.value
            guard !Task.isCancelled, search != nil else { return }
            searchIndex = index
            search?.loading = false
            search?.noData = all.isEmpty
            search?.groups = groups
            runSearch()
        }
    }

    /// 検索画面の入力が変わった。
    func onSearchQuery(_ query: String) {
        searchQuery = query
        runSearch()
    }

    /// 検索画面を閉じる。読み込んだ山は持ち続けない。
    func closeSearch() {
        searchTask?.cancel()
        searchIndex = nil
        searchQuery = ""
        search = nil
    }

    private func runSearch() {
        guard let searchIndex, search != nil else { return }
        // 並びは実際の現在地から近い順(検索位置モードでも山頂からではなく現在地から)。
        search?.results = searchIndex.search(searchQuery, latitude: gpsLocation?.latitude, longitude: gpsLocation?.longitude)
    }

    private func fetchForViewport() {
        guard let here = location else { return }
        if let center = fetchedCenter,
           GeoMath.distanceKm(center.latitude, center.longitude, here.latitude, here.longitude) <= Self.refetchDistanceKm,
           DialGeometry.fetchRadiusKm(rangeKm) <= fetchedRadiusKm { return }
        fetch()
    }

    /// 通信エラーの知らせを閉じる。
    func dismissFetchError() { fetchErrorMessage = nil }

    /// 通信エラーの知らせから、山データを取り直す。
    /// ダウンロードに失敗したあとなら、もう答えをもらっているので、大きさを聞き直さずに同じ版を取り直す。
    func retryAfterFetchError() {
        fetchErrorMessage = nil
        if let failedOffer {
            installPeakData(failedOffer)
        } else {
            updatePeakData()
        }
    }

    func updateSettings(_ transform: (inout Settings) -> Void) {
        let before = settings
        var after = settings
        transform(&after)
        settings = after
        settingsStore.save(after)

        if after.sensorPrecision != before.sensorPrecision {
            locationService.apply(after.sensorPrecision)
        }
        if after.minElevationM != before.minElevationM || after.peakGroups != before.peakGroups, let here = observer {
            updatePeaks(at: here)
        }
        // 起動時の範囲を変えたら、試しやすいよう今の表示にもすぐ反映する。
        if after.initialRangeKm != before.initialRangeKm {
            rangeKm = Double(after.initialRangeKm)
            if DialGeometry.fetchRadiusKm(rangeKm) > fetchedRadiusKm { fetch() }
        }
    }

    func refreshCacheInfo() {
        Task { @MainActor in
            cacheInfo = await cache.info()
        }
        refreshPeakGroups()
    }

    /// 取り込み済みの山データの出典を、端末に残した manifest から読む(通信はしない)。
    func peakDataCredits() async -> PeakDataCredits? {
        let installed = peakDataStore.load()
        let archived = await peakDataStore.archive.read()
        return PeakDataCreditsReader.read(installed: installed, archived: archived)
    }

    /// 保存している山データから山のグループの一覧を作り直し、方位盤の絞り込みにも反映する。
    private func refreshPeakGroups() {
        Task { @MainActor [weak self] in
            guard let self else { return }
            let groups = TagGroups.of(await cache.allMountains().map(\.tags))
            peakGroups = groups
            if let here = observer { updatePeaks(at: here) }
        }
    }

    /// 保存している山データを消して、現在地周辺を読み直す(通信はしない)。事前ダウンロードした地域は残す。
    /// 全国の山データも消えるので、取り込み済みの記録も消し、設定画面から取り直せるようにする。
    func clearCache() {
        fetchTask?.cancel()
        let keep = areaDownload.savedTiles
        Task { @MainActor in
            // 記録を先に消す。消している途中でアプリが終わっても、記録だけが残って「最新です」となり、
            // 取り直せなくなることがないように。
            peakDataStore.clear()
            await cache.clear(keeping: keep)
            peakData = nil
            peakDataNotice = nil
            peaks = []
            mountains = []
            summit = nil
            cacheInfo = await cache.info()
            refreshPeakGroups()
            fetch()
        }
    }

    /// 通信せず、保存済みのデータだけで今の周辺を読み直す(事前ダウンロードで区画を書いた・消したとき)。
    /// 取得中の通信や、出ている知らせには触れない。
    private func reloadFromCache() {
        guard let here = location else { return }
        let radius = DialGeometry.fetchRadiusKm(rangeKm)
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let result = try await repository.mountainsAround(
                    latitude: here.latitude, longitude: here.longitude, radiusKm: radius, allowNetwork: false,
                    ignoreMissing: PeakData.ignoresMissing(latitude: here.latitude, longitude: here.longitude)
                )
                peaks = result.mountains.map(\.mountain)
                updatePeaks(at: observer ?? here)
                incomplete = result.incomplete
            } catch {
                logger.error("山データの読み直しに失敗: \(String(describing: error), privacy: .public)")
            }
        }
    }

    /// 今の表示範囲の山を、保存済みのデータから読み込む。方位盤からは通信しない
    /// (山データは初回の問い合わせか設定画面で、yamamuki-data から全国分をまとめて取得する)。
    private func fetch() {
        guard let here = location else { return }
        let radius = DialGeometry.fetchRadiusKm(rangeKm)
        fetchedCenter = here
        fetchedRadiusKm = radius
        fetchTask?.cancel()
        fetchTask = Task { @MainActor [weak self] in
            guard let self else { return }
            loading = true
            do {
                // 手動移動中の連続した読み込みをまとめる。
                if exploring { try await Task.sleep(nanoseconds: 250_000_000) }
                let result = try await repository.mountainsAround(
                    latitude: here.latitude, longitude: here.longitude, radiusKm: radius, allowNetwork: false,
                    ignoreMissing: PeakData.ignoresMissing(latitude: here.latitude, longitude: here.longitude)
                )
                // 新しい読み込みに置き換わっていたら、古い結果で上書きしない。
                guard !Task.isCancelled else { return }
                peaks = result.mountains.map(\.mountain)
                updatePeaks(at: observer ?? here)
                incomplete = result.incomplete
                loading = false
            } catch {
                guard !Task.isCancelled else { return }
                logger.error("山データの読み込みに失敗: \(String(describing: error), privacy: .public)")
                loading = false
            }
        }
    }

    /// アプリの更新で山データの読み込み処理が新しくなっていたら、残した gz から通信せずに保存データを作り直す。
    /// 作り直せなければ何もしない(次に更新を確かめたときに取り直す)。
    private func rebuildPeakData() {
        guard let installed = peakData, installed.readerVersion < PeakData.readerVersion, !peakDataUpdating else { return }
        peakDataUpdating = true
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { peakDataUpdating = false }
            do {
                guard let rebuilt = try await peakDataUpdater.rebuild(installed: installed) else { return }
                peakDataStore.save(rebuilt)
                peakData = rebuilt
                reloadFromCache()
                refreshCacheInfo()
            } catch {
                logger.warning("山データの作り直しに失敗: \(String(describing: error), privacy: .public)")
            }
        }
    }

    /// 全国の山データの最新版を確かめる。初回の問い合わせと設定画面のボタンから呼ぶ。
    /// manifest だけを取り、新しい版があれば大きさを見せて、ダウンロードするかを聞く(`answerPeakDataOffer`)。
    /// 圏外と分かっていれば通信を試さずに知らせる。失敗しても、取り込み済みのデータはそのまま残る。
    func updatePeakData() {
        failedOffer = nil
        let installed = peakData
        runPeakDataTask { [self] in
            // 記録があっても保存している山が 0 件なら、未取得として取り直す(消去の途中でアプリが終わったときなど)。
            let hasMountains = await cache.info().mountainCount > 0
            switch try await peakDataUpdater.check(installed: hasMountains ? installed : nil) {
            case .upToDate(let latest):
                peakDataStore.save(latest)
                peakData = latest
                peakDataNotice = Strings.text("peak_data_up_to_date")
            case .available(let offer):
                peakDataOffer = offer
            }
        }
    }

    /// 「山データをダウンロードしますか」への答え。はいなら、確認した版のデータ本体を取得して取り込む。
    func answerPeakDataOffer(download: Bool) {
        guard let offer = peakDataOffer else { return }
        peakDataOffer = nil
        if download { installPeakData(offer) }
    }

    /// 確認した版のデータ本体を取得して取り込む。取り込めなければ、「もう一度取得」で取り直せるように残す。
    private func installPeakData(_ offer: PeakDataOffer) {
        failedOffer = offer
        runPeakDataTask { [self] in
            let installed = try await peakDataUpdater.install(offer)
            failedOffer = nil
            peakDataStore.save(installed)
            peakData = installed
            peakDataNotice = Strings.text("peak_data_updated")
            reloadFromCache()
            refreshCacheInfo()
        }
    }

    /// 山データの通信を、取得中の表示を出して行う。圏外なら通信せずに知らせ、失敗したら画面中央で知らせる。
    private func runPeakDataTask(_ task: @escaping @MainActor () async throws -> Void) {
        guard !peakDataUpdating else { return }
        guard isConnected else {
            fetchErrorMessage = Self.offlineNotice(hasCache: !peaks.isEmpty)
            peakDataNotice = nil
            return
        }
        peakDataUpdating = true
        peakDataNotice = nil
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { peakDataUpdating = false }
            do {
                try await task()
            } catch {
                logger.warning("山データの取得に失敗: \(String(describing: error), privacy: .public)")
                fetchErrorMessage = Self.errorNotice(for: error, hasCache: !peaks.isEmpty)
            }
        }
    }

    /// 初回起動時の「山データを取得しますか」への答え。いいえなら、あとで設定画面から取得できる。
    func answerPeakDataPrompt(allow: Bool) {
        updateSettings { $0.peakDataAsked = true }
        if allow { updatePeakData() }
        if authorization == .notDetermined { locationService.requestAuthorization() }
    }

    /// 通信エラーの知らせの文言。端末がつながっていないのか、サーバー側の問題かで案内を変える。
    private static func errorNotice(for error: Error, hasCache: Bool) -> String {
        if isOffline(error) { return offlineNotice(hasCache: hasCache) }
        let cause = (error as? PeakDataError).map { Strings.format("peak_data_error_invalid", $0.message) }
            ?? Strings.text("peak_data_error_no_response")
        return withCacheNote(cause, hasCache: hasCache)
    }

    private static func offlineNotice(hasCache: Bool) -> String {
        withCacheNote(Strings.text("peak_data_error_offline"), hasCache: hasCache)
    }

    private static func withCacheNote(_ cause: String, hasCache: Bool) -> String {
        hasCache ? Strings.format("peak_data_error_with_cache", cause) : cause
    }

    /// 端末が通信できない状態で失敗したか。
    private static func isOffline(_ error: Error) -> Bool {
        guard let e = error as? URLError else { return false }
        return [.notConnectedToInternet, .networkConnectionLost, .dataNotAllowed, .internationalRoamingOff].contains(e.code)
    }

    /// [p] から見た山の一覧と、山頂にいるならその山を入れ直す。
    private func updatePeaks(at p: GeoPoint) {
        let all = peaks.map { $0.seen(fromLatitude: p.latitude, longitude: p.longitude) }
        let top = summitAt(all)
        let minElevation = settings.minElevationM
        let groups = TagGroups.effective(selected: settings.peakGroups, groups: peakGroups)
        mountains = all.filter {
            $0.mountain.osmId != top?.mountain.osmId
                && $0.mountain.meetsMinElevation(minElevation)
                && $0.mountain.inTagGroups(groups)
        }
        summit = top
    }
}
