import Foundation
import YamamukiCore

/// 画面の文言。中身は Localizable.xcstrings にあり、キーで引く。
/// キー名は Android の strings.xml と同じにそろえる。
enum Strings {
    /// キーの文言。
    static func text(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    /// キーの文言に、%1$@ や %1$ld の値を差し込んだもの。3 桁区切りの数は groupedInteger で文字列にして渡す。
    static func format(_ key: String, _ args: CVarArg...) -> String {
        String(format: text(key), arguments: args)
    }
}

/// 16 方位の名前のキー。Heading.directionIndex の番号の順(北から時計回り)。
private let directionKeys = [
    "direction_n", "direction_nne", "direction_ne", "direction_ene",
    "direction_e", "direction_ese", "direction_se", "direction_sse",
    "direction_s", "direction_ssw", "direction_sw", "direction_wsw",
    "direction_w", "direction_wnw", "direction_nw", "direction_nnw",
]

/// 16 方位の名前(北、北北東、…)。
func directionName(_ headingDeg: Double) -> String {
    Strings.text(directionKeys[Heading.directionIndex(headingDeg)])
}

/// 重なって山名を省いた山の数を、代表の山の山名の下に添える文言。「ほか 3 山」。
func othersText(_ count: Int) -> String {
    Strings.format("dial_others", count)
}

/// 詳細表示の標高。「1,212 m」、不明なら「不明」。山と現在地で共通に使う。
func elevationLabel(_ elevationM: Double?) -> String {
    elevationText(elevationM) ?? Strings.text("common_unknown")
}

/// 詳細表示の緯度経度。狭い画面で途中で折り返さないよう、緯度と経度を改行で分ける。山と現在地で共通に使う。
func coordinateLabel(latitude: Double, longitude: Double) -> String {
    let lat = Strings.format(latitude >= 0 ? "detail_latitude_north" : "detail_latitude_south", degreeText(latitude))
    let lon = Strings.format(longitude >= 0 ? "detail_longitude_east" : "detail_longitude_west", degreeText(longitude))
    return "\(lat)\n\(lon)"
}

/// 事前ダウンロードで、今の区画が何を待っているかの文言。「サーバーの応答を待っています（35 秒）」など。
func downloadStatusText(_ status: DownloadStatus) -> String {
    switch status {
    case let .retryingIn(seconds, retry):
        return Strings.format("area_status_retrying", seconds, retry)
    case let .waiting(seconds, retry):
        return retry > 0
            ? Strings.format("area_status_waiting_retry", seconds, retry)
            : Strings.format("area_status_waiting", seconds)
    }
}
