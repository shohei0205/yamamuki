// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import SwiftUI
import YamamukiCore

/// 山データの出典とライセンスの画面。設定画面の「データの出典とライセンス」の行から開く。
/// 使っている種類の取り込み済みの山データの manifest から、元データの表示と、補足に使った資料をすべて出す
/// (manifest 版 6 は、元データも含めたすべての出典を「出典」として出す)。
/// 山データが無いときや出典が読めないときも、その種類の著作者表示は出す。
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
                    Text(model.dataset.label).font(.headline)
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
                        Text(sourcesHeading(credits))
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
                        if index == 0 { Text(sourcesHeading(credits)) }
                    }
                }
            case .loaded(nil):
                // 山データが無い・出典が残っていないときも、アプリが持つ種類ごとの表示は出す。
                Section {
                    Text(model.dataset.label).font(.headline)
                    CreditsItems(items: [CreditsItem(Strings.text("credits_attribution"), "\(model.dataset.creditsAttribution) \(model.dataset.copyrightUrl)")])
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

/// 出典の一覧の見出し。manifest 版 6 は元データと補足の区別が無いので、すべての出典を「出典」の見出しの下に並べる。
private func sourcesHeading(_ credits: PeakDataCredits) -> String {
    credits.listsAllSources ? Strings.text("credits_section_sources") : Strings.text("credits_section_supplements")
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
