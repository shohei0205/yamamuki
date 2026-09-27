package io.github.shohei0205.yamamuki.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shohei0205.yamamuki.core.Box
import io.github.shohei0205.yamamuki.core.DialGeometry
import io.github.shohei0205.yamamuki.core.ElevationClass
import io.github.shohei0205.yamamuki.core.Heading
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.declutter
import io.github.shohei0205.yamamuki.core.elevationClass
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val DialBeige = Color(0xFFEFE4B0)
private val RingGray = Color(0xFFC3C3C3)
private val PeakGreen = Color(0xFF22B14C)
private val PeakYellow = Color(0xFFB5E61D)
private val HillGreen = Color(0xFF9BD65A)
private val PeakBrown = Color(0xFF8C5A2B)
private val PeakBrownDark = Color(0xFF5E3A17)
private val SnowWhite = Color(0xFFFFFFFF)
private val NorthRed = Color(0xFFED1C24)
private val BinocularBody = Color(0xFF333333)
private val BinocularHinge = Color(0xFF777777)
private val LensBlue = Color(0xFF5B8DB8)
private val SummitRock = Color(0xFF5D6D7E)
private val SummitRockLight = Color(0xFF8A99A8)
private val FlagPole = Color(0xFF333333)

/** 画面上部の方位目盛りに収める角度の幅。 */
private const val TAPE_SPAN_DEG = 60.0

/**
 * 方位盤の文字。設定の文字サイズ([scale])を山名・距離の目盛り・方位の表示に掛ける。
 * 上端の方位目盛りは高さが決まっているので倍率を掛けない。
 */
private class DialTextStyles(scale: Float) {
    val label = TextStyle(color = Color.Black, fontSize = 13.sp * scale, fontWeight = FontWeight.Bold)
    val ringLabel = TextStyle(color = RingGray, fontSize = 12.sp * scale, fontWeight = FontWeight.Bold)
    val readout = TextStyle(color = Color.Black, fontSize = 15.sp * scale, fontWeight = FontWeight.Bold)
}

/**
 * 方位盤。現在地(画面下部の双眼鏡)から向いている方向を上にとり、山をアイコンと山名で描く。
 * アイコンの色と形は標高の区分([ElevationClass])で変える。
 * [mountains] は表示の優先順(標高の高い順)に並んでいること。重なる山は優先度の低いほうを省く。
 * 描いた山(アイコンか山名)をタップすると [onMountainTap] を呼ぶ。
 * 双眼鏡(現在地)をタップすると [onObserverTap] を呼ぶ。
 * 現在地がほぼ山頂([summit] が非 null)のときは、双眼鏡の代わりに山頂アイコンと山名を描き、そのタップも [onMountainTap] に渡す。
 */
@Composable
fun DialCanvas(
    headingDeg: Double,
    mountains: List<NearbyMountain>,
    rangeKm: Double,
    modifier: Modifier = Modifier,
    onMountainTap: (NearbyMountain) -> Unit = {},
    onObserverTap: () -> Unit = {},
    /** 現在地がほぼ山頂のとき、その山。 */
    summit: NearbyMountain? = null,
    /** 現在地の標高(海抜)。方位の表示の後ろに添える。null なら出さない。 */
    altitudeM: Double? = null,
    /** 一度に表示する山の上限。 */
    maxPeaks: Int = 40,
    /** 文字の大きさ(標準 = 1.0 に対する倍率)。 */
    textScale: Float = 1f,
) {
    val styles = remember(textScale) { DialTextStyles(textScale) }
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val hitTargets = remember { HitTargets() }
    val currentOnTap by rememberUpdatedState(onMountainTap)
    val currentOnObserverTap by rememberUpdatedState(onObserverTap)
    val tapModifier = Modifier.pointerInput(Unit) {
        val slop = 8.dp.toPx()
        detectTapGestures { tap ->
            if (hitTargets.hitsObserver(tap, slop)) {
                currentOnObserverTap()
            } else {
                hitTargets.find(tap, slop)?.let(currentOnTap)
            }
        }
    }
    Canvas(modifier.clipToBounds().then(tapModifier)) {
        val tapeHeight = 44.dp.toPx()
        val chartTop = tapeHeight + 32.dp.toPx()
        // 双眼鏡が右下の「© OpenStreetMap contributors」と重ならない高さ。
        val observer = Offset(size.width / 2, size.height - 52.dp.toPx())
        val pxPerKm = ((observer.y - chartTop) / rangeKm).toFloat()
        hitTargets.peaks = if (pxPerKm > 0f) {
            drawRings(observer, pxPerKm, rangeKm, chartTop, textMeasurer, styles)
            drawPeaks(observer, pxPerKm, headingDeg, mountains, chartTop, textMeasurer, styles, maxPeaks)
        } else {
            emptyList()
        }
        if (summit != null) {
            hitTargets.summit = drawSummit(observer, summit, textMeasurer, styles)
            hitTargets.observer = null
        } else {
            hitTargets.observer = drawBinoculars(observer)
            hitTargets.summit = null
        }
        drawTape(headingDeg, tapeHeight, textMeasurer)
        drawReadout(headingDeg, altitudeM, tapeHeight, textMeasurer, styles)
    }
}

private fun DrawScope.drawRings(
    observer: Offset,
    pxPerKm: Float,
    rangeKm: Double,
    chartTop: Float,
    textMeasurer: TextMeasurer,
    styles: DialTextStyles,
) {
    val step = DialGeometry.ringStepKm(rangeKm)
    val farthestPx = hypot(size.width / 2, observer.y)
    var i = 1
    while (step * i * pxPerKm <= farthestPx) {
        val km = step * i
        val radius = (km * pxPerKm).toFloat()
        drawCircle(RingGray, radius = radius, center = observer, style = Stroke(width = 3.dp.toPx()))
        val label = textMeasurer.measure(DialGeometry.ringLabel(km), styles.ringLabel)
        val y = observer.y - radius - label.size.height - 2.dp.toPx()
        if (y >= chartTop) drawText(label, topLeft = Offset(observer.x - label.size.width / 2f, y))
        i++
    }
}

private class PlacedPeak(
    val mountain: NearbyMountain,
    val position: Offset,
    val label: TextLayoutResult,
    /** アイコンと山名を合わせた範囲。重なりの判定とタップの当たり判定に使う。 */
    val box: Box,
)

/** 直近に描いた山。描画のたびに差し替え、タップ位置から山を引く。 */
private class HitTargets {
    var peaks: List<PlacedPeak> = emptyList()

    /** 現在地の山頂アイコンと山名。山と重なっても優先する。 */
    var summit: PlacedPeak? = null

    /** 双眼鏡の範囲。山頂アイコンを描いているときは null。山と重なっても優先する。 */
    var observer: Box? = null

    /** [tap] が双眼鏡に当たったか。枠を [slop] だけ広げて判定する。 */
    fun hitsObserver(tap: Offset, slop: Float): Boolean = observer?.contains(tap, slop) ?: false

    /** [tap] を含む山のうち、アイコンが最も近いもの。枠を [slop] だけ広げて判定する。 */
    fun find(tap: Offset, slop: Float): NearbyMountain? {
        fun PlacedPeak.hit() = box.contains(tap, slop)
        summit?.takeIf { it.hit() }?.let { return it.mountain }
        return peaks.filter { it.hit() }.minByOrNull { (it.position - tap).getDistanceSquared() }?.mountain
    }
}

private fun Box.contains(p: Offset, slop: Float) =
    p.x in left - slop..right + slop && p.y in top - slop..bottom + slop

private fun DrawScope.drawPeaks(
    observer: Offset,
    pxPerKm: Float,
    headingDeg: Double,
    mountains: List<NearbyMountain>,
    chartTop: Float,
    textMeasurer: TextMeasurer,
    styles: DialTextStyles,
    maxPeaks: Int,
): List<PlacedPeak> {
    val gap = 2.dp.toPx()

    val visible = mountains.asSequence()
        .map { m ->
            val o = DialGeometry.project(m.distanceKm, m.bearingDeg, headingDeg)
            m to Offset(observer.x + (o.x * pxPerKm).toFloat(), observer.y - (o.y * pxPerKm).toFloat())
        }
        .filter { (_, p) -> p.x in 0f..size.width && p.y - PeakIcon.MAX_HEIGHT_DP.dp.toPx() >= chartTop && p.y < observer.y }
        .map { (m, p) ->
            val icon = PeakIcon.of(m.mountain.elevationClass())
            val halfWidth = icon.halfWidthDp.dp.toPx()
            val label = textMeasurer.measure(m.mountain.name, styles.label)
            val labelHalf = label.size.width / 2f
            val box = Box(
                left = min(p.x - halfWidth, p.x - labelHalf),
                top = p.y - icon.heightDp.dp.toPx(),
                right = max(p.x + halfWidth, p.x + labelHalf),
                bottom = p.y + gap + label.size.height,
            )
            PlacedPeak(m, p, label, box)
        }
        .toList()

    val placed = declutter(visible, limit = maxPeaks) { it.box }

    for (peak in placed) {
        val p = peak.position
        drawPeakIcon(p, PeakIcon.of(peak.mountain.mountain.elevationClass()))
        drawText(peak.label, topLeft = Offset(p.x - peak.label.size.width / 2f, p.y + gap))
    }
    return placed
}

/** 標高の区分ごとの山アイコンの大きさ(dp)。底辺の中点が山の位置に来る。 */
private enum class PeakIcon(val halfWidthDp: Float, val heightDp: Float) {
    /** 1000m 未満(標高不明を含む): 黄緑の低い丘。 */
    HILL(halfWidthDp = 10f, heightDp = 11f),

    /** 1000m 以上 2000m 未満: 黄色の ▲ を緑で縁取る。 */
    PEAK(halfWidthDp = 11f, heightDp = 18f),

    /** 2000m 以上: 茶色の高く尖った ▲ に白い雪の冠。濃い茶色で縁取る。 */
    ALPINE(halfWidthDp = 12f, heightDp = 25f),
    ;

    companion object {
        val MAX_HEIGHT_DP = entries.maxOf { it.heightDp }

        fun of(cls: ElevationClass): PeakIcon = when (cls) {
            ElevationClass.LOW -> HILL
            ElevationClass.MIDDLE -> PEAK
            ElevationClass.HIGH -> ALPINE
        }
    }
}

private fun DrawScope.drawPeakIcon(p: Offset, icon: PeakIcon) {
    val halfWidth = icon.halfWidthDp.dp.toPx()
    val height = icon.heightDp.dp.toPx()
    // 3 種類とも同じ太さの縁取りにそろえる。
    val outline = Stroke(width = OUTLINE_WIDTH.toPx())
    when (icon) {
        PeakIcon.HILL -> {
            // 底辺を直径とする半楕円。縁取りで背景のベージュから浮かせる。
            val topLeft = Offset(p.x - halfWidth, p.y - height)
            val oval = Size(halfWidth * 2, height * 2)
            drawArc(HillGreen, startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = topLeft, size = oval)
            drawArc(
                PeakGreen,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = topLeft,
                size = oval,
                style = outline,
            )
        }
        PeakIcon.PEAK -> {
            drawTriangle(p, halfWidth, height, PeakYellow)
            drawTriangle(p, halfWidth, height, PeakGreen, outline)
        }
        PeakIcon.ALPINE -> {
            drawTriangle(p, halfWidth, height, PeakBrown)
            // 頂上から高さの 35% を白く塗って雪を表す。相似な三角形なので幅も同じ比率。
            val snow = 0.35f
            drawTriangle(Offset(p.x, p.y - height * (1 - snow)), halfWidth * snow, height * snow, SnowWhite)
            drawTriangle(p, halfWidth, height, PeakBrownDark, outline)
        }
    }
}

/** 山アイコンの縁取りの太さ。 */
private val OUTLINE_WIDTH = 1.5.dp

/** 底辺の中点を [bottomCenter] とする二等辺三角形。[style] を渡すと線で描く。 */
private fun DrawScope.drawTriangle(
    bottomCenter: Offset,
    halfWidth: Float,
    height: Float,
    color: Color,
    style: DrawStyle = Fill,
) {
    val path = polygon(
        listOf(
            Offset(bottomCenter.x, bottomCenter.y - height),
            Offset(bottomCenter.x + halfWidth, bottomCenter.y),
            Offset(bottomCenter.x - halfWidth, bottomCenter.y),
        ),
    )
    drawPath(path, color, style = style)
}

/** [points] を順に結んで閉じた多角形。Offset は value class で vararg にできないため List で受ける。 */
private fun polygon(points: List<Offset>): Path = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (p in points.drop(1)) lineTo(p.x, p.y)
    close()
}

/**
 * 現在地がほぼ山頂のときに双眼鏡の代わりに描く、赤い旗を立てた灰色の岩山と山名。
 * 方位盤の山アイコンと見分けられる形にし、双眼鏡と同じ視野の扇形を前方へ描く。
 * 山名は右側に白い下地付きで置く(下は画面の端、上は方位盤のため)。タップの当たり判定用の範囲を返す。
 */
private fun DrawScope.drawSummit(
    center: Offset,
    summit: NearbyMountain,
    textMeasurer: TextMeasurer,
    styles: DialTextStyles,
): PlacedPeak {
    val u = 1.dp.toPx()
    fun at(x: Float, y: Float) = Offset(center.x + x * u, center.y + y * u)

    val rock = polygon(listOf(at(-16f, 10f), at(-8f, -2f), at(-4f, 1f), at(2f, -8f), at(16f, 10f)))
    // 日の当たる面。右の尾根を明るくして立体に見せる。
    val lit = polygon(listOf(at(2f, -8f), at(16f, 10f), at(7f, 10f)))
    val flag = polygon(listOf(at(2f, -24f), at(13f, -20.5f), at(2f, -17f)))
    val poleTop = at(2f, -24f)
    val poleBottom = at(2f, -8f)

    drawViewCone(at(0f, -6f))

    // 白い縁取り → 本体の順に描く。
    val halo = Stroke(width = 4f * u, join = StrokeJoin.Round)
    drawPath(rock, Color.White, style = halo)
    drawPath(flag, Color.White, style = halo)
    drawLine(Color.White, poleTop, poleBottom, strokeWidth = 5f * u, cap = StrokeCap.Round)
    drawPath(rock, SummitRock)
    drawPath(lit, SummitRockLight)
    drawLine(FlagPole, poleTop, poleBottom, strokeWidth = 2f * u, cap = StrokeCap.Round)
    drawPath(flag, NorthRed)

    val label = textMeasurer.measure(summit.mountain.name, styles.label)
    val padX = 5f * u
    val padY = 2f * u
    val labelLeft = center.x + 22f * u
    val labelTop = center.y - 4f * u - label.size.height / 2f
    drawRoundRect(
        Color.White.copy(alpha = 0.85f),
        topLeft = Offset(labelLeft - padX, labelTop - padY),
        size = Size(label.size.width + padX * 2, label.size.height + padY * 2),
        cornerRadius = CornerRadius(4f * u),
    )
    drawText(label, topLeft = Offset(labelLeft, labelTop))

    val box = Box(
        left = center.x - 18f * u,
        top = min(center.y - 26f * u, labelTop - padY),
        right = labelLeft + label.size.width + padX,
        bottom = max(center.y + 12f * u, labelTop + label.size.height + padY),
    )
    return PlacedPeak(summit, center, label, box)
}

/**
 * 向いている方位(画面の上)を示す視野。[apex] から前方へ扇形に広がり、遠くほど薄くなる。
 * 双眼鏡と山頂アイコンで共通に使う。
 */
private fun DrawScope.drawViewCone(apex: Offset) {
    val reach = 70.dp.toPx()
    val halfAngle = 22f
    drawArc(
        brush = Brush.radialGradient(
            colors = listOf(LensBlue.copy(alpha = 0.35f), LensBlue.copy(alpha = 0f)),
            center = apex,
            radius = reach,
        ),
        startAngle = -90f - halfAngle,
        sweepAngle = halfAngle * 2,
        useCenter = true,
        topLeft = Offset(apex.x - reach, apex.y - reach),
        size = Size(reach * 2, reach * 2),
    )
}

/**
 * 現在地を表す双眼鏡。対物レンズを上(向いている方位)に向け、前方へ広がる視野を薄く描いて
 * 「前を覗いている」ように見せる。同心円や山と重なっても埋もれないよう、白い縁取りを付ける。
 * タップの当たり判定用に、白い縁取りまで含めた範囲を返す。
 */
private fun DrawScope.drawBinoculars(center: Offset): Box {
    val u = 1.dp.toPx()

    /** 中心からのずれ(dp)で矩形を描く。[grow] だけ四方に広げる。 */
    fun part(x: Float, top: Float, width: Float, bottom: Float, corner: Float, color: Color, grow: Float = 0f) {
        drawRoundRect(
            color,
            topLeft = Offset(center.x + (x - width / 2 - grow) * u, center.y + (top - grow) * u),
            size = Size((width + grow * 2) * u, (bottom - top + grow * 2) * u),
            cornerRadius = CornerRadius((corner + grow) * u),
        )
    }

    fun body(color: Color, grow: Float) {
        for (side in listOf(-1f, 1f)) {
            val x = side * 10f
            part(x, top = -13f, width = 15f, bottom = 3f, corner = 5f, color = color, grow = grow) // 対物部
            part(x, top = 1f, width = 9f, bottom = 12f, corner = 3f, color = color, grow = grow) // 接眼部
        }
        part(0f, top = -1f, width = 8f, bottom = 6f, corner = 2f, color = color, grow = grow) // ブリッジ
    }

    drawViewCone(Offset(center.x, center.y - 10f * u))
    body(Color.White, grow = 2f)
    body(BinocularBody, grow = 0f)
    drawCircle(BinocularHinge, radius = 3f * u, center = Offset(center.x, center.y + 2.5f * u))
    for (side in listOf(-1f, 1f)) {
        // 前を向いたレンズ面を斜め後ろから見た楕円。
        val lens = Offset(center.x + side * 10f * u, center.y - 10.5f * u)
        drawOval(LensBlue, topLeft = lens - Offset(5.5f * u, 2.5f * u), size = Size(11f * u, 5f * u))
        drawOval(
            Color.White.copy(alpha = 0.8f),
            topLeft = lens + Offset(-3.5f * u, -1.5f * u),
            size = Size(3f * u, 1.4f * u),
        )
    }
    return Box(left = center.x - 20f * u, top = center.y - 15f * u, right = center.x + 20f * u, bottom = center.y + 14f * u)
}

/** 画面上部の方位目盛り。向いている方位が中央に来る。 */
private fun DrawScope.drawTape(headingDeg: Double, tapeHeight: Float, textMeasurer: TextMeasurer) {
    val center = size.width / 2
    val stroke = 3.dp.toPx()
    for (tick in DialGeometry.tapeTicks(headingDeg, TAPE_SPAN_DEG)) {
        val x = center + (tick.offsetDeg / TAPE_SPAN_DEG * size.width).toFloat()
        val cardinal = DialGeometry.cardinalLabel(tick.angleDeg)
        if (cardinal != null) {
            val style = TextStyle(
                color = if (cardinal == "N") NorthRed else Color.Black,
                fontSize = if (cardinal.length == 1) 22.sp else 15.sp,
                fontWeight = FontWeight.Bold,
            )
            val label = textMeasurer.measure(cardinal, style)
            drawText(label, topLeft = Offset(x - label.size.width / 2f, (tapeHeight - label.size.height) / 2))
        } else {
            val length = if (tick.angleDeg % 10 == 0) tapeHeight * 0.75f else tapeHeight * 0.45f
            drawLine(Color.Black, Offset(x, 0f), Offset(x, length), strokeWidth = stroke)
        }
    }
}

/** 目盛りの下に、中央を指す赤い印と「北東 45°　標高 312m」の表示(標高は分かるときだけ)。 */
private fun DrawScope.drawReadout(
    headingDeg: Double,
    altitudeM: Double?,
    tapeHeight: Float,
    textMeasurer: TextMeasurer,
    styles: DialTextStyles,
) {
    val center = size.width / 2
    val caret = 6.dp.toPx()
    drawPath(
        polygon(
            listOf(
                Offset(center, tapeHeight),
                Offset(center + caret, tapeHeight + caret),
                Offset(center - caret, tapeHeight + caret),
            ),
        ),
        NorthRed,
    )
    val deg = headingDeg.roundToInt() % 360
    val altitude = altitudeM?.let { String.format(java.util.Locale.US, "　標高 %,dm", Math.round(it)) } ?: ""
    val label = textMeasurer.measure("${Heading.directionName(headingDeg)} $deg°$altitude", styles.readout)
    drawText(label, topLeft = Offset(center - label.size.width / 2f, tapeHeight + caret + 2.dp.toPx()))
}

@Preview(widthDp = 320, heightDp = 560)
@Composable
private fun DialCanvasPreview() {
    fun peak(name: String, ele: Double, km: Double, bearing: Double) =
        NearbyMountain(Mountain(name.hashCode().toLong(), name, 0.0, 0.0, ele), km, bearing)
    DialCanvas(
        headingDeg = 0.0,
        mountains = listOf(
            peak("□□山", 1212.0, 8.0, 20.0),
            peak("○○山", 560.0, 12.5, -18.0),
            peak("○×山", 122.0, 3.5, -20.0),
            peak("△△岳", 2456.0, 15.0, 5.0),
        ),
        rangeKm = DialGeometry.DEFAULT_RANGE_KM,
        modifier = Modifier.fillMaxSize().background(DialBeige),
    )
}
