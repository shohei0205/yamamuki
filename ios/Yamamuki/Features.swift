/// 版によって出し入れする機能。周辺の山だけを取得するライト版を作るときは、ここを false にする。
enum Features {
    /// 目的地の山データを都道府県単位で前もってダウンロードする(方位盤の左下のボタンと、その画面)。
    static let areaDownload = true

    /// 方位盤の左下の更新ボタンで、今の表示範囲の山データを Overpass から取得する。
    static let fetchButton = true
}
