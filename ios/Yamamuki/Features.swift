/// 版によって出し入れする機能。
enum Features {
    /// 目的地の山データを、都道府県単位で Overpass から前もってダウンロードする(方位盤の左下のボタンと、その画面)。
    /// 全国の山データを yamamuki-data からまとめて取得するようにしたので、止めている。
    static let areaDownload = false
}
