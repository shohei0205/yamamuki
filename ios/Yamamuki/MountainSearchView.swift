import SwiftUI
import YamamukiCore

/// 山の検索画面。方位盤の右下の検索ボタンで開く。入力した文字を含む山を、先頭が一致した山と途中に含む山に分けて
/// それぞれ現在地から近い順に並べ、選ぶとその山頂を中心にした方位盤(検索位置モード)にする。
struct MountainSearchView: View {
    @ObservedObject var model: DialModel
    let onSelect: (Mountain) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @FocusState private var focused: Bool

    var body: some View {
        // iOS 15 でも使えるよう NavigationView にする。
        NavigationView {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField(Strings.text("search_placeholder"), text: $query)
                        .focused($focused)
                        .submitLabel(.search)
                        .onSubmit { focused = false }
                        .disableAutocorrection(true)
                }
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(.secondarySystemBackground)))
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                if let search = model.search, !search.loading, !search.noData {
                    Text(search.groups.isEmpty
                        ? Strings.text("search_scope_all")
                        : Strings.format("search_scope", search.groups.joined(separator: ", ")))
                        .font(.footnote)
                        .foregroundStyle(Color.accentColor)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 4)
                }
                content
            }
            .navigationTitle(Strings.text("search_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Strings.text("common_close")) { dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
        .onChange(of: query) { model.onSearchQuery($0) }
        // シートが出きる前だとキーボードが出ないことがあるので、少し待ってから入力欄を選ぶ。
        .onAppear { DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { focused = true } }
    }

    @ViewBuilder
    private var content: some View {
        let search = model.search ?? SearchState()
        if search.loading {
            note(Strings.text("search_loading"))
        } else if search.noData {
            note(Strings.text("search_no_data"))
        } else if !query.trimmingCharacters(in: .whitespaces).isEmpty && search.results.isEmpty {
            note(Strings.text("search_no_results"))
        } else {
            // 先頭が一致した山を先に、途中に含む山をあとに、見出しで分けて並べる。
            List {
                section(Strings.text("search_section_prefix"), search.results.prefix)
                section(Strings.text("search_section_partial"), search.results.partial)
            }
            .listStyle(.plain)
        }
    }

    @ViewBuilder
    private func section(_ title: String, _ mountains: [Mountain]) -> some View {
        if !mountains.isEmpty {
            Section {
                ForEach(mountains, id: \.osmId) { mountain in
                    Button { onSelect(mountain) } label: { row(mountain) }
                        .buttonStyle(.plain)
                }
            } header: {
                Text(title)
            }
        }
    }

    private func note(_ text: String) -> some View {
        VStack {
            Text(text).font(.subheadline).foregroundStyle(.secondary).padding(16)
            Spacer()
        }
    }

    /// 検索結果の 1 行。山名とよみがな、標高と現在地からの方角・距離。
    private func row(_ mountain: Mountain) -> some View {
        let elevation = elevationListLabel(mountain.elevationM)
        let detail = model.gpsLocation.map { here -> String in
            let seen = mountain.seen(fromLatitude: here.latitude, longitude: here.longitude)
            return Strings.format("detail_group_item", elevation,
                Strings.format("search_result_direction", directionName(seen.bearingDeg), distanceText(seen.distanceKm)))
        } ?? elevation
        return VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(mountain.name).font(.body)
                if let reading = mountain.nameReading {
                    Text(reading).font(.caption).foregroundStyle(.secondary)
                }
            }
            Text(detail).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}
