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

/// 現在地と表示範囲に応じて保存済みの山データを読み、方位盤に出す山の一覧を保つ。全国の山データの取得も受け持つ
/// (Android 版の DialViewModel に相当)。
final class DialModel: ObservableObject {
    /// 表示範囲の基準の地点。ヘディングアップ中は現在地、手動位置モードでは利用者が動かした地点。
    @Published private(set) var location: GeoPoint?
    /// 現在地。双眼鏡の位置と、山までの距離の基準。手動位置モードでも GPS に付いていく。
    @Published private(set) var gpsLocation: GeoPoint?
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

    var hasLocationPermission: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
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
    private let logger = Logger(subsystem: "io.github.shohei0205.yamamuki", category: "DialModel")

    /// これ以上移動したら、保存済みのデータを読み直す。
    private static let refetchDistanceKm = 1.0

    /// 開発版の山データ(peaks-dev/)を読むか。Debug のビルドだけ project.yml で PEAK_DATA_DEV を付ける。
    #if PEAK_DATA_DEV
    private static let peakDataDev = true
    #else
    private static let peakDataDev = false
    #endif

    init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("mountains", isDirectory: true)
        cache = FileMountainCache(directory: directory)
        let userAgent = "yamamuki-ios/0.1 (+https://github.com/shohei0205/yamamuki)"
        repository = MountainRepository(remote: OverpassClient(userAgent: userAgent), cache: cache)
        peakDataUpdater = PeakDataUpdater(
            source: HTTPPeakDataSource(manifestUrl: PeakData.manifestUrl(dev: Self.peakDataDev), userAgent: userAgent),
            cache: cache
        )
        peakData = peakDataStore.load()
        areaDownload = AreaDownloadModel(repository: repository, cache: cache)
        let saved = SettingsStore().load()
        settings = saved
        rangeKm = Double(saved.initialRangeKm)
        authorization = locationService.authorization

        locationService.onLocation = { [weak self] in self?.onLocation($0) }
        locationService.onHeading = { [weak self] in self?.heading = $0 }
        locationService.onHeadingAccuracyLow = { [weak self] in self?.headingAccuracyLow = $0 }
        networkMonitor.onChange = { [weak self] in self?.onConnectivity($0) }
        // 事前ダウンロードで現在地の周辺が埋まったり消えたりしたら、表示を読み直す。
        areaDownload.onCacheChanged = { [weak self] in self?.reloadFromCache() }
        locationService.onAuthorizationChange = { [weak self] status in
            guard let self else { return }
            authorization = status
            if hasLocationPermission { locationService.start() }
        }
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
        updatePeaks(at: point)
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
        guard let viewport = location, canvasHeight.isFinite, canvasHeight > DialGeometry.chartInset else { return }
        northUpTask?.cancel()
        let observer = gpsLocation ?? viewport
        let startHeading = displayHeading
        let aroundCenter = !PanGeometry.isObserverVisible(MapCenter(observer.latitude, observer.longitude),
            viewport: MapCenter(viewport.latitude, viewport.longitude), heading: startHeading,
            rangeKm: rangeKm, canvasWidth: canvasWidth, canvasHeight: canvasHeight)
        let range = rangeKm
        beginExploring()
        northUpTask = Task { @MainActor [weak self] in
            let started = ProcessInfo.processInfo.systemUptime
            while !Task.isCancelled {
                let progress = min(1, (ProcessInfo.processInfo.systemUptime - started) / 0.5)
                let next = PanGeometry.northUpViewport(MapCenter(observer.latitude, observer.longitude),
                    viewport: MapCenter(viewport.latitude, viewport.longitude), heading: startHeading,
                    rangeKm: range, canvasHeight: canvasHeight, progress: progress, aroundCenter: aroundCenter)
                self?.location = next == MapCenter(viewport.latitude, viewport.longitude) ? viewport
                    : GeoPoint(latitude: next.latitude, longitude: next.longitude, mslAltitudeM: nil)
                self?.lockedHeading = PanGeometry.northUpHeading(startHeading, progress: progress)
                if progress >= 1 { break }
                do { try await Task.sleep(nanoseconds: 16_000_000) } catch { return }
            }
            if !Task.isCancelled { self?.fetchForViewport() }
        }
    }

    func onTransform(zoom: Double, rotation: Double, previous: PlanOffset, midpoint: PlanOffset, chartHeight: Double) {
        northUpTask?.cancel()
        guard exploring, let oldHeading = lockedHeading else { onZoom(zoom); return }
        guard let observer = gpsLocation, let viewport = location,
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
            while !Task.isCancelled {
                guard let self, let target = gpsLocation else { return }
                let t = min(1, (ProcessInfo.processInfo.systemUptime - started) / 0.5)
                let fraction = t * t * (3 - 2 * t)
                let nextHeading = PanGeometry.returnHeading(startHeading, target: heading ?? startHeading, fraction: fraction)
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
    func retryAfterFetchError() {
        fetchErrorMessage = nil
        updatePeakData()
    }

    func updateSettings(_ transform: (inout Settings) -> Void) {
        let before = settings
        var after = settings
        transform(&after)
        settings = after
        settingsStore.save(after)

        if after.minElevationM != before.minElevationM, let here = gpsLocation {
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
    }

    /// 保存している山データを消して、現在地周辺を読み直す(通信はしない)。事前ダウンロードした地域は残す。
    /// 全国の山データも消えるので、取り込み済みの記録も消し、設定画面から取り直せるようにする。
    func clearCache() {
        fetchTask?.cancel()
        let keep = areaDownload.savedTiles
        Task { @MainActor in
            await cache.clear(keeping: keep)
            peakDataStore.clear()
            peakData = nil
            peakDataNotice = nil
            peaks = []
            mountains = []
            summit = nil
            cacheInfo = await cache.info()
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
                    latitude: here.latitude, longitude: here.longitude, radiusKm: radius, allowNetwork: false
                )
                peaks = result.mountains.map(\.mountain)
                updatePeaks(at: gpsLocation ?? here)
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
                    latitude: here.latitude, longitude: here.longitude, radiusKm: radius, allowNetwork: false
                )
                // 新しい読み込みに置き換わっていたら、古い結果で上書きしない。
                guard !Task.isCancelled else { return }
                peaks = result.mountains.map(\.mountain)
                updatePeaks(at: gpsLocation ?? here)
                incomplete = result.incomplete
                loading = false
            } catch {
                guard !Task.isCancelled else { return }
                logger.error("山データの読み込みに失敗: \(String(describing: error), privacy: .public)")
                loading = false
            }
        }
    }

    /// 全国の山データの最新版を確かめ、新しければ取得して取り込む。初回の問い合わせと設定画面のボタンから呼ぶ。
    /// 圏外と分かっていれば通信を試さずに知らせる。失敗しても、取り込み済みのデータはそのまま残る。
    func updatePeakData() {
        guard !peakDataUpdating else { return }
        guard isConnected else {
            fetchErrorMessage = Self.offlineNotice(hasCache: !peaks.isEmpty)
            peakDataNotice = nil
            return
        }
        peakDataUpdating = true
        peakDataNotice = nil
        let installed = peakData
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { peakDataUpdating = false }
            do {
                let result = try await peakDataUpdater.update(installed: installed)
                peakDataStore.save(result.installed)
                peakData = result.installed
                if case .updated = result {
                    peakDataNotice = "山データを取得しました"
                    reloadFromCache()
                    refreshCacheInfo()
                } else {
                    peakDataNotice = "山データは最新です"
                }
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
        let cause = (error as? PeakDataError).map { "山データを取り込めませんでした。\n\($0.message)" }
            ?? "山データのサーバーから応答がありません。しばらくしてから再取得してください。"
        return hasCache ? cause + "\n\n保存済みのデータで表示しています。" : cause
    }

    private static func offlineNotice(hasCache: Bool) -> String {
        let cause = "インターネットに接続されていません。電波の届く場所で再取得してください。"
        return hasCache ? cause + "\n\n保存済みのデータで表示しています。" : cause
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
        mountains = all
            .filter { $0.mountain.osmId != top?.mountain.osmId && $0.mountain.meetsMinElevation(minElevation) }
        summit = top
    }
}
