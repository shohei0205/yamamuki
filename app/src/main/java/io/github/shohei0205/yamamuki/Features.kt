package io.github.shohei0205.yamamuki

/**
 * 版によって出し入れする機能。周辺の山だけを取得するライト版を作るときは、ここを false にする。
 */
object Features {
    /** 目的地の山データを都道府県単位で前もってダウンロードする(方位盤の左下のボタンと、その画面)。 */
    const val AREA_DOWNLOAD = true
}
