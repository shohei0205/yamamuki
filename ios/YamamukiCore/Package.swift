// swift-tools-version:5.9
// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import PackageDescription

// iOS に依存しない山データのロジック(Android 版の core/ に相当)。`swift test` で単体テストできる。
let package = Package(
    name: "YamamukiCore",
    platforms: [.iOS(.v15), .macOS(.v13)],
    products: [
        .library(name: "YamamukiCore", targets: ["YamamukiCore"]),
    ],
    targets: [
        .target(name: "YamamukiCore"),
        .testTarget(name: "YamamukiCoreTests", dependencies: ["YamamukiCore"]),
    ]
)
