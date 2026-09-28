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
                    Text("目的地域周辺の山データを、電波の届く場所で事前に端末へ保存する事ができます。\n事前に保存した山データは、キャッシュを消去しても残ります。")
                        .font(.subheadline)
                    if !model.isConnected {
                        Text("圏外のため、今はダウンロードできません。電波の届く場所で開いてください。")
                            .font(.subheadline)
                            .foregroundStyle(.red)
                    }
                }

                if let running = download.running {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("\(running.prefecture.name)をダウンロード中（\(running.progress.doneTiles) / \(running.progress.totalTiles) 区画）")
                                .font(.headline)
                            ProgressView(value: running.progress.fraction)
                            if running.progress.retry > 0 {
                                Text("サーバーが混み合っているため、取り直しています（\(running.progress.retry) 回目）")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Button("中断", role: .destructive) { download.cancel() }
                    }
                }

                if let notice = download.notice {
                    Section {
                        Text(notice.message).font(.subheadline)
                        if let resume = notice.resume {
                            Button("続きから再開") {
                                download.start(resume, refresh: notice.resumeRefresh, maxAge: model.settings.cacheMaxAge)
                            }
                            .disabled(!canStart)
                        }
                        Button("閉じる") { download.dismissNotice() }
                    }
                }

                if !download.savedAreas.isEmpty {
                    Section("保存済みの地域") {
                        ForEach(download.savedAreas) { area in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(area.prefecture.name)
                                    Text("\(dateText(area.downloadedAt)) 取得・山 \(groupedInteger(area.mountainCount)) 件")
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                // 1 行に 2 つのボタンを置くので、行全体ではなくボタンだけが反応するようにする。
                                Button("更新") { confirming = Confirmation(prefecture: area.prefecture, refresh: true) }
                                    .buttonStyle(.borderless)
                                    .disabled(!canStart)
                                // 削除は通信しないので、圏外でも(山で容量を空けたいときなど)できるようにする。
                                Button("削除", role: .destructive) { deleting = area }
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
                                    Text(saved.contains(p.code) ? "保存済み" : "\(p.tiles.count) 区画")
                                        .font(.footnote)
                                        .foregroundColor(saved.contains(p.code) ? .accentColor : .secondary)
                                }
                            }
                            .disabled(!canStart || saved.contains(p.code))
                        }
                    }
                }
            }
            .navigationTitle("事前ダウンロード")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
        .alert(
            confirming.map { $0.refresh ? "\($0.prefecture.name)を取り直しますか？" : "\($0.prefecture.name)をダウンロードしますか？" } ?? "",
            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
            presenting: confirming
        ) { c in
            Button(c.refresh ? "取り直す" : "ダウンロード") {
                download.start(c.prefecture, refresh: c.refresh, maxAge: model.settings.cacheMaxAge)
            }
            Button("キャンセル", role: .cancel) {}
        } message: { c in
            Text("\(c.prefecture.tiles.count) 区画の山データを OpenStreetMap（Overpass API）から取得します。サーバーの混み具合によっては数分かかります。途中で中断でき、この画面を閉じてもダウンロードは継続します。アプリを終了すると一時停止しますが、次に開いた時に再開できます。")
        }
        .alert(
            deleting.map { "\($0.prefecture.name)を削除しますか？" } ?? "",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            presenting: deleting
        ) { area in
            Button("削除", role: .destructive) { download.delete(area) }
            Button("キャンセル", role: .cancel) {}
        } message: { _ in
            Text("保存した山データを端末から消します。ほかの保存済みの地域と重なる部分は残ります。")
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
