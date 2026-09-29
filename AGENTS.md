# 開発ルール（Claude / Codex 共通）

このリポジトリで AI エージェント（Claude Code、Codex など）が作業するときの共通ルール。人が作業するときも同じルールに従う。
アプリの機能・構成・ビルド手順の詳細は [README.md](README.md) にある。ここには作業の進め方だけを書く。

## プロジェクトの概要

- スマホを向けた方向に見える山を山名付きで表示する Android / iOS アプリ「山むき」。
- Android 版は Kotlin + Jetpack Compose、iOS 版は Swift + SwiftUI（XcodeGen。対応する iOS の版は `ios/project.yml` の `deploymentTarget` を見る）。
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
- 文体は決めない。簡潔で分かりやすく書き、専門用語より普段の言葉を選ぶ。
- コミットと PR のタイトルは、何が変わるかを一文で書く（例:「双眼鏡をタップすると現在地の緯度経度と標高を表示する」）。

## コミットメッセージ

- タイトルの先頭に、Git で一般的に使われるプレフィックス（[Conventional Commits](https://www.conventionalcommits.org/ja/) の型）を付ける。書式は `<プレフィックス>: <日本語の説明>`。
- PR はスカッシュマージするので、PR のタイトルにも同じプレフィックスを付ける（マージ後のコミットのタイトルになる）。

| プレフィックス | 使うとき | 例 |
|---|---|---|
| `feat` | 機能の追加・変更 | `feat: 双眼鏡をタップすると現在地の緯度経度と標高を表示する` |
| `fix` | 不具合の修正 | `fix: 圏外で起動すると方位盤が空になるのを直す` |
| `docs` | ドキュメントだけの変更 | `docs: README に iOS のビルド手順を足す` |
| `style` | 動作が変わらない書式の変更（インデントなど） | `style: Kotlin のインデントをそろえる` |
| `refactor` | 動作を変えないコードの整理 | `refactor: 重複した距離の計算をまとめる` |
| `perf` | 速さや電池・通信量の改善 | `perf: 方位の更新で無駄な再描画を省く` |
| `test` | テストだけの追加・修正 | `test: タイルの境界のテストを足す` |
| `build` | ビルド設定・依存ライブラリ・版の変更 | `build: AGP と依存ライブラリを更新する` |
| `ci` | GitHub Actions の変更 | `ci: iOS のビルドに Xcode 16.4 を使う` |
| `chore` | 上のどれにも当たらない雑務 | `chore: .gitignore に DerivedData を足す` |

- Android か iOS の片方だけの変更なら、スコープを付けてもよい（例: `fix(ios): 設定画面の文字が切れるのを直す`）。
- 互換性を壊す変更（保存データの形式が変わり、キャッシュが読めなくなるなど）は、プレフィックスの後に `!` を付け（例: `feat!:`）、本文にその影響を書く。

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

## AI が投稿するコメント

- AI が GitHub に直接投稿するコメント（PR・Issue のコメント、レビュー、レビューへの返信）は、見出し（1 行目）の先頭に、書いた AI のモデル名が分かるプレフィックスを `[モデル名]` の形で付ける。
  - 例: `[Claude Opus 5.5] CI の失敗を直しました`、`[Codex GPT-5] レビューの指摘に対応しました`
- モデル名は、そのとき実際に動いているモデルを書く。分からないときはツール名（`[Claude Code]`、`[Codex]`）だけでもよい。

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
