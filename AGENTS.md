# 開発ルール（Claude / Codex 共通）

このリポジトリで AI エージェント（Claude Code、Codex など）が作業するときの共通ルール。人が作業するときも同じルールに従う。
アプリの機能・構成・ビルド手順の詳細は [README.md](README.md) にある。ここには作業の進め方だけを書く。

## プロジェクトの概要

- スマホを向けた方向に見える山を山名付きで表示する Android / iOS アプリ「山むき」。
- Android 版は Kotlin + Jetpack Compose、iOS 版は Swift + SwiftUI（iOS 15 以上、XcodeGen）。
- 山データは OpenStreetMap の Overpass API から取得し、端末内にキャッシュしてオフラインでも使う。

| ディレクトリ | 中身 |
|---|---|
| `core/` | Android に依存しないロジック（Kotlin）。単体テストあり |
| `app/` | Android アプリ（画面、Room のキャッシュ、設定） |
| `ios/YamamukiCore/` | `core/` を Swift に移植したパッケージ。単体テストあり |
| `ios/Yamamuki/` | iOS アプリ（画面、位置と方位の取得、設定） |
| `ios/project.yml` | XcodeGen の設定。`ios/Yamamuki.xcodeproj` はここから生成する |

## 言葉づかい

- ユーザーとのやり取り、コミットメッセージ、PR のタイトルと説明、コードのコメント、画面の文言は日本語で書く。
- 文体は「〜する」の常体。短く、専門用語より普段の言葉を選ぶ。
- コミットと PR のタイトルは、何が変わるかを一文で書く（例:「双眼鏡をタップすると現在地の緯度経度と標高を表示する」）。

## Android と iOS をそろえる

- 機能の追加や不具合の修正は、特に指示がなければ Android と iOS の両方に同じ動作で入れる。片方だけにするときは PR の説明にそう書く。
- `core/`（Kotlin）と `ios/YamamukiCore/`（Swift）は同じロジックの移植。片方を変えたらもう片方も同じに変え、テストも両方に足す。
- 両 OS の違いは端末の機能による差だけにする。違いを増やしたら README の「Android 版と iOS 版の違い」を更新する。

## 変更の確かめ方

コミットの前に、変えた部分に応じて次を通す。CI（`.github/workflows/`）でも同じものを動かしている。

```bash
# Android: core の単体テストとアプリのビルド
./gradlew -p core test
./gradlew :app:assembleDebug

# iOS: core の単体テスト（Mac または Swift の入った環境）
(cd ios/YamamukiCore && swift test)

# iOS: アプリのビルド（Mac のみ）
cd ios && xcodegen generate && xcodebuild build -project Yamamuki.xcodeproj -scheme Yamamuki \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
```

- 手元で動かせないもの（Mac が無いときの iOS ビルドなど）は、PR の CI で確かめ、PR の説明に「CI で確認」と書く。
- ロジックを変えたら単体テストを足す。テストを消したり飛ばしたりして通すことはしない。
- 画面を変えたら、変更前と変更後のスクリーンショットを PR に貼る（撮れないときはそう書く）。

## ブランチと PR

- `main` に直接 push しない。作業ブランチを切って PR を出す。
- 1 つの PR には 1 つの目的だけを入れる。ついでのリファクタリングや整形は別の PR にする。
- PR の説明は `.github/pull_request_template.md` の見出し（何を変えたか・対象・確認方法・スクリーンショット）に沿って書く。
- マージはユーザーが行う（スカッシュマージ）。エージェントは PR をマージしない。
- Claude と Codex が並行して作業するときは、それぞれ別のブランチを使い、相手のブランチには push しない。同じファイルを大きく変える作業は同時に進めない。

## ドキュメント

- 画面・設定項目・キャッシュの仕組み・ビルド手順を変えたら、同じ PR で README.md も更新する。
- このファイルのルールを変えたら、同じ PR で理由を説明する。

## やってはいけないこと

- 生成物や手元の設定をコミットしない（`ios/Yamamuki.xcodeproj/`、`local.properties`、`build/` など。`.gitignore` を参照）。
- 署名鍵（`*.jks`、`*.keystore`、`*-release.properties`）や API キーなどの秘密情報をコミットしない。
- アプリの版（Android の `versionCode` / `versionName`、iOS の `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`）は、ユーザーの指示があるときだけ上げる。上げるときは両 OS をそろえる。
- 依存ライブラリの更新は、機能の変更と同じ PR に混ぜない。

## OpenStreetMap のデータ

- Overpass API は共有の無料サービス。問い合わせの回数と範囲を増やす変更は避け、増えるときは PR の説明に理由と量の目安を書く。
- 山データは ODbL で利用している。アプリと README の「© OpenStreetMap contributors」の表示を消さない。
