import CoreLocation

/// 現在地と、端末を向けている方位角(真北基準)を流す。
/// 方位は Core Location の heading を使う。現在地が分かっていれば iOS が偏角を補正した真方位(trueHeading)を返すので、
/// Android 版のように自分で偏角を足す必要はない。
/// iOS の heading はすでに平滑化されており、[headingFilter] 以上変わったときしか届かないので、アプリ側では平滑化しない
/// (届いた値を平滑化すると、端末を止めたときに追いつく前に値が来なくなり、ずれたまま止まる)。
final class LocationService: NSObject, CLLocationManagerDelegate {
    var onLocation: ((CLLocation) -> Void)?
    var onHeading: ((Double) -> Void)?
    var onAuthorizationChange: ((CLAuthorizationStatus) -> Void)?

    private let manager = CLLocationManager()

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
            if let last = manager.location { onLocation?(last) }
            manager.startUpdatingLocation()
        }
        if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let last = locations.last { onLocation?(last) }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard newHeading.headingAccuracy >= 0 else { return }
        // trueHeading は現在地が分かるまで負の値になる。その間は磁北基準で代用する。
        onHeading?(newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        onAuthorizationChange?(manager.authorizationStatus)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // 一時的に測位できないだけのことが多いので、次の更新を待つ。
    }
}
