# yamamuki

スマホを向けた方向に見える山を、山名付きで表示する Android / iOS アプリ。山をタップすると標高などの詳細が見られる。

- Android 版: Kotlin + Jetpack Compose
- iOS 版: Swift + SwiftUI（Android 版とほぼ同じ機能）
- 山データは OpenStreetMap の Overpass API から取得し、端末内にキャッシュしてオフラインでも使えるようにする

## 構成

- `core/` Android に依存しないデータ取得ロジック（Overpass API の問い合わせ・解析、距離と方位の計算、キャッシュ方針）。単体でテストできる。
- `app/` Android アプリ。Room によるキャッシュ実装、設定、画面。
- `ios/YamamukiCore/` iOS に依存しないロジックの Swift パッケージ。`core/` を Swift に移植したもので、単体でテストできる。キャッシュはタイルごとの JSON ファイル（`FileMountainCache`）に保存する。
- `ios/Yamamuki/` iOS アプリ。方位盤の描画（`DialCanvasView`）、画面（`DialView`）、設定（`SettingsView`）、現在地と方位の取得（`LocationService`）。
- `ios/project.yml` Xcode プロジェクトの設定（[XcodeGen](https://github.com/yonaskolb/XcodeGen) 用）。`ios/Yamamuki.xcodeproj` はここから生成し、git には入れない。

## 開発環境

### Android 版

| 必要なもの | バージョン |
|---|---|
| JDK | 17 以上（21 で動作確認） |
| Android SDK Platform | 36（`compileSdk` / `targetSdk`） |
| Android SDK Build-Tools | 36.0.0（`buildToolsVersion` で固定） |
| Gradle | 8.14.3（`gradlew` が自動でダウンロードする） |
| 実行する端末 | Android 8.0 (API 26) 以上。現在地の標高表示は Android 14 以上 |

- Android SDK の場所は、環境変数 `ANDROID_HOME` か、リポジトリ直下の `local.properties`（git 管理外）で指定する。
  ```properties
  sdk.dir=C\:\\Users\\<ユーザー名>\\AppData\\Local\\Android\\Sdk
  ```
- SDK のライセンスに未同意だとビルドが止まる。`sdkmanager --licenses` で同意しておく。
- Visual Studio に付属する SDK（`C:\Program Files (x86)\Android\android-sdk`）は書き込みできないため、ビルドのたびに「Probably the SDK is read-only」と出るが、ビルドには影響しない。足りないパッケージを Gradle が自動で入れられないので、必要なものは管理者権限の `sdkmanager` で入れる。

### iOS 版

| 必要なもの | バージョン |
|---|---|
| Mac + Xcode | Xcode 26 以上 |
| XcodeGen | `brew install xcodegen` で入れる |
| 実行する端末 | iOS 26 以上の iPhone（縦向き固定） |

- iOS アプリのビルドと iPhone への転送には Mac が必要。Mac が無くても、ビルドが通るかは GitHub Actions（`.github/workflows/ios.yml`）で確認できる。
- シミュレーターでも起動できるが、方位センサーが無いので方位盤は回らない。現在地はシミュレーターのメニュー（Features > Location）で指定する。

## ビルドと実行

### Android 版

```bash
# core の単体テスト
./gradlew -p core test

# デバッグ用 APK のビルド（app/build/outputs/apk/debug/app-debug.apk）
./gradlew :app:assembleDebug

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
- コマンドラインでビルドだけ確認するときは、CI と同じ次のコマンドを使う。
  ```bash
  xcodebuild build -project Yamamuki.xcodeproj -scheme Yamamuki \
    -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
  ```
- 山データの取得に失敗したときは、原因を Xcode のコンソール（または Mac の「コンソール」アプリ）にカテゴリ `DialModel` で出している。

## Android 版と iOS 版の違い

画面の構成、設定項目、キャッシュの仕組み、通信量は同じ。端末の機能に合わせて次の点が違う。

| 項目 | Android 版 | iOS 版 |
|---|---|---|
| 方位 | 回転ベクトルセンサーから計算し、偏角を足して真北に直す | Core Location の heading。iOS が偏角を補正した真方位を返す |
| 現在地の標高 | GPS の楕円体高を Android 14 以降のジオイドモデルで海抜に直す | Core Location の altitude（海抜）をそのまま使う |
| キャッシュ | Room（SQLite） | タイルごとの JSON ファイル（アプリの Application Support 内） |
| 設定の保存 | SharedPreferences | UserDefaults |
| 山の詳細 | ダイアログ | 下から出るシート |
| 位置情報を断ったとき | 許可を求め直す | 設定アプリを開くボタンを出す（iOS はアプリから再度聞けない） |

## 方位盤の画面

- 端末を向けている方位を上にした平面図に、山をアイコンと山名で描く。現在地は画面下部の双眼鏡のアイコンで、山頂にいるときは代わりに旗の立った山頂アイコンと山名を出す。アイコンは標高で描き分け、1000m 未満(標高不明を含む)は黄緑の丘、2000m 未満は緑で縁取った黄色の ▲、2000m 以上は雪をかぶった茶色の ▲(どれも同じ太さの縁取り)。距離は等倍で、灰色の同心円が距離の目安。
- 画面上部は方位の目盛り(幅 60°)と「北東 45°　標高 312m」のような表示。センサーは磁北基準なので、現在地の偏角を足して真北に直している。標高は GPS の高さ(楕円体高)を Android 14 以降のジオイドモデルで海抜に直したもので、求められないときは出さない。
- 端末を立てて構えたときは背面の向き、水平に持ったときは上端の向きを方位とする(途中の傾きでも連続)。
- ピンチで表示範囲(画面上端までの距離)を 2〜80km で変えられる。範囲に応じて山データを 20 / 50 / 120km の半径で取得する。
- 山が重なるときは標高の高い山を優先して表示する(上限は設定で変えられる)。
- 山をタップすると、山名・標高・緯度経度・現在地からの距離を表示する。
- 左下の歯車ボタンで設定画面を開く。その隣のダウンロードボタンで事前ダウンロードの画面を開く(ダウンロード中はボタンに進み具合を出す)。手動取得モードのときは、さらに山データを取得する更新ボタンが出る。

## 山データの事前ダウンロード

目的地が圏外になりそうなときに、電波の届く場所で都道府県単位の山データを前もって保存しておく機能。

- 都道府県の一覧(地方ごと)から選ぶと、その都道府県を囲む矩形にかかるタイルを取得する。矩形は本土(県庁所在地を含む範囲)と、山のある主な離島(伊豆諸島・屋久島など)。隣の県の山も一部入る。範囲は `core/.../Prefecture.kt` と `ios/YamamukiCore/.../Prefecture.swift` にある。
- 2×2 タイル(1°四方)ずつ Overpass に問い合わせ、終わるたびに保存する。進み具合(区画数)を表示し、いつでも中断できる。問い合わせが失敗したら 5・15・30 秒あけて取り直し、それでも失敗したら知らせる。中断や通信の失敗のあとは、取得済みの区画を飛ばして続きから再開できる。画面を閉じてもダウンロードは続き、アプリを終了して止まったときは、次に開いたときに「続きから再開」を出す。
- 区画を書き込むたびに、方位盤は通信せずに保存済みのデータを読み直す。
- 保存済みの地域は一覧で取得日・山の数を確認でき、更新(取り直し)と削除ができる。削除は圏外でもできる。削除しても、ほかの保存済みの地域と重なる区画は残す。
- 保存済みの地域と、途中で終わった地域は、設定の「キャッシュを消去」でも消さない。
- データは通常のキャッシュと同じタイルに入るので、方位盤の表示はそのまま保存済みのデータを使う。
- 北海道は広い(1 つでは約 130 区画)ので、道央・道南・道北・道東の 4 つに分けている(それぞれ 16〜48 区画)。
- 量の目安(推定): 長野県で 20 区画・数百 KB。
- 周辺の山だけを取得するライト版を作るときは、`Features.AREA_DOWNLOAD`(Android、`app/.../Features.kt`)と `Features.areaDownload`(iOS、`ios/Yamamuki/Features.swift`)を false にすると、ボタンと画面が出なくなる。

## 設定

| 項目 | 内容 | 既定 |
|---|---|---|
| 最低標高 | この標高以上の山だけ表示する（0〜3,000m）。絞り込み中は標高不明の山を出さない | 0m（すべて） |
| 表示する山の上限 | 一度に表示する山の数（10〜100） | 40 |
| 文字の大きさ | 小・標準・大・特大 | 標準 |
| 起動時の表示範囲 | 5〜50km | 15km |
| 画面を常に点灯 | 方位盤の表示中は画面を消さない | オフ |
| 山データを手動で取得 | 自動では通信せず、更新ボタンを押したときだけ今の表示範囲を取得する | オフ |
| 取得したデータを使う期間 | この期間を過ぎた地域は取り直す（7日〜1年） | 30日 |
| キャッシュ | 保存している山の件数・容量の確認と消去 | — |

設定は端末内（Android は SharedPreferences、iOS は UserDefaults）に保存する。

初回起動時は、位置情報の許可より先に「山データを自動で取得してよいか」を聞き、答えるまでは通信しない。「いいえ」なら手動取得モードで起動する。

## キャッシュの仕組み

緯度経度 0.5° 四方のタイル単位で「取得済みか・いつ取得したか」を記録する。現在地の周辺で未取得、または設定の期間(既定 30 日)より古いタイルだけを Overpass に問い合わせ、通信できないときはキャッシュ済みのデータで表示する。圏外や機内モードのときは通信を試さずにキャッシュで表示し(画面上部に「圏外」と出す)、つながったら控えていた分を自動で取得する。通信に失敗したときは画面中央で知らせ、その場で再取得できる。Overpass には、使う列(ID・緯度経度・名前・標高)だけをタブ区切りで返させて、通信量を抑えている。

## 通信量の目安

1 回の取得で受信する量（gzip 圧縮後）。関東の低山が点在する地域で 2026 年 9 月に測ったもの。

| 取得半径 | 問い合わせる範囲 | 山の数 | 受信量 |
|---|---|---|---|
| 20km | 1.0° × 1.0° | 約 500 | 約 13 KB（実測） |
| 50km（起動時の標準） | 1.5° × 1.5° | 約 1,200 | 約 33 KB（推定） |
| 120km（最大に縮小したとき） | 2.5° × 3.0° | 約 2,400 | 約 65 KB（推定） |

- 推定値は、20km の実測値と、以前の JSON 形式で測った山の数・サイズの比から出したもの。送信は問い合わせ文の数百バイトと、接続ごとの TLS のやり取り数 KB。
- 取得済みの地域では、設定の期間内は通信しない。初めての地域で起動したときに数十 KB、同じ地域で使い続けるなら期間が切れるまでほぼ 0 なので、日常的な利用なら月に数百 KB 程度。
- 山の数は地域で大きく変わり、山の多い地域(日本アルプスなど)では上の表の 2〜3 倍になることがある。
- 山の中で使うときは、電波の届く場所で最大まで縮小して取得しておく(半径 120km 分)と、圏外でも表示できる。

## ライセンス

ソースコードは [MIT License](LICENSE) で公開する。

アプリが表示する山データは OpenStreetMap のもので、コードとは別に [ODbL](https://opendatacommons.org/licenses/odbl/) の条件で利用している。

山データ © OpenStreetMap contributors (ODbL)
