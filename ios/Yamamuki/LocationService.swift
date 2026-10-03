import CoreLocation
import YamamukiCore

/// 現在地と、端末を向けている方位角(真北基準)を流す。
/// 方位は Core Location の heading を使う。現在地が分かっていれば iOS が偏角を補正した真方位(trueHeading)を返すので、
/// Android 版のように自分で偏角を足す必要はない。
/// iOS の heading はすでに平滑化されており、[headingFilter] 以上変わったときしか届かないので、アプリ側では平滑化しない
/// (届いた値を平滑化すると、端末を止めたときに追いつく前に値が来なくなり、ずれたまま止まる)。
final class LocationService: NSObject, CLLocationManagerDelegate {
    var onLocation: ((CLLocation) -> Void)?
    var onHeading: ((Double) -> Void)?
    /// 方位の精度が低い(ずれているかもしれない)か。変わったときに呼ぶ。
    var onHeadingAccuracyLow: ((Bool) -> Void)?
    var onAuthorizationChange: ((CLAuthorizationStatus) -> Void)?

    private let manager = CLLocationManager()
    private var headingAccuracyLow = false
    /// 精度が直前よりはっきり悪い位置で現在地が飛ばないよう、使う位置を選ぶ(Android 版と同じ判定)。
    private let filter = LocationFilter()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 20
        manager.headingFilter = 1
        manager.headingOrientation = .portrait
    }

    var authorization: CLAuthorizationStatus { manager.authorizationStatus }

    var isAuthorized: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    func requestAuthorization() {
        manager.requestWhenInUseAuthorization()
    }

    func start() {
        if isAuthorized {
            // 最初に端末が持っている直近の位置を流し、以降は更新を流す。
            if let last = manager.location { send(last) }
            manager.startUpdatingLocation()
        }
        if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let last = locations.last { send(last) }
    }

    private func send(_ location: CLLocation) {
        // horizontalAccuracy が負の位置は無効なので、精度が分からないものとして捨てる。
        let accuracy = location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil
        let timeMs = Int64(location.timestamp.timeIntervalSince1970 * 1000)
        if filter.accept(timeMs: timeMs, accuracyM: accuracy) { onLocation?(location) }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        // headingAccuracy は方位の誤差の見積もり(度)。負の値は方位が無効(補正が要る)という意味。
        let low = newHeading.headingAccuracy < 0 || newHeading.headingAccuracy > Self.lowHeadingAccuracyDeg
        if low != headingAccuracyLow {
            headingAccuracyLow = low
            onHeadingAccuracyLow?(low)
        }
        guard newHeading.headingAccuracy >= 0 else { return }
        // trueHeading は現在地が分かるまで負の値になる。その間は磁北基準で代用する。
        onHeading?(newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading)
    }

    /// 方位の誤差がこれを超えたら、精度が低いとして知らせる。
    private static let lowHeadingAccuracyDeg = 30.0

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        onAuthorizationChange?(manager.authorizationStatus)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // 一時的に測位できないだけのことが多いので、次の更新を待つ。
    }
}
