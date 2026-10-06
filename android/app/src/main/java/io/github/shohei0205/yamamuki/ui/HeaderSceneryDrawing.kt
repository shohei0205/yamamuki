package io.github.shohei0205.yamamuki.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import io.github.shohei0205.yamamuki.core.HeaderScenery

/** 空の上端の色。ステータスバーの裏もこの色で塗り、空がつながって見えるようにする。 */
val HeaderSkyTop = Color(0xFF4E9BD6)
private val HeaderSkyMiddle = Color(0xFF8EC4EA)
private val HeaderSkyBottom = Color(0xFFD9EEF8)
private val CloudWhite = Color.White.copy(alpha = 0.85f)
private val RidgeFar = Color(0xFFA9C6DE)
private val PeakBlue = Color(0xFF8FB0CC)
private val RidgeMiddle = Color(0xFF6F9AA8)
private val RidgeNear = Color(0xFF5E8F6A)

/**
 * 方位盤の上部のヘッダーと、その下の方位目盛りの裏に、青空と遠くの山並みを描く。
 * 形は [HeaderScenery] で決め、iOS と同じに描く。手前の裾野は [ground] (方位盤の地面の色)に溶かし、目盛りとの境目を作らない。
 * 広告を出すときは、ヘッダーの中央に重ねる。
 */
internal fun DrawScope.drawHeaderScenery(ground: Color) {
    val unit = 1.dp.toPx()
    val height = HeaderScenery.HEIGHT_DP.toFloat() * unit
    // 空と山並みは、地面の色で塗りつぶした範囲の途中で止める([HeaderScenery.FILL_BOTTOM_DP])。
    val fillBottom = HeaderScenery.FILL_BOTTOM_DP.toFloat() * unit
    val widthDp = size.width / unit
    fun y(v: Double) = v.toFloat() * unit
    fun x(v: Double) = v.toFloat() * unit

    drawRect(
        Brush.verticalGradient(
            0f to HeaderSkyTop,
            HeaderScenery.SKY_MIDDLE_FRACTION.toFloat() to HeaderSkyMiddle,
            1f to HeaderSkyBottom,
            startY = 0f,
            endY = height,
        ),
        size = Size(size.width, fillBottom),
    )
    for (cloud in HeaderScenery.clouds) {
        val cx = (cloud.xFraction * size.width).toFloat()
        val cy = y(cloud.y)
        val s = cloud.scale.toFloat() * unit
        drawOval(CloudWhite, Offset(cx - 22 * s, cy - 6 * s), Size(44 * s, 12 * s))
        drawOval(CloudWhite, Offset(cx, cy - 10 * s), Size(24 * s, 12 * s))
    }

    fun ridgePath(layer: HeaderScenery.Layer) = Path().apply {
        val ridge = HeaderScenery.ridge(layer, widthDp.toDouble())
        moveTo(0f, fillBottom)
        lineTo(0f, y(ridge.startY))
        for (segment in ridge.segments) {
            quadraticTo(x(segment.controlX), y(segment.controlY), x(segment.endX), y(segment.endY))
        }
        lineTo(x(ridge.segments.last().endX), fillBottom)
        close()
    }

    drawPath(ridgePath(HeaderScenery.Layer.FAR), RidgeFar)

    // 雪をかぶった独立峰。
    val peakX = x(HeaderScenery.peakCenterX(widthDp.toDouble()))
    val peakTop = y(HeaderScenery.PEAK_TOP_Y_DP)
    val foot = y(HeaderScenery.FOOT_Y_DP)
    val crest = x(HeaderScenery.PEAK_CREST_HALF_DP)
    val rise = y(HeaderScenery.CREST_RISE_DP)
    val half = x(HeaderScenery.PEAK_HALF_WIDTH_DP)
    drawPath(
        Path().apply {
            moveTo(peakX - half, foot)
            lineTo(peakX - crest, peakTop)
            quadraticTo(peakX, peakTop - rise, peakX + crest, peakTop)
            lineTo(peakX + half, foot)
            close()
        },
        PeakBlue,
    )
    drawPath(
        Path().apply {
            val cap = HeaderScenery.snowCap
            moveTo(peakX + x(cap[0].first), peakTop + y(cap[0].second))
            quadraticTo(peakX, peakTop - rise, peakX + x(cap[1].first), peakTop + y(cap[1].second))
            for ((dx, dy) in cap.drop(2)) lineTo(peakX + x(dx), peakTop + y(dy))
            close()
        },
        Color.White,
    )

    drawPath(ridgePath(HeaderScenery.Layer.MIDDLE), RidgeMiddle)
    drawPath(ridgePath(HeaderScenery.Layer.NEAR), RidgeNear)

    // 手前の裾野を方位盤の地面の色に溶かす。
    val fadeTop = y(HeaderScenery.FADE_TOP_DP)
    drawRect(
        Brush.verticalGradient(
            0f to ground.copy(alpha = 0f),
            HeaderScenery.FADE_MIDDLE_FRACTION.toFloat() to ground.copy(alpha = HeaderScenery.FADE_MIDDLE_ALPHA.toFloat()),
            HeaderScenery.FADE_SOLID_FRACTION.toFloat() to ground,
            1f to ground,
            startY = fadeTop,
            endY = height,
        ),
        topLeft = Offset(0f, fadeTop),
        size = Size(size.width, height - fadeTop),
    )
}
