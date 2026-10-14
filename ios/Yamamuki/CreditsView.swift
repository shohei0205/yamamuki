import SwiftUI
import YamamukiCore

/// OpenStreetMap の著作権とライセンスの説明ページ。山データの出典が読めないときに添える。
private let osmCopyrightUrl = "https://www.openstreetmap.org/copyright"

/// 山データの出典とライセンスの画面。設定画面の「データの出典とライセンス」の行から開く。
/// 取り込み済みの山データの manifest から、OSM の表示と、補足に使った資料をすべて出す。
/// 山データが無いときや出典が読めないときも、OpenStreetMap の著作者表示は出す。
struct CreditsView: View {
    @ObservedObject var model: DialModel

    /// manifest を読み終えるまでの状態。
    private enum Load: Equatable {
        case loading
        case loaded(PeakDataCredits?)
    }

    @State private var load = Load.loading

    var body: some View {
        Form {
            switch load {
            case .loading:
                EmptyView()
            case .loaded(let credits?):
                Section(Strings.text("credits_section_data")) {
                    CreditsItems(items: [
                        CreditsItem(Strings.text("credits_release"), credits.releaseDate),
                        CreditsItem(Strings.text("credits_license"), credits.license),
                        CreditsItem(Strings.text("credits_attribution"), credits.attribution),
                        CreditsItem(Strings.text("credits_source_url"), credits.sourceUrl),
                    ])
                }
                if credits.supplements.isEmpty {
                    Section {
                        Text(Strings.text("credits_no_supplements")).font(.footnote).foregroundStyle(.secondary)
                    } header: {
                        Text(Strings.text("credits_section_supplements"))
                    }
                }
                ForEach(Array(credits.supplements.enumerated()), id: \.element.id) { index, source in
                    Section {
                        Text(source.id).font(.headline)
                        CreditsItems(items: [
                            CreditsItem(Strings.text("credits_source"), source.source),
                            CreditsItem(Strings.text("credits_license"), source.license),
                            CreditsItem(Strings.text("credits_attribution"), source.attribution),
                            CreditsItem(Strings.text("credits_changes"), source.changes),
                        ])
                    } header: {
                        // 見出しは最初の出典の上にだけ出す。
                        if index == 0 { Text(Strings.text("credits_section_supplements")) }
                    }
                }
            case .loaded(nil):
                // 山データが無い・出典が残っていないときも、アプリが持つ OSM の表示は出す。
                Section {
                    CreditsItems(items: [CreditsItem(Strings.text("credits_attribution"), "\(Strings.text("credits_osm_attribution")) \(osmCopyrightUrl)")])
                } header: {
                    Text(Strings.text("credits_section_data"))
                } footer: {
                    Text(Strings.text(model.peakData == nil ? "credits_not_installed" : "credits_missing"))
                }
            }
        }
        .navigationTitle(Strings.text("settings_credits"))
        .navigationBarTitleDisplayMode(.inline)
        // 山データを取り直したら読み直す。
        .task(id: model.peakData) {
            load = .loaded(model.peakData == nil ? nil : await model.peakDataCredits())
        }
    }
}

/// 「ライセンス」などの見出しと値を 1 組ずつ並べる。値の無い項目は出さない。
private struct CreditsItems: View {
    let items: [CreditsItem]

    var body: some View {
        ForEach(items, id: \.label) { item in
            if let value = item.value {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.label).font(.caption).foregroundStyle(.secondary)
                    Text(linkified(value)).font(.subheadline)
                }
            }
        }
    }
}

/// 「ライセンス」などの見出しと値。値が nil なら出さない。
private struct CreditsItem {
    let label: String
    let value: String?

    init(_ label: String, _ value: String?) {
        self.label = label
        self.value = value
    }
}

/// 文中の URL を、タップするとブラウザで開くリンクにする。
private func linkified(_ text: String) -> AttributedString {
    var result = AttributedString()
    for part in LinkText.split(text) {
        var piece = AttributedString(part.text)
        if part.isUrl, let url = linkUrl(part.text) { piece.link = url }
        result += piece
    }
    return result
}

/// URL の文字列を URL にする。iOS 16 以前は日本語を含む URL を URL(string:) で作れないので、符号化してから作る。
private func linkUrl(_ text: String) -> URL? {
    if let url = URL(string: text) { return url }
    var allowed = CharacterSet.urlQueryAllowed
    allowed.insert(charactersIn: "#%")
    return text.addingPercentEncoding(withAllowedCharacters: allowed).flatMap(URL.init(string:))
}
