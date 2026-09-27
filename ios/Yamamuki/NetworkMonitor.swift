import Network

/// 端末が通信できる状態か(圏外・機内モードでないか)を流す。
/// 圏外なら通信を試す前に分かるので、取得を控えてオフラインとして動かすのに使う。
/// つながっていると判定されても、電波が弱いなどで実際の通信は失敗することがある。
final class NetworkMonitor {
    var onChange: ((Bool) -> Void)?

    private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            self?.onChange?(path.status == .satisfied)
        }
        monitor.start(queue: .main)
    }

    deinit {
        monitor.cancel()
    }
}
