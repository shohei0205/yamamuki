import SwiftUI
import YamamukiCore

/// 山のグループを選ぶ画面。設定画面の「山のグループ」の行から開く。
struct PeakGroupsView: View {
    @ObservedObject var model: DialModel

    var body: some View {
        Form {
            PeakGroupsList(
                groups: model.peakGroups ?? [],
                selected: model.settings.peakGroups
            ) { v in model.updateSettings { $0.peakGroups = v } }
        }
        .navigationTitle(Strings.text("settings_peak_groups"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 山のグループのチェック欄と、すべてのチェックを外すボタン。チェックが無いときは、すべての山を表示する。
/// 方位盤からシートで開く入口を足すときも、この部品をそのまま使う。
struct PeakGroupsList: View {
    let groups: [TagGroup]
    let selected: Set<String>
    let onChange: (Set<String>) -> Void

    var body: some View {
        Section {
            ForEach(groups, id: \.name) { group in
                let checked = selected.contains(group.name)
                Button {
                    var next = selected
                    if checked { next.remove(group.name) } else { next.insert(group.name) }
                    onChange(next)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: checked ? "checkmark.square.fill" : "square")
                            .foregroundStyle(checked ? Color.accentColor : Color.secondary)
                            .font(.title3)
                        Text(group.name).foregroundStyle(.primary)
                        Spacer()
                        Text(Strings.format("settings_peak_group_count", group.count)).foregroundStyle(.secondary)
                    }
                }
                .accessibilityAddTraits(checked ? .isSelected : [])
            }
        } footer: {
            Text(Strings.text("settings_peak_groups_description"))
        }
        Section {
            Button(Strings.text("settings_peak_groups_clear")) { onChange([]) }
                .disabled(selected.isEmpty)
        }
    }
}

/// 設定画面の行に出す、今の絞り込み。「すべての山」「日本百名山」「九州百名山 ほか 1」。`groups` は名前順。
func peakGroupsSummary(_ groups: [String]) -> String {
    switch groups.count {
    case 0: return Strings.text("settings_peak_groups_all")
    case 1: return groups[0]
    default: return Strings.format("settings_peak_groups_more", groups[0], groups.count - 1)
    }
}
