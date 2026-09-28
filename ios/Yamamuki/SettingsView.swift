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
        NavigationView {
            Form {
                Section("表示する山") {
                    StepSlider(
                        value: settings.minElevationM,
                        range: 0...maxMinElevationM,
                        step: minElevationStepM,
                        label: minElevationLabel,
                        description: "0 m ですべての山を表示します。絞り込み中は標高不明の山を表示しません。"
                    ) { m in model.updateSettings { $0.minElevationM = m } }
                    StepSlider(
                        value: settings.maxPeaks,
                        range: Settings.maxPeaksRange,
                        step: maxPeaksStep,
                        label: { "一度に表示する山 最大 \($0) 件" },
                        description: "多いと画面が混み合い、少ないと高い山だけになります。重なる山は標高の低いほうを省きます。"
                    ) { n in model.updateSettings { $0.maxPeaks = n } }
                }

                Section("表示") {
                    Choice(
                        title: "文字の大きさ",
                        options: Settings.textScales,
                        selected: settings.textScale,
                        label: textScaleLabel
                    ) { v in model.updateSettings { $0.textScale = v } }
                    Choice(
                        title: "起動時の表示範囲（km）",
                        options: Settings.initialRangesKm,
                        selected: settings.initialRangeKm,
                        // 6 つ並ぶと「10km」が収まらないので、単位は見出しに出す。
                        label: { "\($0)" },
                        description: "現在地から画面上端までの距離。起動後はピンチで変えられます。"
                    ) { v in model.updateSettings { $0.initialRangeKm = v } }
                }

                Section("画面") {
                    SwitchRow(
                        title: "画面を常に点灯",
                        description: "方位盤を表示している間は画面を消しません。電池の減りが早くなります。",
                        isOn: settings.keepScreenOn
                    ) { v in model.updateSettings { $0.keepScreenOn = v } }
                }

                Section("通信とキャッシュ") {
                    SwitchRow(
                        title: "山データを手動で取得",
                        description: "自動では通信せず、保存済みのデータで表示します。方位盤の左下の更新ボタンを押したときだけ、" +
                            "今の表示範囲を取得します。山に入る前に、電波の届く場所で縮小して広めに取得しておくと安心です。",
                        isOn: settings.manualFetch
                    ) { v in model.updateSettings { $0.manualFetch = v } }
                    Choice(
                        title: "取得したデータを使う期間",
                        options: Settings.cacheMaxAgeDaysOptions,
                        selected: settings.cacheMaxAgeDays,
                        // 5 つ並ぶと「180日」が収まらないので、長い期間は「半年」「1年」と書く。
                        label: { days in
                            switch days {
                            case 180: return "半年"
                            case 365: return "1年"
                            default: return "\(days)日"
                            }
                        },
                        description: "この期間を過ぎた地域は取り直します。山データはめったに変わらないので、長くすると通信が減ります。"
                    ) { v in model.updateSettings { $0.cacheMaxAgeDays = v } }
                    CacheSection(info: model.cacheInfo, onClear: model.clearCache)
                }

                Section("このアプリについて") {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("バージョン \(appVersion)")
                        Text("山データ © OpenStreetMap contributors (ODbL)").font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
        // iOS 15 でも使えるよう NavigationView にする。1 画面だけなので分割表示にはしない。
        .navigationViewStyle(.stack)
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
    m == 0 ? "すべての山を表示" : "標高 \(groupedInteger(m)) m 以上の山だけ表示"
}

private func textScaleLabel(_ scale: Double) -> String {
    switch scale {
    case 0.85: return "小"
    case 1.0: return "標準"
    case 1.2: return "大"
    case 1.4: return "特大"
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

private struct CacheSection: View {
    let info: CacheInfo?
    let onClear: () -> Void
    @State private var confirming = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("保存しているデータ")
            if let info {
                Text("山 \(groupedInteger(info.mountainCount)) 件（取得済みの区画 \(info.tileCount) 個）・容量 \(byteSizeText(info.sizeBytes))")
                    .font(.subheadline)
            } else {
                Text("読み込み中…").font(.subheadline)
            }
            Text("消去すると現在地の周辺を取り直すので、通信が発生します。").font(.footnote).foregroundStyle(.secondary)
        }
        Button("キャッシュを消去", role: .destructive) { confirming = true }
            .disabled(info == nil || info?.tileCount == 0)
            .alert("キャッシュを消去しますか？", isPresented: $confirming) {
                Button("消去", role: .destructive, action: onClear)
                Button("キャンセル", role: .cancel) {}
            } message: {
                Text(
                    Features.areaDownload
                        ? "保存している山データを消去し、現在地の周辺を取り直します。事前ダウンロードした地域は残ります。"
                        : "保存している山データをすべて消去し、現在地の周辺を取り直します。"
                )
            }
    }
}
