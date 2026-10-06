import SwiftUI
import YamamukiCore

/// 山データの事前ダウンロード画面。方位盤の左下のダウンロードボタンで開く。
struct AreaDownloadView: View {
    @ObservedObject var model: DialModel
    @ObservedObject var download: AreaDownloadModel
    @Environment(\.dismiss) private var dismiss

    /// ダウンロードを確かめる都道府県と、取り直しか。
    @State private var confirming: Confirmation?
    @State private var deleting: SavedArea?

    private struct Confirmation {
        let prefecture: Prefecture
        let refresh: Bool
    }

    private var canStart: Bool { download.running == nil && model.isConnected }

    var body: some View {
        NavigationView {
            List {
                Section {
                    Text(Strings.text("area_description"))
                        .font(.subheadline)
                    if !model.isConnected {
                        Text(Strings.text("area_offline"))
                            .font(.subheadline)
                            .foregroundStyle(.red)
                    }
                }

                if let running = download.running {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(Strings.format("area_running", running.prefecture.name, running.progress.doneTiles, running.progress.totalTiles))
                                .font(.headline)
                            ProgressView(value: running.progress.fraction)
                            // 区画数が増えない間も止まっていないことが分かるよう、待っている秒数を 1 秒ごとに出す。
                            TimelineView(.periodic(from: running.since, by: 1)) { context in
                                Text(downloadStatusText(running.progress.status(elapsed: context.date.timeIntervalSince(running.since))))
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Button(Strings.text("area_cancel"), role: .destructive) { download.cancel() }
                    }
                }

                if let notice = download.notice {
                    Section {
                        Text(notice.message).font(.subheadline)
                        if let resume = notice.resume {
                            Button(Strings.text("area_resume")) {
                                download.start(resume, refresh: notice.resumeRefresh, maxAge: AreaDownloadModel.maxAge)
                            }
                            .disabled(!canStart)
                        }
                        Button(Strings.text("common_close")) { download.dismissNotice() }
                    }
                }

                if !download.savedAreas.isEmpty {
                    Section(Strings.text("area_section_saved")) {
                        ForEach(download.savedAreas) { area in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(area.prefecture.name)
                                    Text(Strings.format("area_saved_summary", dateText(area.downloadedAt), groupedInteger(area.mountainCount)))
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                // 1 行に 2 つのボタンを置くので、行全体ではなくボタンだけが反応するようにする。
                                Button(Strings.text("area_update")) { confirming = Confirmation(prefecture: area.prefecture, refresh: true) }
                                    .buttonStyle(.borderless)
                                    .disabled(!canStart)
                                // 削除は通信しないので、圏外でも(山で容量を空けたいときなど)できるようにする。
                                Button(Strings.text("common_delete"), role: .destructive) { deleting = area }
                                    .buttonStyle(.borderless)
                                    .disabled(download.running != nil)
                            }
                        }
                    }
                }

                let saved = Set(download.savedAreas.map(\.prefecture.code))
                ForEach(regions, id: \.name) { region in
                    Section(region.name) {
                        ForEach(region.prefectures) { p in
                            Button {
                                confirming = Confirmation(prefecture: p, refresh: false)
                            } label: {
                                HStack {
                                    Text(p.name).foregroundStyle(.primary)
                                    Spacer()
                                    Text(saved.contains(p.code) ? Strings.text("area_saved") : Strings.format("area_tiles", p.tiles.count))
                                        .font(.footnote)
                                        .foregroundColor(saved.contains(p.code) ? .accentColor : .secondary)
                                }
                            }
                            .disabled(!canStart || saved.contains(p.code))
                        }
                    }
                }
            }
            .navigationTitle(Strings.text("area_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(Strings.text("common_close")) { dismiss() }
                }
            }
        }
        .alert(
            confirming.map {
                Strings.format($0.refresh ? "area_confirm_refresh_title" : "area_confirm_download_title", $0.prefecture.name)
            } ?? "",
            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
            presenting: confirming
        ) { c in
            Button(Strings.text(c.refresh ? "area_refresh" : "area_download")) {
                download.start(c.prefecture, refresh: c.refresh, maxAge: AreaDownloadModel.maxAge)
            }
            Button(Strings.text("common_cancel"), role: .cancel) {}
        } message: { c in
            Text(Strings.format("area_confirm_message", c.prefecture.tiles.count))
        }
        .alert(
            deleting.map { Strings.format("area_delete_title", $0.prefecture.name) } ?? "",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            presenting: deleting
        ) { area in
            Button(Strings.text("common_delete"), role: .destructive) { download.delete(area) }
            Button(Strings.text("common_cancel"), role: .cancel) {}
        } message: { _ in
            Text(Strings.text("area_delete_message"))
        }
    }

    private struct Region {
        let name: String
        let prefectures: [Prefecture]
    }

    /// 地方ごとに、都道府県コード順のまま分ける。
    private var regions: [Region] {
        var result: [Region] = []
        for p in Prefecture.all {
            if result.last?.name == p.region {
                result[result.count - 1] = Region(name: p.region, prefectures: result[result.count - 1].prefectures + [p])
            } else {
                result.append(Region(name: p.region, prefectures: [p]))
            }
        }
        return result
    }
}

private func dateText(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "ja_JP")
    formatter.dateFormat = "yyyy/MM/dd"
    return formatter.string(from: date)
}
