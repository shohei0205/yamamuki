# 利用状況の計測（iOS 版）

iOS 版の配布版では、Google アナリティクス for Firebase で初回起動（`first_open`）を数え、Google 広告のアプリ キャンペーンのコンバージョンに使う（Issue #217）。Android は Play Console と Google 広告のリンクでインストールを数えられるので、入れていない。

## しくみ

- 計測するのは Release だけ（ビルド設定 `ANALYTICS_ENABLED`）。開発中の起動は数えない。
- 起動時に `UsageAnalytics.start()`（`ios/Yamamuki/UsageAnalytics.swift`）が `FirebaseApp.configure()` を呼ぶ。
- 設定ファイル `GoogleService-Info.plist` はリポジトリに入れない（`.gitignore` 済み）。Firebase コンソールからダウンロードし、`ios/Yamamuki/GoogleService-Info.plist` に置く。無いビルド（CI など）では計測を始めず、ログ（カテゴリ `UsageAnalytics`）に出すだけにする。
- 送るのは SDK が自動で記録するイベント（`first_open`・`session_start`・`user_engagement` など）だけ。独自のイベントは送らない。位置情報は渡さない。
- IDFA を使わない製品 `FirebaseAnalyticsCore` を使う。トラッキングの許可（ATT）は求めない。
- 広告のオンデバイス計測（`GoogleAdsOnDeviceConversion`）は `FirebaseAnalyticsCore` に入っていないので、別に足している。版は Firebase（GoogleAppMeasurement）が対応する版に決める。メールアドレスなどを使う「ファースト パーティ データ版」は使わない。
- `Info.plist` で次を止めている。
  - `GOOGLE_ANALYTICS_DEFAULT_ALLOW_AD_PERSONALIZATION_SIGNALS`: 広告のパーソナライズ（リマーケティングなど）に使わせない。
  - `FirebaseAutomaticScreenReportingEnabled`: 画面の表示（`screen_view`）を自動で記録しない。
- Analytics はコードから直接呼ばないので、リンカーのフラグ `-ObjC`（`ios/project.yml` の `OTHER_LDFLAGS`）が無いとリンクされず、計測が始まらない。
- Firebase の 13 系は Xcode 26.2 以上が要る。

## 動作の確かめ方

Release でビルドし、起動時の引数 `-FIRDebugEnabled` を付けて起動すると、イベントが Firebase コンソールの DebugView に出る。シミュレータでは、署名せずに（`CODE_SIGNING_ALLOWED=NO`）ビルドするとキーチェーンを使えず、Installation ID の取得に失敗するので、署名してビルドする。

```bash
cd ios
xcodebuild build -project Yamamuki.xcodeproj -scheme Yamamuki -configuration Release \
  -destination 'id=<シミュレータの UDID>' -derivedDataPath build DEVELOPMENT_TEAM=<Team ID>
xcrun simctl install <シミュレータの UDID> build/Build/Products/Release-iphonesimulator/Yamamuki.app
xcrun simctl launch <シミュレータの UDID> io.github.shohei0205.yamamuki -FIRDebugEnabled
```

記録したイベントは、アプリのデータの `Library/Application Support/Google/Measurement/google-app-measurement.sql` の `events` テーブルでも見られる（`_f` が `first_open`）。

## 人が行う作業

1. Firebase コンソールでプロジェクトを作り、Google アナリティクスを有効にする。
2. iOS アプリ（バンドル ID `io.github.shohei0205.yamamuki`、App Store ID）を登録し、`GoogleService-Info.plist` をダウンロードして上の場所に置く。
3. Firebase の「プロジェクトの設定 > 統合 > Google 広告」で Google 広告とリンクする。
4. 新しい版を配布し、`first_open` が GA4 に届いてから、Google 広告の「目標 > コンバージョン」で iOS の `first_open` を取り込み、「ダウンロード」の主要なアクションにする。
5. App Store Connect の「App のプライバシー」に、計測で集めるデータを足す。
