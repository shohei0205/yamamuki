# 地表データ

方位盤の背景に描く地表（森林・水域・陰影）のデータ。[yamamuki-data](https://github.com/shohei0205/yamamuki-data) が配る SQLite を端末に置いて使う（Android・iOS）。

yamamuki-data が正式に公開するまでは、開発用のビルド（Android の debug、iOS の Debug）だけで出す（`Features.SURFACE` / `Features.surface`）。方位盤に描く処理はまだ入っておらず、取得・選択・設定・出典だけがある。

- データは ESA WorldCover 2021 から作った森林・水域と、国土地理院の標高タイルから作った陰影。Web メルカトルのタイル画像を収めた SQLite で、全国が 1 ファイル。アプリが読む種類は core の `SurfaceDataset`（今はズーム 5〜9 の `esa-worldcover-z9`、約 17 MB）だけ。
- 読む manifest は、山データと同じカタログ `catalog.json` の、その種類の `stable`（開発用のビルドは `dev`、無ければ `stable`）で決める。カタログにその種類が無いあいだは、選択画面に「利用できる地表データはありません。」と出す。
- 山データの選択画面の下の「地表データ」に、取得済みの種類とカタログにある種類を並べる。行には名前、「試験版」「使用中」「更新あり」の印、リリース日・取得日・大きさを出す。
  - 取得していない種類を選ぶと、リリース日と大きさを見せてダウンロードするかを聞く。取り込めたら、その種類を使う。
  - 新しい版があるときは「更新」、取得済みなら「削除」のボタンを出す。使っている種類を削除すると、設定の「地表を表示」をオフに戻す。
  - ダウンロード中は進み具合（%）を出す。
- データ本体はメモリに載せず、ファイルへ流しながら受け取る。別名で保存し、サイズと SHA-256、SQLite の中身（`PRAGMA application_id` と `user_version`、manifest に書かれた地表のデータセットとズームの段があるか）を確かめてから置き換える。途中で失敗しても前の版はそのまま残る。
- 置き場所は、Android が `noBackupFilesDir/surface-data/<種類>/`、iOS が Application Support の `surface-data/<種類>/`（バックアップから除外）。取り込んだ manifest も残し、出典の表示に使う。山データの DB（Room）には入れない。
- 設定の「地表を表示」（既定はオフ）で、方位盤に描くかを切り替える。地表データを取得していないときにオンにすると、オンにせずに山データの選択画面を開く。
- 「データの出典とライセンス」の画面には、取得済みの地表データのリリース日と出典（manifest の `metadata.sources`）を「地表データ」の見出しの下に出す。
- 確認と取得は `android/core/.../SurfaceData.kt`・`SurfaceDataUpdater.kt` と `ios/YamamukiCore/.../SurfaceData.swift`・`SurfaceDataUpdater.swift`、タイルの座標と合成は `SurfaceTiles`・`SurfaceCompositor`、保存と SQLite の確認は `SurfaceDataStore.kt` / `SurfaceDataStore.swift` にある。
