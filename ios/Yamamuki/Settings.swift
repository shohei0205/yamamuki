import Foundation
import YamamukiCore

/// 設定画面で変えられる値。
struct Settings: Codable, Equatable {
    /// この標高(m)以上の山だけ方位盤に出す。0 なら絞り込まない。
    var minElevationM = 0
    /// 方位盤を表示している間は画面を消さない。
    var keepScreenOn = false
    /// 方位盤の 1 画面に出す山名の上限。山名を出さない山はアイコンだけで描く。
    var maxPeaks = 15
    /// 方位盤の文字の大きさ(標準 = 1.0 に対する倍率)。
    var textScale = 1.0
    /// 起動時の表示範囲(現在地から画面上端までの距離)。
    var initialRangeKm = Int(DialGeometry.defaultRangeKm)
    /// 現在地と方位をどのくらいこまめに測るか。こまめなほど電池を使う。
    var sensorPrecision = SensorPrecision.standard
    /// 初回起動時の「山データを取得しますか」に答えた。答えるまでは位置情報の許可を求めない(ダイアログを重ねない)。
    var peakDataAsked = false

    static let textScales = [0.85, 1.0, 1.2, 1.4]
    static let initialRangesKm = [5, 10, 15, 20, 30, 50]
    static let maxPeaksRange = 5...30

    init() {}

    /// 項目を足しても前の保存内容を読めるよう、無い項目は既定値にする。
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Settings()
        minElevationM = try c.decodeIfPresent(Int.self, forKey: .minElevationM) ?? d.minElevationM
        keepScreenOn = try c.decodeIfPresent(Bool.self, forKey: .keepScreenOn) ?? d.keepScreenOn
        // 以前は山の数(10〜100)だった。山名の数に変えたので、今の範囲に収める。
        let savedMaxPeaks = try c.decodeIfPresent(Int.self, forKey: .maxPeaks) ?? d.maxPeaks
        maxPeaks = min(max(savedMaxPeaks, Settings.maxPeaksRange.lowerBound), Settings.maxPeaksRange.upperBound)
        textScale = try c.decodeIfPresent(Double.self, forKey: .textScale) ?? d.textScale
        initialRangeKm = try c.decodeIfPresent(Int.self, forKey: .initialRangeKm) ?? d.initialRangeKm
        sensorPrecision = (try? c.decodeIfPresent(SensorPrecision.self, forKey: .sensorPrecision)) ?? d.sensorPrecision
        peakDataAsked = try c.decodeIfPresent(Bool.self, forKey: .peakDataAsked) ?? d.peakDataAsked
    }
}

/// 現在地と方位を測る頻度の段階。設定画面のスライダーで左(省電力)から右(高精度)へ並ぶ。
enum SensorPrecision: Int, Codable, CaseIterable {
    case saver = 0
    case standard = 1
    case precise = 2

    var label: String {
        switch self {
        case .saver: return Strings.text("settings_sensor_precision_saver")
        case .standard: return Strings.text("settings_sensor_precision_standard")
        case .precise: return Strings.text("settings_sensor_precision_precise")
        }
    }
}

/// [Settings] を端末内(UserDefaults)に保存する。
struct SettingsStore {
    private let defaults = UserDefaults.standard
    private let key = "settings"

    func load() -> Settings {
        guard let data = defaults.data(forKey: key),
              let settings = try? JSONDecoder().decode(Settings.self, from: data)
        else { return Settings() }
        return settings
    }

    func save(_ settings: Settings) {
        if let data = try? JSONEncoder().encode(settings) { defaults.set(data, forKey: key) }
    }
}
