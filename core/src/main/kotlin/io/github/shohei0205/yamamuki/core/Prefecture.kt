package io.github.shohei0205.yamamuki.core

/**
 * 山データを事前ダウンロードする単位としての都道府県。
 * 範囲は境界を囲む矩形なので、隣の県の山も一部含む(県境付近から見える山なので、むしろ都合がよい)。
 */
data class Prefecture(
    /** JIS X 0401 の都道府県コード(北海道 = 1 〜 沖縄県 = 47)。 */
    val code: Int,
    val name: String,
    /** 本土(県庁所在地を含む範囲)と、山のある主な離島の矩形。 */
    val areas: List<BoundingBox>,
) {
    /** ダウンロードするタイル。 */
    val tiles: List<Tile> get() = areas.flatMap { Tile.covering(it) }.distinct()

    /** 一覧で見出しにする地方。 */
    val region: String
        get() = when (code) {
            in 1..7 -> "北海道・東北"
            in 8..14 -> "関東"
            in 15..23 -> "中部"
            in 24..30 -> "近畿"
            in 31..35 -> "中国"
            in 36..39 -> "四国"
            else -> "九州・沖縄"
        }

    companion object {
        fun byCode(code: Int): Prefecture? = ALL.firstOrNull { it.code == code }

        /**
         * 本土の矩形は japanmap (Apache-2.0、国土数値情報をもとにした簡略な境界) から求め、0.01° 単位で外側に丸めた。
         * 離島は東京都の伊豆諸島、兵庫県の淡路島、島根県の隠岐、香川県の小豆島、長崎県の対馬・五島列島、
         * 鹿児島県の屋久島・奄美大島、沖縄県の石垣島・西表島を足した(小笠原諸島などは含まない)。
         */
        val ALL: List<Prefecture> = listOf(
        Prefecture(1, "北海道", listOf(BoundingBox(41.39, 139.77, 45.53, 145.83))),
        Prefecture(2, "青森県", listOf(BoundingBox(40.21, 139.86, 41.55, 141.69))),
        Prefecture(3, "岩手県", listOf(BoundingBox(38.74, 140.66, 40.45, 142.08))),
        Prefecture(4, "宮城県", listOf(BoundingBox(37.77, 140.28, 39.00, 141.68))),
        Prefecture(5, "秋田県", listOf(BoundingBox(38.87, 139.70, 40.51, 140.99))),
        Prefecture(6, "山形県", listOf(BoundingBox(37.73, 139.54, 39.13, 140.65))),
        Prefecture(7, "福島県", listOf(BoundingBox(36.78, 139.17, 37.98, 141.05))),
        Prefecture(8, "茨城県", listOf(BoundingBox(35.73, 139.68, 36.94, 140.86))),
        Prefecture(9, "栃木県", listOf(BoundingBox(36.19, 139.33, 37.16, 140.30))),
        Prefecture(10, "群馬県", listOf(BoundingBox(35.97, 138.40, 37.06, 139.68))),
        Prefecture(11, "埼玉県", listOf(BoundingBox(35.75, 138.71, 36.28, 139.90))),
        Prefecture(12, "千葉県", listOf(BoundingBox(34.89, 139.75, 36.10, 140.87))),
        Prefecture(13, "東京都", listOf(BoundingBox(35.49, 138.94, 35.90, 139.92), BoundingBox(33.05, 139.10, 34.80, 139.90))),
        Prefecture(14, "神奈川県", listOf(BoundingBox(35.13, 138.92, 35.67, 139.80))),
        Prefecture(15, "新潟県", listOf(BoundingBox(36.73, 137.63, 38.55, 139.90))),
        Prefecture(16, "富山県", listOf(BoundingBox(36.27, 136.77, 36.98, 137.77))),
        Prefecture(17, "石川県", listOf(BoundingBox(36.06, 136.24, 37.53, 137.36))),
        Prefecture(18, "福井県", listOf(BoundingBox(35.33, 135.45, 36.30, 136.84))),
        Prefecture(19, "山梨県", listOf(BoundingBox(35.16, 138.18, 35.97, 139.14))),
        Prefecture(20, "長野県", listOf(BoundingBox(35.19, 137.32, 37.03, 138.75))),
        Prefecture(21, "岐阜県", listOf(BoundingBox(35.13, 136.28, 36.46, 137.65))),
        Prefecture(22, "静岡県", listOf(BoundingBox(34.59, 137.48, 35.65, 139.16))),
        Prefecture(23, "愛知県", listOf(BoundingBox(34.57, 136.67, 35.42, 137.84))),
        Prefecture(24, "三重県", listOf(BoundingBox(33.72, 135.86, 35.26, 136.94))),
        Prefecture(25, "滋賀県", listOf(BoundingBox(34.78, 135.77, 35.70, 136.46))),
        Prefecture(26, "京都府", listOf(BoundingBox(34.70, 134.85, 35.78, 136.06))),
        Prefecture(27, "大阪府", listOf(BoundingBox(34.26, 135.09, 35.05, 135.75))),
        Prefecture(28, "兵庫県", listOf(BoundingBox(34.62, 134.25, 35.67, 135.48), BoundingBox(34.15, 134.70, 34.62, 135.05))),
        Prefecture(29, "奈良県", listOf(BoundingBox(33.85, 135.54, 34.78, 136.24))),
        Prefecture(30, "和歌山県", listOf(BoundingBox(33.43, 135.05, 34.39, 136.01))),
        Prefecture(31, "鳥取県", listOf(BoundingBox(35.05, 133.13, 35.61, 134.52))),
        Prefecture(32, "島根県", listOf(BoundingBox(34.30, 131.67, 35.61, 133.32), BoundingBox(35.95, 132.95, 36.35, 133.40))),
        Prefecture(33, "岡山県", listOf(BoundingBox(34.42, 133.26, 35.35, 134.42))),
        Prefecture(34, "広島県", listOf(BoundingBox(34.18, 132.04, 35.10, 133.46))),
        Prefecture(35, "山口県", listOf(BoundingBox(33.82, 130.86, 34.68, 132.26))),
        Prefecture(36, "徳島県", listOf(BoundingBox(33.54, 133.66, 34.24, 134.76))),
        Prefecture(37, "香川県", listOf(BoundingBox(34.00, 133.56, 34.40, 134.45), BoundingBox(34.40, 134.15, 34.60, 134.35))),
        Prefecture(38, "愛媛県", listOf(BoundingBox(32.89, 132.01, 34.14, 133.70))),
        Prefecture(39, "高知県", listOf(BoundingBox(32.71, 132.61, 33.88, 134.32))),
        Prefecture(40, "福岡県", listOf(BoundingBox(32.99, 130.04, 33.97, 131.20))),
        Prefecture(41, "佐賀県", listOf(BoundingBox(32.95, 129.76, 33.56, 130.55))),
        Prefecture(42, "長崎県", listOf(BoundingBox(32.56, 129.55, 33.40, 130.39), BoundingBox(34.05, 129.15, 34.72, 129.50), BoundingBox(32.55, 128.55, 33.30, 129.20))),
        Prefecture(43, "熊本県", listOf(BoundingBox(32.09, 130.36, 33.20, 131.34))),
        Prefecture(44, "大分県", listOf(BoundingBox(32.73, 130.82, 33.69, 132.09))),
        Prefecture(45, "宮崎県", listOf(BoundingBox(31.36, 130.70, 32.84, 131.89))),
        Prefecture(46, "鹿児島県", listOf(BoundingBox(30.99, 130.10, 32.19, 131.21), BoundingBox(30.20, 130.35, 30.50, 130.70), BoundingBox(28.00, 129.10, 28.55, 129.75))),
        Prefecture(47, "沖縄県", listOf(BoundingBox(26.06, 127.63, 26.88, 128.33), BoundingBox(24.20, 123.60, 24.60, 124.35))),
        )
    }
}
