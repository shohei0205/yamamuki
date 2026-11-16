// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import SwiftUI

/// 地表データの選択画面。山データの選択画面(`PeakDataListView`)とは別に、設定画面の「地表データ」の行から開く。
/// 取得済みの種類と、カタログで取得できる種類を並べ、選ぶとその種類を使う(取得していなければダウンロードするかを聞く)。
struct SurfaceDataView: View {
    @ObservedObject var model: DialModel

    var body: some View {
        Form {
            SurfaceDataSection(surface: model.surface, selected: model.settings.surfaceDataset)
        }
        .navigationTitle(Strings.text("surface_data_section_title"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
