import SwiftUI
import YamamukiCore

/// 最低標高スライダーの上限と刻み。
private let maxMinElevationM = 3000
private let minElevationStepM = 100
private let maxPeaksStep = 10

/// 設定画面。方位盤の左下の設定ボタンで開く。
struct SettingsView: View {
    @ObservedObject var model: DialModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let settings = model.settings
        NavigationStack {
            Form {
                Section(Strings.text("settings_section_peaks")) {
                    StepSlider(
                        value: settings.minElevationM,
                        range: 0...maxMinElevationM,
                        step: minElevationStepM,
                        label: minElevationLabel,
                        description: Strings.text("settings_min_elevation_description")
                    ) { m in model.updateSettings { $0.minElevationM = m } }
                    StepSlider(
                        value: settings.maxPeaks,
                        range: Settings.maxPeaksRange,
                        step: maxPeaksStep,
                        label: { Strings.format("settings_max_peaks", $0) },
                        description: Strings.text("settings_max_peaks_description")
                    ) { n in model.updateSettings { $0.maxPeaks = n } }
                }

                Section(Strings.text("settings_section_display")) {
                    Choice(
                        title: Strings.text("settings_text_size"),
                        options: Settings.textScales,
                        selected: settings.textScale,
                        label: textScaleLabel
                    ) { v in model.updateSettings { $0.textScale = v } }
                    Choice(
                        title: Strings.text("settings_initial_range"),
                        options: Settings.initialRangesKm,
                        selected: settings.initialRangeKm,
                        // 6 つ並ぶと「10km」が収まらないので、単位は見出しに出す。
                        label: { "\($0)" },
                        description: Strings.text("settings_initial_range_description")
                    ) { v in model.updateSettings { $0.initialRangeKm = v } }
                }

                Section(Strings.text("settings_section_battery")) {
                    SwitchRow(
                        title: Strings.text("settings_keep_screen_on"),
                        description: Strings.text("settings_keep_screen_on_description"),
                        isOn: settings.keepScreenOn
                    ) { v in model.updateSettings { $0.keepScreenOn = v } }
                    StepSlider(
                        value: settings.sensorPrecision.rawValue,
                        range: 0...(SensorPrecision.allCases.count - 1),
                        step: 1,
                        label: { Strings.format("settings_sensor_precision", SensorPrecision(rawValue: $0)?.label ?? "") },
                        description: Strings.text("settings_sensor_precision_description")
                    ) { i in model.updateSettings { $0.sensorPrecision = SensorPrecision(rawValue: i) ?? .standard } }
                }

                Section(Strings.text("settings_section_peak_data")) {
                    PeakDataSection(
                        data: model.peakData,
                        updating: model.peakDataUpdating,
                        notice: model.peakDataNotice,
                        onUpdate: model.updatePeakData
                    )
                    CacheSection(info: model.cacheInfo, onClear: model.clearCache)
                }

                Section(Strings.text("settings_section_about")) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(Strings.format("settings_version", appVersion))
                        Text(Strings.text("settings_data_license")).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(Strings.text("settings_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(Strings.text("common_close")) { dismiss() }
                }
            }
        }
        .onAppear { model.refreshCacheInfo() }
    }

    /// project.yml の MARKETING_VERSION / CURRENT_PROJECT_VERSION。
    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }
}

private func minElevationLabel(_ m: Int) -> String {
    m == 0 ? Strings.text("settings_min_elevation_all") : Strings.format("settings_min_elevation_value", groupedInteger(m))
}

private func textScaleLabel(_ scale: Double) -> String {
    switch scale {
    case 0.85: return Strings.text("settings_text_size_small")
    case 1.0: return Strings.text("settings_text_size_standard")
    case 1.2: return Strings.text("settings_text_size_large")
    case 1.4: return Strings.text("settings_text_size_extra_large")
    default: return "×\(scale)"
    }
}

/// [step] 刻みのスライダー。ドラッグ中は画面内だけで値を動かし、指を離したときに保存する。見出しは [label] で作る。
private struct StepSlider: View {
    let value: Int
    let range: ClosedRange<Int>
    let step: Int
    let label: (Int) -> String
    let description: String
    let onChange: (Int) -> Void

    @State private var dragging: Double?

    var body: some View {
        let current = dragging ?? Double(value)
        VStack(alignment: .leading, spacing: 4) {
            Text(label(Int(current.rounded())))
            Slider(
                value: Binding(get: { current }, set: { dragging = $0 }),
                in: Double(range.lowerBound)...Double(range.upperBound),
                step: Double(step)
            ) { editing in
                if !editing, let d = dragging {
                    onChange(Int(d.rounded()))
                    dragging = nil
                }
            }
            Text(description).font(.footnote).foregroundStyle(.secondary)
        }
    }
}

private struct Choice<T: Hashable>: View {
    let title: String
    let options: [T]
    let selected: T
    let label: (T) -> String
    var description: String?
    let onSelect: (T) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
            Picker(title, selection: Binding(get: { selected }, set: onSelect)) {
                ForEach(options, id: \.self) { option in
                    Text(label(option)).tag(option)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            if let description {
                Text(description).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

private struct SwitchRow: View {
    let title: String
    let description: String
    let isOn: Bool
    let onChange: (Bool) -> Void

    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: onChange)) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                Text(description).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

/// 全国の山データの取り込み状況と、取得(最新版の確認)のボタン。
private struct PeakDataSection: View {
    let data: InstalledPeakData?
    let updating: Bool
    let notice: String?
    let onUpdate: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(Strings.text("settings_peak_data_title"))
            if let data {
                // 元データの日時は UTC なので、日付だけを出す。
                Text(Strings.format(
                    "settings_peak_data_summary",
                    groupedInteger(data.mountainCount),
                    String(data.sourceTimestamp.prefix(10)).replacingOccurrences(of: "-", with: "/"),
                    installedDate(data.installedAt)
                ))
                    .font(.subheadline)
            } else {
                Text(Strings.text("settings_peak_data_none")).font(.subheadline)
            }
            if let notice { Text(notice).font(.subheadline).foregroundStyle(Color.accentColor) }
            Text(Strings.text("settings_peak_data_description"))
                .font(.footnote).foregroundStyle(.secondary)
        }
        Button(action: onUpdate) {
            HStack(spacing: 8) {
                if updating {
                    ProgressView()
                    Text(Strings.text("settings_peak_data_fetching"))
                } else {
                    Text(Strings.text(data == nil ? "settings_peak_data_fetch" : "settings_peak_data_check"))
                }
            }
        }
        .disabled(updating)
    }

    private func installedDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ja_JP")
        formatter.dateFormat = "yyyy/MM/dd"
        return formatter.string(from: date)
    }
}

private struct CacheSection: View {
    let info: CacheInfo?
    let onClear: () -> Void
    @State private var confirming = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(Strings.text("settings_cache_title"))
            if let info {
                Text(Strings.format("settings_cache_summary", groupedInteger(info.mountainCount), info.tileCount, byteSizeText(info.sizeBytes)))
                    .font(.subheadline)
            } else {
                Text(Strings.text("common_loading")).font(.subheadline)
            }
            Text(Strings.text("settings_cache_description")).font(.footnote).foregroundStyle(.secondary)
        }
        Button(Strings.text("settings_cache_clear"), role: .destructive) { confirming = true }
            .disabled(info == nil || info?.tileCount == 0)
            .alert(Strings.text("settings_cache_clear_title"), isPresented: $confirming) {
                Button(Strings.text("settings_cache_clear_confirm"), role: .destructive, action: onClear)
                Button(Strings.text("common_cancel"), role: .cancel) {}
            } message: {
                Text(Strings.text(Features.areaDownload ? "settings_cache_clear_message_keep_areas" : "settings_cache_clear_message"))
            }
    }
}
