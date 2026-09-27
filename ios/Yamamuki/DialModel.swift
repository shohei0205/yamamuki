import CoreLocation
import Foundation
import Observation
import os
import YamamukiCore

struct GeoPoint: Equatable {
    let latitude: Double
    let longitude: Double
    /// 標高(海抜)。求められないときは nil。
    let mslAltitudeM: Double?
}

/// 現在地と表示範囲に応じて山データを取得し、方位盤に出す山の一覧を保つ(Android 版の DialViewModel に相当)。
@Observable
final class DialModel {
    private(set) var location: GeoPoint?
    /// 現在地から見た山。最低標高で絞り込み、表示の優先順(標高の高い順)に並べたもの。現在地が変わるたびに計算し直す。
    private(set) var mountains: [NearbyMountain] = []
    /// 現在地がほぼ山頂([summitRadiusKm] 以内)のとき、その山。
    /// 最低標高の絞り込みとは関係なく探し、[mountains] からは除く(現在地の位置に別のアイコンで出す)。
    private(set) var summit: NearbyMountain?
    /// 端末を向けている方位(真北基準)。センサーの値が届くまでは nil。
    private(set) var heading: Double?
    /// 現在地から画面上端までの距離。
    private(set) var rangeKm: Double
    private(set) var loading = false
    /// 通信に失敗し、キャッシュだけで表示している。
    private(set) var offline = false
    /// 範囲内に一度も取得できていない地域がある。
    private(set) var incomplete = false
    /// 手動取得モードのため、未取得または古い地域があっても通信しなかった。
    private(set) var networkSkipped = false
    private(set) var settings: Settings
    /// 設定画面に出すキャッシュの状況。読み込むまでは nil。
    private(set) var cacheInfo: CacheInfo?
    private(set) var authorization: CLAuthorizationStatus = .notDetermined

    var hasLocationPermission: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    private let cache: FileMountainCache
    private let repository: MountainRepository
    private let settingsStore = SettingsStore()
    private let locationService = LocationService()
    private let headingFilter = HeadingFilter()
    @ObservationIgnored private var peaks: [Mountain] = []
    @ObservationIgnored private var fetchedCenter: GeoPoint?
    @ObservationIgnored private var fetchedRadiusKm = 0.0
    @ObservationIgnored private var fetchTask: Task<Void, Never>?
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
        let saved = SettingsStore().load()
        settings = saved
        rangeKm = Double(saved.initialRangeKm)
        authorization = locationService.authorization

        locationService.onLocation = { [weak self] in self?.onLocation($0) }
        locationService.onHeading = { [weak self] raw in
            guard let self else { return }
            heading = headingFilter.update(raw)
        }
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

    /// ピンチの倍率(前回からの変化分)。
    func onZoom(_ zoom: Double) {
        rangeKm = DialGeometry.zoomedRange(rangeKm, zoom: zoom)
        if DialGeometry.fetchRadiusKm(rangeKm) > fetchedRadiusKm { fetch() }
    }

    func retry() { fetch(forceRefresh: true) }

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

    /// キャッシュを消して、現在地周辺を取り直す。
    func clearCache() {
        fetchTask?.cancel()
        Task { @MainActor in
            await cache.clear()
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
        let allowNetwork = manual || (!settings.manualFetch && settings.networkConsentAsked)
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
                offline = result.error != nil
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
