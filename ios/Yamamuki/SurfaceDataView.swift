// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import SwiftUI

/// 地表データの選択画面。方位盤の左下の地表データのボタンから開く。方位盤の背景に描くものを、「表示しない」、
/// オフラインデータ(yamamuki-data の地表データ)、オンラインデータ(地理院タイル)から選ぶ(`SurfaceDataSection`)。
struct SurfaceDataView: View {
    @ObservedObject var model: DialModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                SurfaceDataSection(surface: model.surface, settings: model.settings)
            }
            .navigationTitle(Strings.text("surface_data_section_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(Strings.text("common_close")) { dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}
