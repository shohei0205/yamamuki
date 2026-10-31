# <img src="docs/images/app-icon.png" width="40" alt="" align="top"> 山むき

スマホを向けた方向に見える山を、山名付きで表示する Android / iOS アプリ。山をタップすると標高などの詳細が見られる。

- Android 版: Kotlin + Jetpack Compose
- iOS 版: Swift + SwiftUI（Android 版とほぼ同じ機能）
- 山データは OpenStreetMap のデータから作った全国の山頂データを [yamamuki-data](https://github.com/shohei0205/yamamuki-data) から取得し、端末内に保存してオフラインでも使えるようにする

| ヘディングアップモード | 手動位置モード |
|---|---|
| <img src="docs/images/heading-up-kamikochi.png" width="270" alt="上高地から北へ向けた方位盤。槍ヶ岳・中岳・北穂高岳・奥穂高岳などが並ぶ"> | <img src="docs/images/manual-okuhotaka.png" width="270" alt="奥穂高岳の山頂を中心にした地図。槍ヶ岳・常念岳・焼岳などが周りに並ぶ"> |

## 構成

- `android/` Android 版の Gradle プロジェクト。Android Studio ではこのフォルダを開く。
- `android/core/` Android に依存しないデータ取得ロジック（配布データの取得・検証・取り込み、距離と方位の計算、キャッシュ方針。使っていない Overpass API の問い合わせ・解析も残している）。単体でテストできる。
- `android/app/` Android アプリ。Room によるキャッシュ実装、設定、画面。
- `ios/YamamukiCore/` iOS に依存しないロジックの Swift パッケージ。`android/core/` を Swift に移植したもので、単体でテストできる。キャッシュはタイルごとの JSON ファイル（`FileMountainCache`）に保存する。
- `ios/Yamamuki/` iOS アプリ。方位盤の描画（`DialCanvasView`）、画面（`DialView`）、設定（`SettingsView`）、現在地と方位の取得（`LocationService`）。画面の文言は `Localizable.xcstrings` にまとめ、Android の `android/app/src/main/res/values/strings.xml` と同じキー名にしている。
- `ios/YamamukiUITests/` iOS アプリの UI テスト（XCUITest）。アプリを起動して方位盤と設定の画面を開き、画面を撮る。
- `ios/project.yml` Xcode プロジェクトの設定（[XcodeGen](https://github.com/yonaskolb/XcodeGen) 用）。`ios/Yamamuki.xcodeproj` はここから生成し、git には入れない。
- `docs/` アプリの詳しい仕様（下の「詳しい仕様」）と、開発中に分かったことの記録。README の画像も置く。
- `site/` GitHub Pages で公開するページ（プライバシーポリシー `site/privacy/index.html` → https://shohei0205.github.io/yamamuki/privacy/ ）と、山データの参照先ファイル（`site/data/osm-peaks/`）。main に入ると `.github/workflows/pages.yml` で公開する（Settings → Pages の Source は「GitHub Actions」）。

## 開発環境

### Android 版

| 必要なもの | バージョン |
|---|---|
| JDK | 17 以上（21 で動作確認） |
| Android SDK Platform | 36（`compileSdk` / `targetSdk`） |
| Android SDK Build-Tools | 36.0.0（`buildToolsVersion` で固定） |
| Gradle | 8.14.3（`gradlew` が自動でダウンロードする） |
| 実行する端末 | Android 8.0 (API 26) 以上。現在地の標高表示は Android 14 以上 |

- Android SDK の場所は、環境変数 `ANDROID_HOME` か、`android/local.properties`（git 管理外）で指定する。
  ```properties
  sdk.dir=C\:\\Users\\<ユーザー名>\\AppData\\Local\\Android\\Sdk
  ```
- SDK のライセンスに未同意だとビルドが止まる。`sdkmanager --licenses` で同意しておく。
- Visual Studio に付属する SDK（`C:\Program Files (x86)\Android\android-sdk`）は書き込みできないため、ビルドのたびに「Probably the SDK is read-only」と出るが、ビルドには影響しない。足りないパッケージを Gradle が自動で入れられないので、必要なものは管理者権限の `sdkmanager` で入れる。

### iOS 版

| 必要なもの | バージョン |
|---|---|
| Mac + Xcode | Xcode 16 以上（16.4 で動作確認） |
| XcodeGen | `brew install xcodegen` で入れる |
| 実行する端末 | iOS 15 以上の iPhone（縦向き固定） |

- iOS アプリのビルドと iPhone への転送には Mac が必要。Mac が無くても、ビルドが通るかは GitHub Actions（`.github/workflows/ios.yml`）で確認できる。
- シミュレーターでも起動できるが、方位センサーが無いので方位盤は回らない。現在地はシミュレーターのメニュー（Features > Location）で指定する。

## ビルドと実行

### Android 版

Gradle のコマンドは `android/` で実行する。

```bash
cd android

# core の単体テスト
./gradlew -p core test

# デバッグ用 APK のビルド（android/app/build/outputs/apk/debug/app-debug.apk）
./gradlew :app:assembleDebug

# アプリの単体テスト（DB の移行など。Robolectric で動かす）
./gradlew :app:testDebugUnitTest

# USB でつないだ端末にインストール
./gradlew :app:installDebug
```

- 端末側で「開発者向けオプション」の「USB デバッグ」を有効にし、つないだときに出る「USB デバッグを許可しますか？」で許可する。
- つながっているかは `adb devices` で確認する（`device` と出れば OK、`unauthorized` なら端末で許可する）。PowerShell で adb をフルパスで実行するときは、先頭に `&` を付ける。
  ```powershell
  & "C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe" devices
  ```
- 山データの取得に失敗したときは、原因を logcat にタグ `DialViewModel` で出している。
- 開発版はアプリ ID が `io.github.shohei0205.yamamuki.debug`、名前が「山むき(開発版)」になり、配布版と同じ端末に並べて入れられる。
- 広告(AdMob)の SDK は配布版(release)にだけ入る。開発版でも試すときは `-Pads=all`、どちらにも入れないときは `-Pads=none` を付ける([docs/ads.md](docs/ads.md) を参照)。
- 配布版(release)は R8 で使わないコードとリソースを削って小さくする。規則は `android/app/proguard-rules.pro` に書く。難読化の対応表(`android/app/build/outputs/mapping/release/mapping.txt`)は AAB に入るので、Play Console のクラッシュの記録も元のクラス名で読める。

### iOS 版

```bash
cd ios

# core の単体テスト
(cd YamamukiCore && swift test)

# Xcode プロジェクトを生成して開く（project.yml を変えたら生成し直す）
xcodegen generate
open Yamamuki.xcodeproj
```

- Xcode で `Yamamuki` ターゲットの「Signing & Capabilities」の Team に自分の Apple ID を選ぶ。無料の Apple ID でも、自分の iPhone に 7 日間有効な開発用署名で入れられる（期限が切れたら Xcode から入れ直す）。
- iPhone を USB でつなぎ、Xcode 上部の実行先に選んで Run（⌘R）する。初回は iPhone の「設定 > プライバシーとセキュリティ > デベロッパモード」をオンにし、「設定 > 一般 > VPN とデバイス管理」で開発元を信頼する。
- コマンドラインでビルドだけ確認するときは、CI と同じ次のコマンドを使う。UI テストのコードも一緒にビルドする。
  ```bash
  xcodebuild build-for-testing -project Yamamuki.xcodeproj -scheme Yamamuki \
    -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
  ```
- UI テストは Xcode で ⌘U、またはコマンドラインで次のように動かす。実機でもシミュレータでも動く。
  ```bash
  # シミュレータ
  xcodebuild test -project Yamamuki.xcodeproj -scheme Yamamuki \
    -destination 'platform=iOS Simulator,name=<シミュレータ名>' CODE_SIGNING_ALLOWED=NO
  # 実機（UDID は xcrun devicectl list devices で調べる。Team ID は Xcode の Settings > Accounts で見る）
  xcodebuild test -project Yamamuki.xcodeproj -scheme Yamamuki \
    -destination 'id=<UDID>' -allowProvisioningUpdates DEVELOPMENT_TEAM=<Team ID>
  ```
  - 初回起動の山データのダウンロードの確認には「あとで」で答え(テストのたびにデータ本体を取らないため)、位置情報の許可には「使用中は許可」で答える。実機では端末に入っている山むきの設定をそのまま使う。
  - テストで撮った画面はテスト結果に残り、Xcode の Report ナビゲータで見られる。
  - テストが終わると、テストした端末で山むきを起動し直す（スキームのテスト後の処理）。
- 広告(AdMob)は Release だけで出す。Debug で試すときは、ビルド設定 `ADS_ENABLED` を `YES` にする([docs/ads.md](docs/ads.md) を参照)。
- 山データの取得に失敗したときは、原因を Xcode のコンソール（または Mac の「コンソール」アプリ）にカテゴリ `DialModel` で出している。

## 詳しい仕様

アプリの詳しい仕様は [docs/](docs/) に分けて書いている。

| ページ | 中身 |
|---|---|
| [Android 版と iOS 版の違い](docs/platform-differences.md) | 端末の機能による両 OS の違い（方位の求め方、キャッシュの保存先など） |
| [方位盤の画面](docs/dial-screen.md) | 山のアイコンと山名の描き方、表示する山の選び方、ヘディングアップと手動位置モードの操作 |
| [山の検索と検索位置モード](docs/search.md) | 山名の検索、選んだ山頂から見た方位盤 |
| [山データの取得](docs/peak-data.md) | 山データのダウンロード、更新の確認、読める形式、山データの種類の選択 |
| [山データのカタログと差し替え](docs/peak-data-catalog.md) | `site/data/` のカタログと参照先ファイル、差し替え PR の作り方と CI の確認 |
| [設定](docs/settings.md) | 設定項目と既定値 |
| [キャッシュの仕組みと通信量](docs/cache.md) | タイル単位のキャッシュ、自動バックアップ、通信量の目安 |
| [広告](docs/ads.md) | 配布版のヘッダーのバナー広告（AdMob）、広告を入れるビルドと広告の ID の渡し方 |
| [データの出典とライセンスの画面](docs/credits-screen.md) | 山データの出典の出し方 |
| [山データの取得で分かったこと](docs/peak-data-notes.md) | Overpass API から取得していたころの記録 |

## ライセンス

ソースコードは [MIT License](LICENSE) で公開する。著作権者の「The yamamuki Authors」は [AUTHORS.md](AUTHORS.md) に載せた開発者を指す。ソースコードの各ファイルの先頭には、著作権とライセンスを [SPDX](https://spdx.dev/) の形式（`SPDX-FileCopyrightText` と `SPDX-License-Identifier`）で書いている。

アプリ名「山むき」とアプリアイコン（`android/app/src/main/res/drawable/ic_launcher_*.xml`、`ios/Yamamuki/Assets.xcassets/AppIcon.appiconset/`、`docs/images/app-icon.png`）は MIT License の対象外とする。コードを使って別のアプリを配布するときは、別の名前とアイコンを使う。利用者が公式のアプリと取り違えないようにするため。

アプリが表示する山データは、OpenStreetMap のもの（既定）と国土地理院のものから選ぶ。コードとは別に、OpenStreetMap のものは [ODbL](https://opendatacommons.org/licenses/odbl/)、国土地理院のものは [公共データ利用規約（PDL 1.0）](https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html) の条件で利用している。方位盤の右下には、使っている種類の短い表示（「© OpenStreetMap contributors」か「出典：国土地理院」）を出す。

山データ © OpenStreetMap contributors (ODbL)

山データ 出典：国土地理院 (PDL 1.0)
