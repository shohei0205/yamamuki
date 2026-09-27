# yamamuki (iOS 版)

Android 版(リポジトリ直下の `app/`)とほぼ同じ機能の iOS アプリ。スマホを向けた方向に見える山を、山名付きの方位盤で表示する。

- Swift + SwiftUI、iOS 17 以上(iPhone、縦向き固定)
- 山データは Android 版と同じく OpenStreetMap の Overpass API から取得し、端末内にキャッシュしてオフラインでも使えるようにする

## 構成

- `YamamukiCore/` iOS に依存しないロジックの Swift パッケージ(Android 版の `core/` を移植)。Overpass API の問い合わせ・解析、距離と方位の計算、方位盤の幾何、タイル単位のキャッシュ方針。キャッシュはタイルごとの JSON ファイル(`FileMountainCache`)に保存する。
- `Yamamuki/` アプリ本体。方位盤の描画(`DialCanvasView`)、画面(`DialView`)、設定(`SettingsView`)、現在地と方位の取得(`LocationService`)。
- `project.yml` Xcode プロジェクトの設定([XcodeGen](https://github.com/yonaskolb/XcodeGen) 用)。`Yamamuki.xcodeproj` はここから生成し、git には入れない。

## 開発環境

| 必要なもの | バージョン |
|---|---|
| macOS + Xcode | Xcode 16 以上 |
| XcodeGen | `brew install xcodegen` |
| 実行する端末 | iOS 17 以上の iPhone(方位センサーのためシミュレーターでは方位が動かない) |

iOS アプリのビルドと端末への転送には Mac が必要。Mac が無い場合も、GitHub Actions(`.github/workflows/ios.yml`)でビルドが通るかは確認できる。

## ビルドと実行

```bash
cd ios

# core の単体テスト(Mac。Linux の Swift でも動く)
(cd YamamukiCore && swift test)

# Xcode プロジェクトを生成して開く
xcodegen generate
open Yamamuki.xcodeproj
```

- Xcode で `Yamamuki` ターゲットの「Signing & Capabilities」に自分の Apple ID のチームを選ぶと、USB でつないだ iPhone で実行できる(無料の Apple ID でも 7 日間有効な開発用署名で入れられる)。
- iPhone 側で「設定 > プライバシーとセキュリティ > デベロッパモード」をオンにする必要がある。
- 山データの取得に失敗したときは、原因を Xcode のコンソール(またはMac の「コンソール」アプリ)にカテゴリ `DialModel` で出している。

## Android 版との違い

| 項目 | Android 版 | iOS 版 |
|---|---|---|
| 方位 | 回転ベクトルセンサーから計算し、偏角(GeomagneticField)を足して真北に直す | Core Location の heading。iOS が偏角を補正した真方位を返す |
| 現在地の標高 | GPS の楕円体高を Android 14 以降のジオイドモデルで海抜に直す | Core Location の altitude(海抜)をそのまま使う |
| キャッシュ | Room(SQLite) | タイルごとの JSON ファイル(アプリの Application Support 内) |
| 設定の保存 | SharedPreferences | UserDefaults |
| 山の詳細 | ダイアログ | 下から出るシート |

画面の構成、設定項目、キャッシュの仕組み、通信量はリポジトリ直下の [README](../README.md) と同じ。
