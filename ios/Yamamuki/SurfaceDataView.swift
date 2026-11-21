// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import SwiftUI

/// 地表データの選択画面。方位盤の左下の地表データのボタンから開く。方位盤の背景に描くものを、「表示しない」、
/// オフラインデータ(yamamuki-data の地表データ)、オンラインデータ(地理院タイル)から選ぶ(`SurfaceDataForm`)。
///
/// `DialModel` は方位や現在地が変わるたびに変わる(実機の方位センサーでは 1 秒に何度も)。それを監視すると、そのたびに
/// この画面を作り直すので、`DialModel` は監視せず、設定だけを受け取る。
struct SurfaceDataView: View {
    private let model: DialModel
    @ObservedObject private var surface: SurfaceDataModel
    @State private var settings: Settings
    @Environment(\.dismiss) private var dismiss

    init(model: DialModel) {
        self.model = model
        surface = model.surface
        _settings = State(initialValue: model.settings)
    }

    var body: some View {
        NavigationView {
            SurfaceDataForm(surface: surface, settings: settings)
                .navigationTitle(Strings.text("surface_data_section_title"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(Strings.text("common_close")) { dismiss() }
                    }
                }
        }
        .navigationViewStyle(.stack)
        .onReceive(model.$settings.removeDuplicates()) { settings = $0 }
    }
}
