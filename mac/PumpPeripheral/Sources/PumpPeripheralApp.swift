import SwiftUI

@main
struct PumpPeripheralApp: App {
    @StateObject private var peripheral = PeripheralController()

    var body: some Scene {
        WindowGroup {
            ContentView(peripheral: peripheral)
        }
    }
}
