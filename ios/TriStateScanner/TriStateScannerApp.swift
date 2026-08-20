import SwiftUI

@main
struct TriStateScannerApp: App {
    @StateObject private var model = ScannerViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
        }
    }
}
