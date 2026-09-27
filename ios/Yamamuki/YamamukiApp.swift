import SwiftUI

@main
struct YamamukiApp: App {
    @State private var model = DialModel()

    var body: some Scene {
        WindowGroup {
            DialView(model: model)
        }
    }
}
