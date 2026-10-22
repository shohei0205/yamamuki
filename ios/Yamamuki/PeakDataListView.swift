import SwiftUI
import YamamukiCore

extension PeakDataset {
    /// 山データの種類の名前。manifest の name は比べる名前として使いにくいので、アプリが知っている種類ごとに持つ。
    var label: String {
        switch self {
        case .osmPeaks: return Strings.text("peak_dataset_osm_peaks")
        case .gsiPeaks: return Strings.text("peak_dataset_gsi_peaks")
        }
    }

    /// 方位盤の右下に出す、短い著作者表示。
    var attribution: String {
        switch self {
        case .osmPeaks: return Strings.text("osm_attribution")
        case .gsiPeaks: return Strings.text("gsi_attribution")
        }
    }

    /// 出典の画面で、manifest が読めないときに出す著作者表示とライセンス。
    var creditsAttribution: String {
        switch self {
        case .osmPeaks: return Strings.text("credits_osm_attribution")
        case .gsiPeaks: return Strings.text("credits_gsi_attribution")
        }
    }

    /// 出典の画面で、manifest が読めないときに添える著作権とライセンスの説明ページ。
    var copyrightUrl: String {
        switch self {
        case .osmPeaks: return "https://www.openstreetmap.org/copyright"
        case .gsiPeaks: return "https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html"
        }
    }
}

/// 山データの選択画面。設定画面の「山データ」の、選んでいる山データの名前の行から開く。取得済みの種類と、カタログで取得できる種類を並べ、
/// 選ぶと切り替える(取得していなければダウンロードするかを聞く)。取得済みの種類は削除もできる。
struct PeakDataListView: View {
    @ObservedObject var model: DialModel
    @State private var deleting: PeakDataset?

    var body: some View {
        let list = model.peakDataList ?? PeakDataListState(entries: [])
        Form {
            Section {
                ForEach(list.entries, id: \.dataset) { entry in
                    PeakDataRow(
                        entry: entry,
                        selected: entry.dataset == model.dataset,
                        enabled: !model.peakDataUpdating,
                        onChoose: { model.choosePeakData(entry.dataset) },
                        onDelete: { deleting = entry.dataset }
                    )
                }
            } header: {
                // 見出しの太い字にせず、フッターと同じ小さな字で出す。
                Text(Strings.text("peak_data_list_description"))
                    .font(.footnote)
                    .fontWeight(.regular)
                    .textCase(nil)
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    if list.checking || model.peakDataUpdating {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text(Strings.text(model.peakDataUpdating ? "settings_peak_data_fetching" : "peak_data_list_checking"))
                        }
                    }
                    if list.offline { Text(Strings.text("peak_data_list_offline")) }
                }
            }
        }
        .navigationTitle(Strings.text("peak_data_list_title"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.openPeakDataList() }
        .onDisappear { model.closePeakDataList() }
        .alert(
            deleting.map { Strings.format("peak_data_delete_title", $0.label) } ?? "",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            presenting: deleting
        ) { d in
            Button(Strings.text("peak_data_list_delete"), role: .destructive) { model.deletePeakData(d) }
            Button(Strings.text("common_cancel"), role: .cancel) {}
        } message: { d in
            Text(Strings.text(d == model.dataset ? "peak_data_delete_message_in_use" : "peak_data_delete_message"))
        }
    }
}

/// 種類ごとの行。名前と印(試験版・使用中・更新あり)、取得済みなら件数と取得日、まだならダウンロードする大きさを出す。
private struct PeakDataRow: View {
    let entry: PeakDataEntry
    let selected: Bool
    let enabled: Bool
    let onChoose: () -> Void
    let onDelete: () -> Void

    var body: some View {
        // 取得しておらず、カタログでも確かめられなかった種類は選べない。
        let choosable = enabled && (entry.installed != nil || entry.available != nil)
        HStack(spacing: 12) {
            Button(action: onChoose) {
                HStack(spacing: 12) {
                    Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                        .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                        .font(.title3)
                    VStack(alignment: .leading, spacing: 2) {
                        title
                        Text(summary).font(.subheadline).foregroundStyle(Color.secondary)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!choosable)
            .opacity(choosable || selected ? 1 : 0.4)
            .accessibilityAddTraits(selected ? .isSelected : [])
            if entry.installed != nil {
                Button(Strings.text("peak_data_list_delete"), role: .destructive, action: onDelete)
                    .buttonStyle(.borderless)
                    .disabled(!enabled)
            }
        }
    }

    /// 名前と印。印が名前の横に入りきらないときは、印をまとめて次の行に送る(印の文字を縦に折り返さないように)。
    /// iOS 15 は入りきるかを測れないので、印があれば常に次の行に出す。
    @ViewBuilder private var title: some View {
        // ボタンの中は文字が強調色になるので、黒を指定する。
        let name = Text(entry.dataset.label).foregroundStyle(Color.primary)
        if badges.isEmpty {
            name
        } else if #available(iOS 16, *) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) { name; badgeRow }
                VStack(alignment: .leading, spacing: 4) { name; badgeRow }
            }
        } else {
            VStack(alignment: .leading, spacing: 4) { name; badgeRow }
        }
    }

    private var badgeRow: some View {
        HStack(spacing: 6) {
            ForEach(badges, id: \.self) { Badge(text: $0) }
        }
    }

    /// 名前の横に出す印(試験版・使用中・更新あり)。
    private var badges: [String] {
        var texts: [String] = []
        if entry.experimental { texts.append(Strings.text("peak_data_list_experimental")) }
        if selected && entry.installed != nil { texts.append(Strings.text("peak_data_list_in_use")) }
        if entry.updateFound { texts.append(Strings.text("peak_data_list_update_found")) }
        return texts
    }

    private var summary: String {
        if let installed = entry.installed {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "ja_JP")
            formatter.dateFormat = "yyyy/MM/dd"
            let date = formatter.string(from: installed.installedAt)
            if let release = installed.releaseDate {
                return Strings.format("settings_peak_data_summary", groupedInteger(installed.mountainCount), release, date)
            }
            return Strings.format("settings_peak_data_summary_no_release", groupedInteger(installed.mountainCount), date)
        }
        if let available = entry.available {
            let size = byteSizeText(Int64(available.sizeBytes))
            if let release = available.releaseDate {
                return Strings.format("peak_data_list_available", groupedInteger(available.mountainCount), release, size)
            }
            return Strings.format("peak_data_list_available_no_release", groupedInteger(available.mountainCount), size)
        }
        return Strings.text("settings_peak_data_none")
    }
}

/// 名前の横に出す小さな印。
private struct Badge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption2)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 4))
            .foregroundStyle(Color.primary)
    }
}
