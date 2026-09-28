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

/// 現在地と表示範囲に応じて山データを取得し、方位盤に出す山の一覧を保つ(Android 版の DialViewModel に相当)。
final class DialModel: ObservableObject {
    @Published private(set) var location: GeoPoint?
    /// 現在地から見た山。最低標高で絞り込み、表示の優先順(標高の高い順)に並べたもの。現在地が変わるたびに計算し直す。
    @Published private(set) var mountains: [NearbyMountain] = []
    /// 現在地がほぼ山頂([summitRadiusKm] 以内)のとき、その山。
    /// 最低標高の絞り込みとは関係なく探し、[mountains] からは除く(現在地の位置に別のアイコンで出す)。
    @Published private(set) var summit: NearbyMountain?
    /// 端末を向けている方位(真北基準)。センサーの値が届くまでは nil。
    @Published private(set) var heading: Double?
    /// 現在地から画面上端までの距離。
    @Published private(set) var rangeKm: Double
    @Published private(set) var loading = false
    /// 通信に失敗し、キャッシュだけで表示している。
    @Published private(set) var offline = false
    /// 端末が通信できる状態か。圏外や機内モードでは false になり、取得を控えて保存済みのデータで表示する。
    @Published private(set) var isConnected = true
    /// 通信に失敗したときに画面中央で知らせる文言。閉じるまで保つ。
    @Published private(set) var fetchErrorMessage: String?
    /// 範囲内に一度も取得できていない地域がある。
    @Published private(set) var incomplete = false
    /// 手動取得モードのため、未取得または古い地域があっても通信しなかった。
    @Published private(set) var networkSkipped = false
    @Published private(set) var settings: Settings
    /// 設定画面に出すキャッシュの状況。読み込むまでは nil。
    @Published private(set) var cacheInfo: CacheInfo?
    @Published private(set) var authorization: CLAuthorizationStatus = .notDetermined

    var hasLocationPermission: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    private let cache: FileMountainCache
    private let repository: MountainRepository
    /// 山データの事前ダウンロード。画面を閉じても続くよう、ここで持つ。
    let areaDownload: AreaDownloadModel
    private let settingsStore = SettingsStore()
    private let locationService = LocationService()
    private let networkMonitor = NetworkMonitor()
    private var peaks: [Mountain] = []
    private var fetchedCenter: GeoPoint?
    private var fetchedRadiusKm = 0.0
    private var fetchTask: Task<Void, Never>?
    /// 圏外のため取得を控えた。値は手動の取得だったか。つながったらその続きを取得する。
    private var skippedWhileDisconnected: Bool?
    private let logger = Logger(subsystem: "io.github.shohei0205.yamamuki", category: "DialModel")

    /// これ以上移動したら取り直す。取得済みの地域ならキャッシュから読むだけで通信しない。
    private static let refetchDistanceKm = 1.0

    init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("mountains", isDirectory: true)
        cache = FileMountainCache(directory: directory)
        repository = MountainRepository(
            remote: OverpassClient(userAgent: "yamamuki-ios/0.1 (+https://github.com/shohei0205/yamamuki)"),
            cache: cache
        )
        areaDownload = AreaDownloadModel(repository: repository, cache: cache)
        let saved = SettingsStore().load()
        settings = saved
        rangeKm = Double(saved.initialRangeKm)
        authorization = locationService.authorization

        locationService.onLocation = { [weak self] in self?.onLocation($0) }
        locationService.onHeading = { [weak self] in self?.heading = $0 }
        networkMonitor.onChange = { [weak self] in self?.onConnectivity($0) }
        // 事前ダウンロードで現在地の周辺が埋まったり消えたりしたら、表示を読み直す。
        areaDownload.onCacheChanged = { [weak self] in self?.fetch() }
        locationService.onAuthorizationChange = { [weak self] status in
            guard let self else { return }
            authorization = status
            if hasLocationPermission { locationService.start() }
        }
    }

    /// 画面が前面に出たとき。初回は「山データを取得してよいか」に答えてから位置情報の許可を求める(ダイアログを重ねない)。
    func start() {
        if settings.networkConsentAsked && authorization == .notDetermined {
            locationService.requestAuthorization()
        }
        locationService.start()
    }

    func stop() {
        locationService.stop()
    }

    func requestLocationPermission() {
        locationService.requestAuthorization()
    }

    private func onLocation(_ loc: CLLocation) {
        // 高さを持たない位置(verticalAccuracy が負)では、標高の表示が消えないよう直前の標高を引き継ぐ。
        let msl = loc.verticalAccuracy >= 0 ? loc.altitude : location?.mslAltitudeM
        let point = GeoPoint(latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude, mslAltitudeM: msl)
        location = point
        updatePeaks(at: point)
        if let center = fetchedCenter,
           GeoMath.distanceKm(center.latitude, center.longitude, point.latitude, point.longitude) <= Self.refetchDistanceKm {
            return
        }
        fetch()
    }

    private func onConnectivity(_ connected: Bool) {
        guard connected != isConnected else { return }
        isConnected = connected
        // 圏外で控えていた取得を、つながったところで行う。
        if connected, let manual = skippedWhileDisconnected {
            skippedWhileDisconnected = nil
            fetch(manual: manual)
        }
    }

    /// ピンチの倍率(前回からの変化分)。
    func onZoom(_ zoom: Double) {
        rangeKm = DialGeometry.zoomedRange(rangeKm, zoom: zoom)
        if DialGeometry.fetchRadiusKm(rangeKm) > fetchedRadiusKm { fetch() }
    }

    func retry() { fetch(forceRefresh: true) }

    /// 通信エラーの知らせを閉じる。
    func dismissFetchError() { fetchErrorMessage = nil }

    /// 通信エラーの知らせから取り直す。利用者が求めたので、手動取得モードでも通信する。
    func retryAfterFetchError() {
        fetchErrorMessage = nil
        fetch(manual: true)
    }

    /// 左下の更新ボタン(山データを取得)。今の表示範囲のうち、未取得または古い地域を取得する。
    func fetchManually() { fetch(manual: true) }

    /// 初回起動時の「山データを自動で取得してよいか」への答え。いいえなら手動取得モードにする。
    func answerNetworkConsent(allow: Bool) {
        updateSettings {
            $0.networkConsentAsked = true
            $0.manualFetch = !allow
        }
        if allow { fetch() }
        if authorization == .notDetermined { locationService.requestAuthorization() }
    }

    func updateSettings(_ transform: (inout Settings) -> Void) {
        let before = settings
        var after = settings
        transform(&after)
        settings = after
        settingsStore.save(after)

        if after.minElevationM != before.minElevationM, let here = location {
            updatePeaks(at: here)
        }
        // 起動時の範囲を変えたら、試しやすいよう今の表示にもすぐ反映する。
        if after.initialRangeKm != before.initialRangeKm {
            rangeKm = Double(after.initialRangeKm)
            if DialGeometry.fetchRadiusKm(rangeKm) > fetchedRadiusKm { fetch() }
        }
        // 手動取得をやめたら、控えていた分をすぐ取得する。
        if before.manualFetch && !after.manualFetch && networkSkipped { fetch() }
    }

    func refreshCacheInfo() {
        Task { @MainActor in
            cacheInfo = await cache.info()
        }
    }

    /// キャッシュを消して、現在地周辺を取り直す。事前ダウンロードした地域は残す。
    func clearCache() {
        fetchTask?.cancel()
        let keep = areaDownload.savedTiles
        Task { @MainActor in
            await cache.clear(keeping: keep)
            peaks = []
            mountains = []
            summit = nil
            cacheInfo = await cache.info()
            fetch()
        }
    }

    private func fetch(forceRefresh: Bool = false, manual: Bool = false) {
        guard let here = location else { return }
        let radius = DialGeometry.fetchRadiusKm(rangeKm)
        let settings = self.settings
        // 初回の問い合わせに答えるまでは、キャッシュだけで表示して通信しない。
        let wantsNetwork = manual || (!settings.manualFetch && settings.networkConsentAsked)
        // 圏外と分かっていれば通信を試さない(失敗を待たず、エラーの知らせも出さない)。
        let connected = isConnected
        let allowNetwork = wantsNetwork && connected
        fetchedCenter = here
        fetchedRadiusKm = radius
        fetchTask?.cancel()
        fetchTask = Task { @MainActor [weak self] in
            guard let self else { return }
            loading = true
            do {
                let result = try await repository.mountainsAround(
                    latitude: here.latitude,
                    longitude: here.longitude,
                    radiusKm: radius,
                    forceRefresh: forceRefresh,
                    allowNetwork: allowNetwork,
                    maxAge: settings.cacheMaxAge
                )
                // 新しい取得に置き換わっていたら、古い結果で上書きしない。
                guard !Task.isCancelled else { return }
                if let error = result.error {
                    logger.warning("山データの取得に失敗: \(String(describing: error), privacy: .public)")
                }
                peaks = result.mountains.map(\.mountain)
                updatePeaks(at: location ?? here)
                let skippedOffline = wantsNetwork && !connected && result.networkSkipped
                if skippedOffline {
                    skippedWhileDisconnected = manual
                } else if allowNetwork {
                    skippedWhileDisconnected = nil
                }
                offline = result.error != nil || skippedOffline
                let hasCache = !peaks.isEmpty
                if let error = result.error {
                    fetchErrorMessage = Self.errorNotice(for: error, hasCache: hasCache)
                } else if skippedOffline && manual {
                    // 自分で取得を押したときだけ、圏外で取得できなかったことを知らせる。
                    fetchErrorMessage = Self.offlineNotice(hasCache: hasCache)
                } else if !skippedOffline {
                    fetchErrorMessage = nil
                }
                incomplete = result.incomplete
                networkSkipped = result.networkSkipped
                loading = false
            } catch {
                guard !Task.isCancelled else { return }
                logger.error("山データの読み込みに失敗: \(String(describing: error), privacy: .public)")
                loading = false
            }
        }
    }

    /// 通信エラーの知らせの文言。端末がつながっていないのか、サーバー側の問題かで案内を変える。
    private static func errorNotice(for error: Error, hasCache: Bool) -> String {
        if isOffline(error) { return offlineNotice(hasCache: hasCache) }
        let cause = "山データのサーバーが混み合っているか、応答がありません。しばらくしてから再取得してください。"
        return hasCache ? cause + "\n\n保存済みのデータで表示しています。" : cause
    }

    private static func offlineNotice(hasCache: Bool) -> String {
        let cause = "インターネットに接続されていません。電波の届く場所で再取得してください。"
        return hasCache ? cause + "\n\n保存済みのデータで表示しています。" : cause
    }

    /// 端末が通信できない状態で失敗したか。Overpass はエンドポイントごとの原因をまとめて返すので、すべてを見る。
    private static func isOffline(_ error: Error) -> Bool {
        if let e = error as? URLError {
            return [.notConnectedToInternet, .networkConnectionLost, .dataNotAllowed, .internationalRoamingOff].contains(e.code)
        }
        if let e = error as? OverpassError {
            return !e.causes.isEmpty && e.causes.allSatisfy(isOffline)
        }
        return false
    }

    /// [p] から見た山の一覧と、山頂にいるならその山を入れ直す。
    private func updatePeaks(at p: GeoPoint) {
        let all = peaks.map { $0.seen(fromLatitude: p.latitude, longitude: p.longitude) }
        let top = summitAt(all)
        let minElevation = settings.minElevationM
        mountains = all
            .filter { $0.mountain.osmId != top?.mountain.osmId && $0.mountain.meetsMinElevation(minElevation) }
            .sorted(by: displayPriority)
        summit = top
    }
}
