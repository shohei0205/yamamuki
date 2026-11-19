// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import SwiftUI
import YamamukiCore

extension SurfaceDataset {
    /// 地表データの種類の名前。
    var label: String {
        switch self {
        case .esaWorldcoverZ9: return Strings.text("surface_dataset_esa_worldcover_z9")
        case .esaWorldcoverZ10: return Strings.text("surface_dataset_esa_worldcover_z10")
        case .esaWorldcoverZ11: return Strings.text("surface_dataset_esa_worldcover_z11")
        case .esaWorldcoverZ12: return Strings.text("surface_dataset_esa_worldcover_z12")
        }
    }
}

extension OnlineSurface {
    /// 通信して取る地図の種類の名前。
    var label: String {
        switch self {
        case .gsiPale: return Strings.text("online_surface_gsi_pale")
        case .gsiStd: return Strings.text("online_surface_gsi_std")
        case .gsiHillshade: return Strings.text("online_surface_gsi_hillshade")
        case .gsiRelief: return Strings.text("online_surface_gsi_relief")
        case .gsiPhoto: return Strings.text("online_surface_gsi_photo")
        }
    }
}

/// 地表データの選択画面で選んでいるもの。
enum SurfaceChoice: Equatable {
    /// 表示しない。
    case none
    /// オフラインデータ(yamamuki-data の地表データ)。
    case offline(SurfaceDataset)
    /// オンラインデータ(地理院タイル)。
    case online(OnlineSurface)

    /// 設定から、選んでいるものを決める。取得していない地表データは「表示しない」とする(方位盤にも描かない)。
    @MainActor
    static func of(_ settings: Settings, surface: SurfaceDataModel) -> SurfaceChoice {
        guard settings.showSurface else { return .none }
        if let online = settings.onlineSurface { return .online(online) }
        return surface.installed(settings.surfaceDataset) ? .offline(settings.surfaceDataset) : .none
    }
}

/// 地表データの選択画面(`SurfaceDataView`)の中身。方位盤の背景に描くものを、「表示しない」、オフラインデータ
/// (yamamuki-data の地表データ)、オンラインデータ(地理院タイル)から 1 つ選ぶ。
/// オフラインデータは取得済みの種類とカタログで取得できる種類を並べ、選ぶとその種類を表示する(取得していなければ
/// ダウンロードするかを聞く)。取得済みの種類は更新と削除ができ、陰影を重ねるかもここで切り替える。
/// オンラインデータはダウンロードせず、選ぶとすぐ表示する。
struct SurfaceDataSection: View {
    @ObservedObject var surface: SurfaceDataModel
    let settings: Settings
    @State private var deleting: SurfaceDataset?

    private var choice: SurfaceChoice { SurfaceChoice.of(settings, surface: surface) }

    var body: some View {
        Section {
            ChoiceRow(label: Strings.text("surface_none"), selected: choice == .none, enabled: surface.updating == nil) {
                surface.chooseNone()
            }
        } header: {
            // 説明は見出しの太い字にせず、フッターと同じ小さな字で出す。
            Text(Strings.text("surface_data_section_description"))
                .font(.footnote)
                .fontWeight(.regular)
                .textCase(nil)
        }
        Section {
            ForEach(surface.entries, id: \.dataset) { entry in
                SurfaceDataRow(
                    entry: entry,
                    selected: choice == .offline(entry.dataset),
                    enabled: surface.updating == nil,
                    progress: surface.updating == entry.dataset ? surface.progress : nil,
                    onChoose: { surface.choose(entry.dataset) },
                    onUpdate: { surface.offerDownload(entry.dataset) },
                    onDelete: { deleting = entry.dataset }
                )
            }
            if surface.checking {
                HStack(spacing: 8) {
                    ProgressView()
                    Text(Strings.text("surface_data_checking")).foregroundStyle(.secondary)
                }
            } else if surface.entries.isEmpty {
                Text(Strings.text("surface_data_none")).foregroundStyle(.secondary)
            }
            Toggle(isOn: Binding(get: { settings.showHillshade }, set: { surface.setHillshade($0) })) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(Strings.text("settings_show_hillshade"))
                    Text(Strings.text("settings_show_hillshade_description")).font(.footnote).foregroundStyle(.secondary)
                }
            }
        } header: {
            // yamamuki-data の地表データはまだ試験的に配っているので、山データの試験版と同じ印を見出しに付ける。
            HStack(spacing: 8) {
                Text(Strings.text("surface_offline_section_title"))
                SurfaceBadge(text: Strings.text("peak_data_list_experimental")).textCase(nil)
            }
        } footer: {
            Text(Strings.text("surface_offline_section_description"))
        }
        Section {
            ForEach(OnlineSurface.allCases, id: \.self) { online in
                ChoiceRow(label: online.label, selected: choice == .online(online), enabled: surface.updating == nil) {
                    surface.chooseOnline(online)
                }
            }
        } header: {
            Text(Strings.text("online_surface_section_title"))
        } footer: {
            Text(Strings.text("online_surface_section_description"))
        }
        .onAppear { surface.open() }
        .alert(
            Strings.text("surface_data_offer_title"),
            isPresented: Binding(get: { surface.offer != nil }, set: { if !$0 { surface.answerOffer(download: false) } }),
            presenting: surface.offer
        ) { _ in
            Button(Strings.text("peak_data_offer_download")) { surface.answerOffer(download: true) }
            Button(Strings.text("peak_data_offer_later"), role: .cancel) { surface.answerOffer(download: false) }
        } message: { offer in
            Text(Strings.format("surface_data_offer_message", offer.dataset.label, offerDetail(offer.manifest)))
        }
        .alert(
            Strings.text("surface_data_error_title"),
            isPresented: Binding(get: { surface.errorMessage != nil }, set: { if !$0 { surface.errorMessage = nil } })
        ) {
            Button(Strings.text("common_close"), role: .cancel) {}
        } message: {
            Text(surface.errorMessage ?? "")
        }
        .alert(
            deleting.map { Strings.format("surface_data_delete_title", $0.label) } ?? "",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            presenting: deleting
        ) { d in
            Button(Strings.text("peak_data_list_delete"), role: .destructive) { surface.delete(d) }
            Button(Strings.text("common_cancel"), role: .cancel) {}
        } message: { _ in
            Text(Strings.text("surface_data_delete_message"))
        }
    }

    /// データを作った日が版の文字列から読めなければ、大きさだけを出す。
    private func offerDetail(_ manifest: SurfaceManifest) -> String {
        let size = byteSizeText(Int64(manifest.sizeBytes))
        if let date = manifest.releaseDate { return Strings.format("peak_data_offer_detail", date, size) }
        return Strings.format("peak_data_offer_size", size)
    }
}

/// 種類ごとの行。名前と印(試験版・使用中・更新あり)、リリース日・取得日・大きさ、ダウンロード中は進み具合を出す。
private struct SurfaceDataRow: View {
    let entry: SurfaceDataEntry
    let selected: Bool
    let enabled: Bool
    let progress: Double?
    let onChoose: () -> Void
    let onUpdate: () -> Void
    let onDelete: () -> Void

    private var inUse: Bool { selected && entry.installed != nil }

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onChoose) {
                HStack(spacing: 12) {
                    Image(systemName: inUse ? "largecircle.fill.circle" : "circle")
                        .foregroundStyle(inUse ? Color.accentColor : Color.secondary)
                        .font(.title3)
                    VStack(alignment: .leading, spacing: 4) {
                        // ボタンの中は文字が強調色になるので、黒を指定する。
                        Text(entry.dataset.label).foregroundStyle(Color.primary)
                        if !badges.isEmpty {
                            HStack(spacing: 6) {
                                ForEach(badges, id: \.self) { SurfaceBadge(text: $0) }
                            }
                        }
                        Text(summary).font(.subheadline).foregroundStyle(Color.secondary)
                        if let progress {
                            Text(Strings.format("surface_data_fetching", Int(progress * 100)))
                                .font(.subheadline)
                                .foregroundStyle(Color.secondary)
                            ProgressView(value: progress)
                        }
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .opacity(enabled || progress != nil ? 1 : 0.4)
            .accessibilityAddTraits(inUse ? .isSelected : [])
            if entry.installed != nil, progress == nil {
                VStack(alignment: .trailing, spacing: 8) {
                    if entry.updateFound {
                        Button(Strings.text("surface_data_update"), action: onUpdate).buttonStyle(.borderless)
                    }
                    Button(Strings.text("peak_data_list_delete"), role: .destructive, action: onDelete).buttonStyle(.borderless)
                }
                .disabled(!enabled)
            }
        }
    }

    /// 名前の下に出す印(試験版・使用中・更新あり)。
    private var badges: [String] {
        var texts: [String] = []
        if entry.experimental { texts.append(Strings.text("peak_data_list_experimental")) }
        if inUse { texts.append(Strings.text("peak_data_list_in_use")) }
        if entry.updateFound { texts.append(Strings.text("peak_data_list_update_found")) }
        return texts
    }

    private var summary: String {
        if let installed = entry.installed {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "ja_JP")
            formatter.dateFormat = "yyyy/MM/dd"
            let date = formatter.string(from: installed.installedAt)
            let size = byteSizeText(Int64(installed.sizeBytes))
            if let release = installed.releaseDate { return Strings.format("surface_data_summary_installed", release, date, size) }
            return Strings.format("surface_data_summary_installed_no_release", date, size)
        }
        if let available = entry.available {
            let size = byteSizeText(Int64(available.sizeBytes))
            if let release = available.releaseDate { return Strings.format("surface_data_summary_available", release, size) }
            return Strings.format("peak_data_offer_size", size)
        }
        return ""
    }
}

/// 名前の下に出す小さな印。
private struct SurfaceBadge: View {
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

/// 名前だけの選択肢の行。選んでいれば「使用中」の印を付ける。
private struct ChoiceRow: View {
    let label: String
    let selected: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                    .font(.title3)
                // ボタンの中は文字が強調色になるので、黒を指定する。
                Text(label).foregroundStyle(Color.primary)
                if selected { SurfaceBadge(text: Strings.text("peak_data_list_in_use")) }
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
