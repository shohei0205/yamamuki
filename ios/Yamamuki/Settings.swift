// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

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
    /// 方位盤に引く補助線の種類。
    var gridStyle = GridStyle.rings
    /// 起動時の表示範囲(現在地から画面上端までの距離)。
    var initialRangeKm = Int(DialGeometry.defaultRangeKm)
    /// 現在地と方位をどのくらいこまめに測るか。こまめなほど電池を使う。
    var sensorPrecision = SensorPrecision.standard
    /// 初回起動時の「山データを取得しますか」に答えた。答えるまでは位置情報の許可を求めない(ダイアログを重ねない)。
    var peakDataAsked = false
    /// 方位盤に出す山のグループ(地点のタグ)。どれかを持つ山だけを出す。空なら絞り込まない。
    var peakGroups: Set<String> = []
    /// アプリを開いたときに、新しい山データがあるかを自動で確かめる(30 日に 1 回、取り込みはしない)。
    var autoCheckPeakData = false
    /// 方位盤・検索・出典に使う山データの種類。設定の「山データ」で選ぶ。
    var peakDataset = PeakDataset.default

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
        gridStyle = (try? c.decodeIfPresent(GridStyle.self, forKey: .gridStyle)) ?? d.gridStyle
        initialRangeKm = try c.decodeIfPresent(Int.self, forKey: .initialRangeKm) ?? d.initialRangeKm
        sensorPrecision = (try? c.decodeIfPresent(SensorPrecision.self, forKey: .sensorPrecision)) ?? d.sensorPrecision
        peakDataAsked = try c.decodeIfPresent(Bool.self, forKey: .peakDataAsked) ?? d.peakDataAsked
        peakGroups = try c.decodeIfPresent(Set<String>.self, forKey: .peakGroups) ?? d.peakGroups
        autoCheckPeakData = try c.decodeIfPresent(Bool.self, forKey: .autoCheckPeakData) ?? d.autoCheckPeakData
        // 知らない種類(新しい版のアプリで選んだもの)なら、既定に戻す。
        peakDataset = (try? c.decodeIfPresent(PeakDataset.self, forKey: .peakDataset)) ?? d.peakDataset
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

/// 方位盤に引く補助線の種類。設定画面ではこの順に並ぶ。
enum GridStyle: String, Codable, CaseIterable {
    /// 距離の円(同心円)と距離の数字。
    case rings
    /// 同心円に、45° ごとの方位線と、端末を向けている方向の線を足す。
    case bearingLines
    /// 同心円の内側に 10° ごとの刻みを入れる。
    case ringTicks
    /// 補助線を引かない(距離の帯の色分けだけ残す)。
    case off

    var label: String {
        switch self {
        case .rings: return Strings.text("settings_grid_style_rings")
        case .bearingLines: return Strings.text("settings_grid_style_bearing_lines")
        case .ringTicks: return Strings.text("settings_grid_style_ring_ticks")
        case .off: return Strings.text("settings_grid_style_none")
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
