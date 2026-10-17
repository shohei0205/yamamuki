// swift-tools-version:5.9
import PackageDescription

// iOS に依存しない山データのロジック(Android 版の core/ に相当)。`swift test` で単体テストできる。
let package = Package(
    name: "YamamukiCore",
    platforms: [.iOS("26.0"), .macOS(.v13)],
    products: [
        .library(name: "YamamukiCore", targets: ["YamamukiCore"]),
    ],
    targets: [
        .target(name: "YamamukiCore"),
        .testTarget(name: "YamamukiCoreTests", dependencies: ["YamamukiCore"]),
    ]
)
