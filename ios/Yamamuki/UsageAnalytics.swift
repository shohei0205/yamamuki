// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import FirebaseCore
import Foundation
import os

/// 利用状況の計測(Google アナリティクス for Firebase)。初回起動(first_open)を Google 広告のコンバージョンに使う(docs/analytics.md)。
/// - 計測するのは Release だけ(ビルド設定 ANALYTICS_ENABLED)。開発中の起動は数えない。
/// - 設定ファイル GoogleService-Info.plist はリポジトリに入れない。無いビルド(CI など)では何もしない。
/// - 送るのは SDK が自動で記録するイベントだけで、独自のイベントや位置情報は送らない。IDFA は使わない(FirebaseAnalyticsCore)。
enum UsageAnalytics {
    static let enabled = (Bundle.main.object(forInfoDictionaryKey: "YamamukiAnalyticsEnabled") as? String) == "YES"

    private static let logger = Logger(subsystem: "io.github.shohei0205.yamamuki", category: "UsageAnalytics")

    /// 起動時に一度だけ呼ぶ。
    static func start() {
        guard enabled else { return }
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else {
            logger.notice("GoogleService-Info.plist が無いので、利用状況の計測を始めない")
            return
        }
        FirebaseApp.configure()
    }
}
