# 開発ルール（Claude / Codex 共通）

このリポジトリで AI エージェント（Claude Code、Codex など）が作業するときの共通ルール。人が作業するときも同じルールに従う。
アプリの機能・構成・ビルド手順の詳細は [README.md](README.md) にある。ここには作業の進め方だけを書く。

## プロジェクトの概要

- スマホを向けた方向に見える山を山名付きで表示する Android / iOS アプリ「山むき」。
- Android 版は Kotlin + Jetpack Compose、iOS 版は Swift + SwiftUI（XcodeGen。対応する iOS の版は `ios/project.yml` の `deploymentTarget` を見る）。
- 山データは OpenStreetMap のデータから作った全国の山頂データを yamamuki-data（GitHub Pages と Releases）から取得し、端末内にキャッシュしてオフラインでも使う。Overpass API から取得するコードは残っているが使っていない。

| ディレクトリ | 中身 |
|---|---|
| `android/` | Android 版の Gradle プロジェクト。Android Studio ではこのフォルダを開く |
| `android/core/` | Android に依存しないロジック（Kotlin）。単体テストあり |
| `android/app/` | Android アプリ（画面、Room のキャッシュ、設定） |
| `ios/YamamukiCore/` | `android/core/` を Swift に移植したパッケージ。単体テストあり |
| `ios/Yamamuki/` | iOS アプリ（画面、位置と方位の取得、設定） |
| `ios/YamamukiUITests/` | iOS アプリの画面を操作する UI テスト（XCUITest） |
| `ios/project.yml` | XcodeGen の設定。`ios/Yamamuki.xcodeproj` はここから生成する |
| `site/` | GitHub Pages で公開するページ（プライバシーポリシー）。main に入ると `.github/workflows/pages.yml` で公開する |

## 言葉づかい

- ユーザーとのやり取り、コミットメッセージ、PR のタイトルと説明、コードのコメント、画面の文言は日本語で書く。
- 文体は決めない。簡潔で分かりやすく書き、専門用語より普段の言葉を選ぶ。
- コミットと PR のタイトルは、何が変わるかを一文で書く（例:「双眼鏡をタップすると現在地の緯度経度と標高を表示する」）。
- 画面に出す文言はコードに直接書かず、Android は `android/app/src/main/res/values/strings.xml`、iOS は `ios/Yamamuki/Localizable.xcstrings` に書き、コードからはキーで引く（iOS は `Strings.text` / `Strings.format`）。
  - キー名は両 OS で同じにし、同じキーには同じ文言を入れる。OS で文言を変えるときは別のキーにする。片方の OS だけの文言は、`.github/scripts/check-ui-strings.py` の `PLATFORM_ONLY` に理由と一緒に書く。
  - core（`android/core/`・`ios/YamamukiCore/`）は画面の文言を持たない。方位の番号や待ち時間などの値を返し、アプリ側で文言にする。都道府県・地方の名前と、開発者向けのエラーの文は core に置いてよい。
  - ログの文と、プレビュー用の仮の文字列（行末に `// 文言チェック対象外`）は対象外。
- PR の説明や README などの文章と、アプリの画面に出す文言（Android は `android/app/src/main/res/values/strings.xml`、iOS は `ios/Yamamuki/Localizable.xcstrings`）を、自然で読みやすい表現に推敲するときは、スキル `yomiyasu`（`.agents/skills/yomiyasu/SKILL.md`）を使う。ただし、次の点はこのリポジトリの書き方を優先する。
  - 英単語や数字の前後の半角空白（「OpenStreetMap の」「7 日間」など）は消さない。
  - コミットの本文、README、AGENTS.md の箇条書きは地の文に書き換えない。PR テンプレートの見出しも変えない。
  - 画面の文言は、画面に収まる短さを保ち、ボタンや見出しの短い語を文に書き換えない。Android と iOS で同じ文言にそろえる。
  - 付属の `yomiyasu_lint.py` の指摘のうち、半角空白（`unnatural_halfwidth_space`）と箇条書きの比率（`excess_list`）は直さない。

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

### タイトル

- 何がどう変わるかを、具体的な動詞で書く。対象が片方の OS に限られる場合は明示する。
- PR 番号は推測して書かない（スカッシュマージのときに GitHub が付ける）。

### 本文

あとから変更内容や理由を追えるように、タイトルだけのコミットにしない。

- タイトルのあとに空行を入れ、箇条書きで本文を書く。複数の動作を変えたコミットをタイトルだけで済ませない。
- 1 項目に 1 つの変更を書く。利用者から見た操作・結果を先に、実装の補足をあとに書く。必要なら理由・制約・変更前後の値を添える。
- ファイル名の羅列や「改善した」だけで終わらせない。
- 単純な変更は短くてよい。複雑な変更では、主要な動作・例外処理・互換性・テストを書く。項目数を埋めるための説明は足さない。
- 長い箇条書きの続きは 2 文字分字下げする。
- メッセージは、そのコミットの差分だけを説明する。

### 検証

- テストやビルドを実行した場合は、本文の末尾に空行を入れて「検証:」を置く。
- 成功・失敗・未実施・環境上実行できなかった確認を区別して書く。
- 件数や対象環境は、確認済みの情報だけを書く。ビルドの成功を実機での確認と言い換えない。

### 作成者

- AI が共同で作成した場合は、末尾に空行を入れて `Co-authored-by:` を書く（ツールごとの書き方は「AI のクセへの対策」を参照）。

### 記載例

次の例の内容や検証結果は、実際の変更に合わせて書き換える。署名のモデル名も、実行環境で確認できた実際のモデル名に置き換える。

```text
feat: 山データを都道府県単位で事前ダウンロードできるようにする

- 方位盤の左下のダウンロードボタンから都道府県を選び、山データを前もって
  保存できるようにする（Android / iOS）。
- 進み具合を区画数で表示し、いつでも中断できる。中断や通信の失敗のあとは、
  取得済みの区画を飛ばして続きから再開する。
- 問い合わせが失敗したら 5・15・30 秒あけて取り直す。
- 設定の「キャッシュを消去」では、保存済みの地域を消さない。

検証:
- 成功: ./gradlew -p core test（android/ で実行）
- 成功: ./gradlew :app:assembleDebug（android/ で実行）
- 環境上実行できず: swift test（Swift の無い環境のため、PR の CI で確認する）
- 未実施: 実機での動作確認

Co-authored-by: Codex GPT-6
```

## Android と iOS をそろえる

- 機能の追加や不具合の修正は、特に指示がなければ Android と iOS の両方に同じ動作で入れる。片方だけにするときは PR の説明にそう書く。
- `android/core/`（Kotlin）と `ios/YamamukiCore/`（Swift）は同じロジックの移植。片方を変えたらもう片方も同じに変え、テストも両方に足す。
- 両 OS の違いは端末の機能による差だけにする。違いを増やしたら README の「Android 版と iOS 版の違い」を更新する。

## DB の版と移行

Android 版の山データの DB（Room）は、配布したあとも端末に残る。アップデートで版が変わったときに読めないと、起動直後に落ちる。

- テーブルや列を変えたら、DB の版（`MountainDatabase.VERSION`）を上げ、同じ PR で古い版からの移行を入れる。
  - テーブルや列を足すだけなら、`@Database` の `autoMigrations` に `AutoMigration(from, to)` を足す。
  - Room が自動で作れない移行（列の名前を変える・消すなど）は、`MountainMigrations.kt` に `Migration(from, to)` を足す。
- 事前ダウンロードした山データ（`mountains` と `fetched_tiles`）は移行で消さない。DB をまるごと作り直す `fallbackToDestructiveMigration()` は使わない（作り直すのは、古い版のアプリで新しい版の DB を開いたときだけ）。取り直せるテーブルだけは、移行の中で作り直してよい。
- ビルドで書き出したスキーマ（`android/app/schemas/` の版ごとの JSON）をコミットし、既にある版の JSON は書き換えない。
- 移行は、`android/app/schemas/` のすべての版から今の版へ移して山データが残るかを、単体テスト（`MountainDatabaseMigrationTest`）で確かめる。新しい版で初期値の無い必須の列を足したら、テストの見本の行にも値を足す。
- iOS 版のキャッシュはタイルごとの JSON で、読めないファイルは取得していないものとして取り直す。形式を変えるときは、項目を省略可能にして古いファイルも読めるようにする。

## 変更の確かめ方

コミットの前に、変えた部分に応じて次を通す。CI（`.github/workflows/`）でも同じものを動かしている。

```bash
# すべて: 改行コードと BOM の確認、画面の文言が文字列リソースにまとまっているかの確認
.github/scripts/check-text-format.sh
.github/scripts/check-ui-strings.py

# Android: core の単体テスト、アプリのビルドと単体テスト、DB のスキーマの確認（Gradle は android/ で動かす）
(cd android && ./gradlew -p core test)
(cd android && ./gradlew :app:assembleDebug)
(cd android && ./gradlew :app:testDebugUnitTest)
git fetch origin main && .github/scripts/check-room-schemas.sh origin/main

# iOS: core の単体テスト（Mac または Swift の入った環境）
(cd ios/YamamukiCore && swift test)

# iOS: アプリと UI テストのビルド（Mac のみ。XcodeGen は brew install xcodegen で入れる）
(cd ios && xcodegen generate && xcodebuild build-for-testing -project Yamamuki.xcodeproj -scheme Yamamuki \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO)
```

- iOS の画面を変えたら、手元で UI テストも動かす（手順は README の「iOS 版」）。CI では UI テストのビルドだけを確かめ、実行はしない。
- CI の「Build」（Android）は、アプリの単体テストと DB のスキーマの確認（スキーマのコミット漏れと、既にある版のスキーマの書き換え）も行う。
- CI の「Text format」は、画面の文言の確認（コードに直接書いた日本語、iOS のキーの過不足、両 OS のキーと文言のずれ）も行う。
- CI の「Build」（Android）と「Text format」はすべての PR で、「iOS」は `ios/` か `.github/workflows/ios.yml` を変えた PR だけで動く。
- 手元で動かせないもの（Mac が無いときの iOS ビルドなど）は、PR の CI で確かめ、PR の説明に「CI で確認」と書く。
- ロジックを変えたら単体テストを足す。テストを消したり飛ばしたりして通すことはしない。
- 画面を変えたら、変更前と変更後のスクリーンショットを PR に貼る（撮れないときはそう書く）。

## ブランチと PR

- `main` に直接 push しない。変更は作業ブランチで行い、main へは PR を通して入れる。
- PR はユーザーが明示的に作成を指示した場合だけ作成する。ドラフト PR も同様。
- 「修正して」「ブランチを作成して」という依頼に、PR 作成の許可は含まれない。作業が終わったら PR は作らず、ブランチ名と変更内容を伝える。
- 1 つの PR には 1 つの目的だけを入れる。ついでのリファクタリングや整形は別の PR にする。
- PR の説明は `.github/pull_request_template.md` の見出し（何を変えたか・対象・確認方法・スクリーンショット）に沿って書く。
- PR の件名と本文は、スキル `pr-description`（`.agents/skills/pr-description/SKILL.md`）の手順でコミットから作る。コミットが複数ある PR は、本文の最後に「マージ時のコミット」の候補を入れる。
- マージはユーザーが行う（スカッシュマージ）。エージェントは PR をマージしない。
- 複数の人や AI が並行して作業するときは、それぞれ別のブランチを使い、相手のブランチには push しない。同じファイルを大きく変える作業は同時に進めない。

## AI が作成する Issue・PR と投稿するコメント

- AI が作成する Issue・PR の本文と、GitHub に直接投稿するコメント（PR・Issue のコメント、レビュー、レビューへの返信）は、先頭（1 行目）に `[モデル名]` を付ける。本文が見出しで始まる場合は、その前にモデル名だけの行を追加し、空行を挟んで見出しを書く。文章で始まる場合は、その文章の先頭に付ける。
  - 例: `[Claude Opus 5.5] CI の失敗を直しました`、`[Codex GPT-5] レビューの指摘に対応しました`
- Issue・PR の件名にはモデル名を入れず、要望・問題・変更の内容が分かるものにする。
- モデル名は、そのとき実際に動いているモデルを書く。分からないときはツール名（`[Claude Code]`、`[Codex]`）だけでもよい。
- ほかの AI や人に @ で宛てたコメント（AI の名前や人のアカウント名の前に @ を付けたもの）には反応しない。同じ PR を見ている別の AI が、自分宛てでない依頼に答えてしまうのを防ぐため。
- AI は、PR や Issue のコメント・レビューに、自分宛てに @ を付けて明示的に頼まれたときだけ反応する（返信、コードの修正、push）。@ の付いていないコメントには、内容が修正の依頼や指摘に読めても反応しない。クラウドのセッションなどで PR を監視しているときも同じ。コメント欄で人どうしが仕様を検討して整理してから AI に頼むことが多く、検討中の意見に AI が先回りして手を加えると話がややこしくなるため。
- AI にレビューや作業を頼むときは、宛先を @ で明示する。
- 説明や例のつもりでも、AI の名前の前に @ を付けて書くと、その AI が呼ばれる（コードの書式で囲んでも同じ）。呼ぶつもりがないときは @ を付けずに名前だけを書く。

## AI のクセへの対策

AI ツールが起こしやすい失敗を防ぐための指示。人の作業には当てはまらないものもある。

- 画面の文言をコードに直接書いてしまうツールがあるので、文言は文字列リソースに書く（「言葉づかい」を参照）。
- エディタの設定（`.editorconfig`）を読まずにファイルを書くツールがあるので、編集前に対象ファイルの改行コードと BOM を確かめて保つ。Markdown は保存後に先頭の BOM が残っているかを確かめる（`SKILL.md` は BOM が付いていないかを確かめる）。
- 既存の Markdown に BOM を付けるときは、改行コードを変えない。
- `Co-authored-by:` の書き方:
  - Codex は `Co-authored-by: Codex` のあとに空白を入れて、実行環境で確認できたモデル名を書く。世代に加えて名称部分まで確認できる場合は、その部分も含める。モデル名が不明な場合は `Co-authored-by: Codex` とし、名称を推測で補わない。メールアドレスは確認できる場合だけ書く。
  - Claude Code は、Claude Code が指定する行をそのまま使う。
- 他のツールのコミットは、文章の粒度と構成の参考にするだけにする。他ツールの署名やセッション情報をコピーせず、名前・メールアドレス・モデル名・URL を創作しない。
- 過去のコミットメッセージを直す場合は、そのコミット時点の検証結果を使い、ファイル内容・既存の作成者情報・作業中の変更を保持する。

## 個人のルール

このファイルと `CLAUDE.md` は全員とクラウドの AI が読む共通ルール。人によって違う好みや、手元の環境によること（PR を AI に作らせるか、使う実機など）はここに書かず、コミットしない各自のファイルに書く。

| ツール | 個人のルールを書くファイル |
|---|---|
| Claude Code | リポジトリ直下の `CLAUDE.local.md`、または `~/.claude/CLAUDE.md` |
| Codex | `~/.codex/AGENTS.md` |

- 個人のルールには、共通ルールと矛盾することを書かない。
- Codex の `AGENTS.override.md` は、このファイルに足されるのではなく置き換えて読まれ、共通ルールが読まれなくなるので使わない。
- `CLAUDE.local.md` と `AGENTS.override.md` は `.gitignore` に入れてある。

## ドキュメント

- 画面・設定項目・キャッシュの仕組み・ビルド手順を変えたら、同じ PR で README.md も更新する。
- CI で確かめる内容を変えたら、同じ PR でこのファイルの「変更の確かめ方」も更新する。
- このファイルのルールを変えたら、同じ PR で理由を説明する。

## 文字コードと改行コード

- テキストファイルは UTF-8、改行は LF にする（`.bat` だけ CRLF）。
- Markdown（`.md`）は BOM 付きにする（BOM が無いと文字化けする AI ツールがあるため）。Markdown 以外には BOM を付けない。
- 例外として、スキルの `SKILL.md` には BOM を付けない。先頭の `---` からスキルの設定を読むため、BOM があると読めなくなるおそれがある。
- この決まりは `.gitattributes` と `.editorconfig` に書いてある。指定のない新規ファイルは、同じディレクトリにある同じ種類のファイルに合わせる。
- ルールどおりかは CI の「Text format」で確かめている。手元では `.github/scripts/check-text-format.sh` で確かめられる。

## やってはいけないこと

- 生成物や手元の設定をコミットしない（`ios/Yamamuki.xcodeproj/`、`android/local.properties`、`build/` など。`.gitignore` を参照）。
- 署名鍵（`*.jks`、`*.keystore`、`*-release.properties`）や API キーなどの秘密情報をコミットしない。
- アプリの版（Android の `versionCode` / `versionName`、iOS の `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`）は、ユーザーの指示があるときだけ上げる。上げるときは両 OS をそろえる。
- 依存ライブラリの更新は、機能の変更と同じ PR に混ぜない。

## OpenStreetMap のデータ

- Overpass API は共有の無料サービス。問い合わせの回数と範囲を増やす変更は避け、増えるときは PR の説明に理由と量の目安を書く。
- 山データは ODbL で利用している。アプリと README の「© OpenStreetMap contributors」の表示を消さない。
