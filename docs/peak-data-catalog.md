# 山データのカタログと差し替え

- カタログと参照先ファイルは `site/data/` にある。
  - `catalog.json`(カタログ): データの種類(`datasets` の `osm-peaks`・`gsi-peaks`)ごとに、`stable` と `dev` が指す manifest の URL と SHA-256 を書く。`osm-peaks` の `stable` は必須で、ほかは省略できる。アプリは `stable`(開発用のビルドは `dev` も)のある種類を選択画面に出す。
  - `osm-peaks/current.json`(参照先ファイル): 0.6.0・0.7.0 が読む。`stable` だけを書き、カタログの `osm-peaks` の `stable` と同じ manifest を指す。0.9.0 で廃止する予定。
  - `<データの種類>/manifests/<タグ>.json`: yamamuki-data の Release の `manifest.json` を書き換えずにコピーしたもの。差し替えるたびに 1 つ増やし、一度置いたファイルは上書きも削除もしない（前の版に戻すとき、そのまま使えるようにするため）。データ本体は yamamuki-data の Release のものを使う。
- カタログと参照先ファイルは手で書き換えず、Actions の「山データの差し替え PR を作る」を main で手動で動かして、差し替えの PR を作る（実行できるのは書き込み権限のある人だけ）。
  - 「データの種類」で `osm-peaks` か `gsi-peaks` を選ぶ。`osm-peaks` の `stable` を変えると、`current.json` の `stable` も同じ manifest にする。
  - 「行う操作」で「dev: 開発用アプリで試す山データを選ぶ」「stable: 配布用アプリで使う山データを選ぶ」「remove-dev: 開発用アプリも配布用と同じ山データに戻す（gsi-peaks では dev を外す）」のいずれかを選ぶ。内部ではそれぞれ `dev`・`stable`・`remove-dev` として処理する。
  - 山データを選ぶ操作では Release のタグを指定する（空欄なら、その種類の最新の Release）。`stable` には正式版の Release（タグ `osm-peaks-…`・`gsi-peaks-…`）だけを置ける。`osm-peaks` の `stable` は manifest の版 5 だけにする。配布中のアプリが版 6 を読めないうちに、配布用を版 6 にしないため。`osm-peaks` の開発用は形式の版を問わない（アプリが読めるかは CI の `SitePeakDataTest` で確かめる）。`gsi-peaks` は版 6 以降だけを置ける。最新の Release は yamamuki-data の Release の一覧をすべて読んで、選んだ種類のタグ（`osm-peaks-`・`gsi-peaks-`）から選ぶ。manifest の `fileName` が種類と合わない（`osm-peaks` なら `osm-peaks.json.gz` でない）ものは置けない。
  - 「同じ山データに戻す」操作では `dev` の指定だけを外し、既存の `stable` を使う。Release のタグは使わない。`dev` の指定がなければ PR は作らない。
  - 配布用の山データを選ぶときに「開発用アプリも配布用と同じ山データに戻す」にチェックすると、配布用の更新と同時に `dev` の指定も外す。
  - PR は GitHub App のトークンで作る（標準のトークンで作った PR では CI が動かないため）。App の ID と鍵は、main だけが使える環境 `data-swap` の変数 `APP_ID` とシークレット `APP_PRIVATE_KEY` に置いている。
  - PR の CI「山データの確認」がカタログと `current.json` の形、`current.json` の `stable` がカタログの `osm-peaks` の `stable` と同じか、コピーが Release と同じか、`manifests/` が追加だけか、`osm-peaks` のデータ本体の大きさと SHA-256、`osm-peaks` の `stable` の件数が 5% より多く減っていないかを確かめる。`gsi-peaks` は manifest までを確かめ、データ本体は取らない。件数が減るのを確かめたうえで通すときは、PR にラベル「山データの件数減を確認済み」を付ける。
  - core の単体テスト（`SitePeakDataTest`）が、カタログの `osm-peaks`・`gsi-peaks` の指す manifest をアプリの読み込み処理で読めるかを確かめる。
  - マージ後は Pages の公開のあとに、公開中のカタログ・`current.json`・manifest が git と同じかを確かめる。公開中のカタログから manifest とデータ本体(`gsi-peaks` は manifest まで)をたどれるかと、公開中の `current.json` がカタログと合っているかは、「山データの見張り」で毎日確かめ、失敗したら Issue で知らせる。
  - 確認の処理は `.github/scripts/peak_data.py` にある。
