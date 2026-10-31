import SwiftUI

@main
struct DekhoApp: App {
    @StateObject private var tv = TV()

    var body: some Scene {
        WindowGroup {
            RemoteView()
                .environmentObject(tv)
                .preferredColorScheme(.dark)
        }
    }
}
