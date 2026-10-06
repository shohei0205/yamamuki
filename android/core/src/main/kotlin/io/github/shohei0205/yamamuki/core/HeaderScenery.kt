package io.github.shohei0205.yamamuki.core

/** 2 次ベジェ曲線の 1 区間。始点は前の区間の終点。座標は dp。 */
data class QuadSegment(val controlX: Double, val controlY: Double, val endX: Double, val endY: Double)

/** 山並みの稜線。[startY] は左端(x = 0)の高さ。[segments] を左から順につなぐと、画面の右端まで届く。 */
data class Ridge(val startY: Double, val segments: List<QuadSegment>)

/** 空に浮かべる雲。[xFraction] は画面の幅に対する中心の位置、[y] は中心の高さ(dp)、[scale] は大きさの倍率。 */
data class Cloud(val xFraction: Double, val y: Double, val scale: Double)

/**
 * 方位盤の上部のヘッダーに描く、青空と遠くの山並みの形。両 OS で同じ形に描くため、点の位置をここで決める。
 * 座標の原点はヘッダーの左上で、単位は dp。風景はヘッダーの下の方位目盛りの裏まで続き、手前の裾野を方位盤の地面の色に溶かす。
 */
object HeaderScenery {
    /** 風景を描く高さ。ヘッダーと方位目盛りを合わせた高さで、下端は方位盤の地面の色に溶ける。 */
    const val HEIGHT_DP = DialGeometry.HEADER_HEIGHT_DP + DialGeometry.TAPE_HEIGHT_DP

    /** 山並みの模様の幅。画面がこれより広いときは、同じ模様を横に繰り返す。 */
    const val PERIOD_DP = 360.0

    /** 山並みの裾の高さ。ここから下を地面の色に溶かし始める。 */
    const val FOOT_Y_DP = 66.0

    /** 地面の色に溶かし始める高さ。裾より少し上から始め、稜線の下端をぼかす。 */
    const val FADE_TOP_DP = FOOT_Y_DP - 6.0

    /** 空のグラデーションの中ほどの色に切り替わる位置(風景の高さに対する割合)。 */
    const val SKY_MIDDLE_FRACTION = 0.55

    /** 地面の色に溶かす途中で、ほぼ地面の色になる位置(溶かす範囲に対する割合)と、そこでの濃さ。 */
    const val FADE_MIDDLE_FRACTION = 0.45
    const val FADE_MIDDLE_ALPHA = 0.85

    /** 地面の色で塗りつぶし終える位置(溶かす範囲に対する割合)。ここから風景の下端までは地面の色だけになる。 */
    const val FADE_SOLID_FRACTION = 0.8

    /** 地面の色で塗りつぶし終える高さ。 */
    const val FADE_SOLID_Y_DP = FADE_TOP_DP + (HEIGHT_DP - FADE_TOP_DP) * FADE_SOLID_FRACTION

    /**
     * 空と山並みを塗る下端。地面の色で塗りつぶし終えた高さより少し下で止め、風景の下端までは描かない。
     * 下端を風景と同じ高さにすると、dp がピクセルの境目に来ない端末で、端の半端な行に山並みの色が透けて細い線に見える。
     */
    const val FILL_BOTTOM_DP = FADE_SOLID_Y_DP + 2.0

    /** 方位目盛りの白い帯を、上端から濃くしていき、元の濃さになる位置(目盛りの高さに対する割合)。帯の上端を山並みの上で筋に見せない。 */
    const val TAPE_BAND_FADE_FRACTION = 0.6

    /** 奥から手前へ、3 重の山並み。 */
    enum class Layer(internal val baseY: Double, internal val points: List<Pair<Double, Double>>) {
        /** いちばん奥の淡い青の山並み。 */
        FAR(
            FOOT_Y_DP - 30,
            listOf(0.0 to 22.0, 40.0 to 10.0, 78.0 to 26.0, 120.0 to 8.0, 160.0 to 20.0, 205.0 to 2.0,
                240.0 to 18.0, 285.0 to 6.0, 322.0 to 24.0, 360.0 to 22.0),
        ),
        /** 中ほどの青緑の山並み。 */
        MIDDLE(
            FOOT_Y_DP - 14,
            listOf(0.0 to 4.0, 30.0 to 14.0, 64.0 to 2.0, 110.0 to 16.0, 150.0 to 6.0, 190.0 to 18.0,
                230.0 to 10.0, 272.0 to 20.0, 312.0 to 6.0, 360.0 to 4.0),
        ),
        /** 手前の緑の稜線。 */
        NEAR(
            FOOT_Y_DP - 2,
            listOf(0.0 to 8.0, 50.0 to 0.0, 96.0 to 10.0, 140.0 to 4.0, 200.0 to 12.0, 250.0 to 2.0,
                300.0 to 10.0, 360.0 to 8.0),
        ),
    }

    /** 雪をかぶった独立峰。奥の山並みと中ほどの山並みの間に、画面の幅に対して同じ割合の位置に 1 つだけ描く。 */
    const val PEAK_X_FRACTION = 0.64
    const val PEAK_TOP_Y_DP = FOOT_Y_DP - 46
    const val PEAK_HALF_WIDTH_DP = 70.0
    /** 山頂の平らな部分の半分の幅。 */
    const val PEAK_CREST_HALF_DP = 14.0

    val clouds = listOf(Cloud(0.36, 14.0, 1.0), Cloud(0.83, 10.0, 0.8))

    /**
     * 幅 [widthDp] の画面に描く稜線。模様を [PERIOD_DP] ごとに繰り返し、右端を越えるところまで返す。
     * 山と山の間は、隣り合う 2 点の低いほう(画面では上)より少し高く盛り上げてつなぐ。
     */
    fun ridge(layer: Layer, widthDp: Double): Ridge {
        val points = layer.points
        val segments = mutableListOf<QuadSegment>()
        var shift = 0.0
        while (shift < widthDp || segments.isEmpty()) {
            for (i in 1 until points.size) {
                val (x, dy) = points[i]
                val (px, pdy) = points[i - 1]
                segments += QuadSegment(
                    controlX = shift + (x + px) / 2,
                    controlY = layer.baseY + minOf(dy, pdy) - 4,
                    endX = shift + x,
                    endY = layer.baseY + dy,
                )
            }
            shift += PERIOD_DP
        }
        return Ridge(layer.baseY + points.first().second, segments)
    }

    /** 独立峰の中心の x(dp)。 */
    fun peakCenterX(widthDp: Double): Double = widthDp * PEAK_X_FRACTION

    /** 独立峰の頂の雪の輪郭。中心を (0, 山頂の高さ) とした相対位置(dp)。上の 2 点の間は山頂と同じく少し盛り上げる。 */
    val snowCap = listOf(
        -14.0 to 0.0, 14.0 to 0.0, 24.0 to 12.0, 15.0 to 9.0, 8.0 to 14.0,
        0.0 to 8.0, -8.0 to 14.0, -16.0 to 9.0, -24.0 to 12.0,
    )

    /** 山頂の平らな部分を盛り上げる量(dp)。山頂の輪郭と雪の輪郭の上辺で使う。 */
    const val CREST_RISE_DP = 3.0
}
