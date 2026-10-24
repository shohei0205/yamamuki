# 山データの取得

全国の山頂データ(約 14,000 件、約 0.5 MB)を [yamamuki-data](https://github.com/shohei0205/yamamuki-data) から取得し、端末に保存する。方位盤は保存したデータだけで表示し、自分では通信しない。新しい版があるかは、利用者が頼んだときと、設定の「更新を自動で確認」をオンにしたときだけ確かめる。

- 初回起動時に「山データを取得」を聞く。「取得する」を選ぶと manifest だけを取り、データを作った日とデータ本体の大きさ（manifest の `version` の先頭の日付と `sizeBytes`）を「リリース：2026/10/07、約 493 KB」のように見せて、ダウンロードするかを聞く。「あとで」を選んだときは設定画面の「山データ」から取得できる。位置情報の許可はこのあとに聞く。
- 設定画面の「山データを更新」(取得済みなら「更新を確認」)で取り直せる。新しい版があれば、初回と同じように作った日と大きさを見せて、ダウンロードするかを聞く。新しい版がなければ確認だけで終わる。
- 設定の「更新を自動で確認」(既定はオフ)をオンにすると、アプリが前面に出たときに、前回の確認から 30 日たっていれば新しい版があるかを確かめる。圏外で確かめられなかったときは、通信できるようになったときに確かめる。オンにした直後も、前回から 30 日たっていれば確かめる（30 日以内なら通信せず、覚えておいた結果でラベルを出す）。
  - 確かめるのはカタログ `catalog.json`(1 KB ほど)と、指す manifest が変わったときの manifest だけで、データ本体は取らない。新しい版が見つかったら、方位盤の上部に「新しい山データあり」のラベルを出す。タップすると設定の「更新を確認」と同じ処理を行い、作った日と大きさを見せてダウンロードするかを聞く。
  - 見つかったことは端末に覚え、取り込むか、確認で新しい版が無くなるまで、アプリを開き直してもラベルを出す。設定をオフにするとラベルも消える。
  - 山データをまだ取得していないとき、「山データの更新が必要です」のラベルが出ているときは確かめない。利用者が頼んだ通信ではないので、失敗しても知らせず、次の機会に確かめ直す。
  - 確かめる時期の判定(`PeakData.autoCheckDue`)は core にある。
- ダウンロードに失敗したときの「再試行する」は、確認を聞き直さずに同じ版を取り直す。確認の段階で失敗したときは、確認からやり直す。
- 読む manifest は yamamuki の Pages に置いたカタログ `catalog.json`（https://shohei0205.github.io/yamamuki/data/catalog.json ）の、設定の「山データ」で選んだ種類（`osm-peaks` か `gsi-peaks`）で決める（0.8.0 から。0.7.0 までは `osm-peaks/current.json` を読んでいた）。URL は core の `PeakData` にある。
  - アプリが知っているデータの種類は、core の `PeakDataset`（`osm-peaks`・`gsi-peaks`）だけ。カタログにほかの種類があっても読まない。manifest の版 5 以降では、`fileName` が種類と合わない（`osm-peaks` なら `osm-peaks.json.gz`、`gsi-peaks` なら `gsi-peaks.json.gz` でない）manifest は読まない。種類を取り違えて、保存済みの山を別の種類で置き換えないため。`gsi-peaks` は manifest の版 6 以降だけを読む。
  - 配布用のビルド(Android の release、iOS の Release)は、カタログのその種類の `stable` が指す manifest を読む。
  - 開発用のビルド(Android の debug、iOS の Debug)は、既定ではカタログのその種類の `dev` が指す manifest を読む(`dev` が無ければ `stable`)。配布版の `stable` を変えずに、新しい版のデータを試せる。
  - 開発用のビルドで取得先を `dev` にすると、`osm-peaks` は yamamuki-data の dev が公開する最新の開発版 `https://shohei0205.github.io/yamamuki-data/points/osm-peaks-dev/manifest.json` を直接読む（`gsi-peaks` はカタログの `dev` を読む）。画面からは切り替えられず、ビルドのときに選ぶ。
    - Android: Gradle のプロパティ `peakDataSource=dev` を付ける(例: `./gradlew :app:installDebug -PpeakDataSource=dev`。`~/.gradle/gradle.properties` に書いてもよい。既定は `current`)。`android/app/build.gradle.kts` で `BuildConfig.PEAK_DATA_DEV` になる。
    - iOS: ビルド設定 `PEAK_DATA_SOURCE` を `dev` にする(例: `xcodebuild ... PEAK_DATA_SOURCE=dev`。Xcode ではターゲットの Build Settings の User-Defined で変える。`xcodegen generate` で既定の `current` に戻る)。`ios/project.yml` で `PEAK_DATA_DEV` の条件が付く。
  - 読み先を切り替えたビルドは、前の読み先の版と違えば取り直し、保存している山データを入れ替える。
- カタログは確かめるたびに取り直す(1 KB ほど)。ただし、選択画面を開いたときのように種類ごとに続けて確かめるときは、1 分以内に取ったカタログを使い回す。指す manifest の SHA-256 が前回と同じなら、manifest は取り直さない。違えば manifest を取り、SHA-256 がカタログと合うかを確かめる。カタログが yamamuki の `<データの種類>/manifests/` 以外を指しているときは読まない。0.7.0 の `current.json` と同じく manifest の SHA-256 で比べるので、同じ manifest を指している間は、0.7.0 から上げても取り直さない。yamamuki-data の開発版を直接読むときは、manifest に前回の ETag を付けて問い合わせ、変わっていなければ何も受け取らない。
- 版が新しいときだけデータ本体(manifest の `downloadUrl`)を取得し、サイズと SHA-256 を確かめてからキャッシュに取り込む。失敗したときは保存済みのデータをそのまま使い、画面中央で知らせる(「再試行する」で取り直せる)。
- 取得先のカタログと参照先ファイル（`site/data/`）の形と、差し替えの手順は [山データのカタログと差し替え](peak-data-catalog.md) にある。
- 対応する manifest の形式は schemaVersion 4・5・6。版 5 以降では、データ本体の版(`dataSchemaVersion`)が 5 か 6 のときだけ取り込む。知らない版や、データ本体の版が書かれていないときは取り込まず、アプリの更新を促す。
  - 版 6 では件数を `metadata.pointCount` から読み、形式の名前(`schemaFormat` が `yamamuki-manifest`、`dataSchemaFormat` が `yamamuki-points`)が違う manifest は読まない。
- 版 5 のデータ本体(地点データ)は、山ごとの項目が版 4 までと同じなので、同じ読み方で読む。山頂以外の種別(`type`)の地点と、`osmId` の無い地点は飛ばす。
- 版 6 のデータ本体は、地点の配列が文書の `points` に入る。`osm-peaks` は `osmId` の代わりに `id` の `osm:<ノード番号>` をノード番号として読み、`osm:` 以外の ID は飛ばす。`gsi-peaks` は `id`（国土地理院の地点の ID）をそのまま地点の ID にする。別名は `{name, nameReading}` の配列から `name` を、読み仮名は配列なら最初の空でないものを読む。保存データの形は版 5 と同じ。
  - 山名・座標・標高のほかに、地点の ID(`id`)・読み仮名・別名・Wikipedia と Wikidata の URL(https だけ)・タグ(`tags`、「日本百名山」など)・画像(`graphic` の SVG と倍率)も読んで保存データに残す。今は画面には出していない。形の合わない値は無いものとして扱う。
  - 保存先は、Android が DB の `mountains` の列(DB の版 2 で追加。別名とタグは JSON の配列の文字列)、iOS がタイルごとの JSON(項目は省略可能で、項目の無い古いファイルも読める)。
  - 地点の ID はアプリの中では文字列で持つ（`osm-peaks` は `osm:<ノード番号>`）。Android は DB の版 3 で、`mountains` と `fetched_tiles` に種類の列（`dataset`）を足して種類ごとに分け、OSM のノード番号の列を地点の ID の列（`pointId`）にまとめた。版 2 までの山データは `osm-peaks` として移す。iOS は種類ごとにフォルダを分け（`osm-peaks` は今までの `mountains/`、ほかは `mountains-<種類>/`）、ノード番号だけの古いファイルも `osm:<番号>` として読む。
- 取り込んだ manifest とデータ本体(gz)は、保存データに取り込み終えてから、種類ごとに端末に 1 組だけ残す。新しい版を取り込むと置き換えるので溜まらない(今は約 0.5 MB)。取り込みに失敗したときは、前の組をそのまま残す。アプリが読まない項目(地点の画像など)も、ここには残る。
  - 取り込み済みの記録には保存データを作った読み込み処理の版(`PeakData.READER_VERSION` / `PeakData.readerVersion`)と、manifest と gz の SHA-256 も書く。読む項目を増やしたら、この版を上げる。
  - アプリの更新で読み込み処理の版が上がっていたら、起動時に残した manifest と gz から通信せずに保存データを作り直す。どちらかが無いか SHA-256 が合わないときは、次に更新を確かめたときに、データの版が同じでも取り直す。読み込み処理の版が変わらないうちは、残したファイルが消えても取り直さない。gz を残す前の版のアプリから更新したときも、最初の確認で一度だけ取り直す。
  - 作り直せずに読み込み処理の版が古いままのときは、方位盤の上部に「山データの更新が必要です」のラベルを出す。タップすると設定の「更新を確認」と同じ処理を行い、作った日と大きさを見せてダウンロードするかを聞く。自動では取り直さず、圏外ならタップしても通信せずに圏外の知らせを出す。
  - 置き場所は、Android がアプリの `noBackupFilesDir/peak-data/`、iOS が Application Support の `peak-data/`(バックアップから除外)。`osm-peaks` は今までと同じ場所、ほかの種類はその下の `<種類>/`。取り込み済みの記録も種類ごとに持つ（`osm-peaks` は今までと同じ名前）。山データの選択画面で削除すると記録と一緒に消す(Android は、古い版のアプリでキャッシュを作り直したときも、すべての種類を消す)。
- 取り込みと取り込み済みの版の記録は `android/core/.../PeakData.kt` と `ios/YamamukiCore/.../PeakData.swift`、gz の保存は `PeakDataArchive.kt` / `PeakDataArchive.swift` にある。
- OpenStreetMap の Overpass API から取得していたころに分かったことは [peak-data-notes.md](peak-data-notes.md) にまとめた。

## 山データの選択

設定の「山データ」の先頭の行（選んでいる山データの名前を出す）から、方位盤・山の検索・出典の画面に使う山データの種類を選ぶ（Android・iOS）。既定は OpenStreetMap（`osm-peaks`）で、0.7.0 から上げても見え方は変わらない。

- 選択画面には取得済みの種類をすぐに出し、通信できればカタログで取得できる種類と更新があるかを確かめて足す（カタログと、変わったときの manifest だけを取り、データ本体は取らない）。圏外のときは取得済みの種類だけを出す。
- 各行には名前（「OpenStreetMap」「国土地理院」）、manifest の `experimental` が true のときの「試験版」、使っている種類の「使用中」、新しい版があるときの「更新あり」の印と、取得済みなら件数・リリース日・取得日、まだならダウンロードする件数・リリース日・大きさを出す。
- 取得済みの種類を選ぶとすぐ切り替える。取得していない種類を選ぶと、初回と同じように作った日と大きさを見せてダウンロードするかを聞き、取り込めたら切り替える。切り替えると、検索位置モードと検索画面はやめ、フィルターの一覧はその種類のタグで作り直す（チェックは残し、その種類に無いグループでは絞り込まない）。
- 取得済みの種類は「削除」で端末から消せる（記録・残した manifest と gz・保存した山）。使っている種類を消すと、取り直すまで方位盤に山が出ない。`osm-peaks` を消しても、事前ダウンロードした地域は残す。以前の設定の「キャッシュ」（件数・容量の確認と消去）は、この削除に置き換えた。
- 設定の「取得した山データ」「更新を確認」は、使っている種類について行う。「更新を自動で確認」は取得済みの種類をすべて確かめ、方位盤のラベルは使っている種類に新しい版があるときだけ出す。
- 種類ごとに別々に保存するので、切り替えても取り直さない。2 つの種類を重ねては表示しない。

以前の都道府県単位の事前ダウンロード(Overpass から取得)は、`Features.AREA_DOWNLOAD`(Android、`android/app/.../Features.kt`)と `Features.areaDownload`(iOS、`ios/Yamamuki/Features.swift`)を false にして隠している。コードは残してある。
