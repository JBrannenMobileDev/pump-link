// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PumpPeripheral",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "PumpPeripheral",
            path: "Sources"
        ),
    ]
)
