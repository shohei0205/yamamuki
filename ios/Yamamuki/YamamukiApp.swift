// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import SwiftUI

@main
struct YamamukiApp: App {
    @StateObject private var model = DialModel()

    var body: some Scene {
        WindowGroup {
            DialView(model: model)
        }
    }
}
