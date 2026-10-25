// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import GoogleMobileAds
import SwiftUI
import UIKit

/// 広告(Google AdMob)。広告を出すのは Release だけ(ビルド設定 ADS_ENABLED。ID は Ads.xcconfig)。
enum Ads {
    /// このビルドで広告を出すか。Swift Package は構成ごとに外せないので、SDK は常に入れて、出すかどうかをここで決める。
    static let enabled = (Bundle.main.object(forInfoDictionaryKey: "YamamukiAdsEnabled") as? String) == "YES"
    static let bannerUnitID = Bundle.main.object(forInfoDictionaryKey: "YamamukiAdMobBannerID") as? String

    private static var started = false

    /// AdMob の SDK を一度だけ起動する。
    static func startIfNeeded() {
        guard !started else { return }
        started = true
        MobileAds.shared.start(completionHandler: nil)
    }
}

/// 方位盤のヘッダーに出すバナー広告(320×50)。
/// 読み込めないとき(圏外など)は何も描かないので、後ろのヘッダーの絵がそのまま見える。
struct AdBanner: UIViewRepresentable {
    func makeUIView(context: Context) -> BannerView {
        Ads.startIfNeeded()
        let banner = BannerView(adSize: AdSizeBanner)
        banner.adUnitID = Ads.bannerUnitID
        // 広告をタップしたときの画面を出す元。山むきの画面は 1 つなので、最初のウィンドウの根元の画面を使う。
        banner.rootViewController = UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.windows.first?.rootViewController }
            .first
        banner.load(Request())
        return banner
    }

    func updateUIView(_ uiView: BannerView, context: Context) {}
}
