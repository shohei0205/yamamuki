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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shohei0205.yamamuki.core.Box
import io.github.shohei0205.yamamuki.core.DialGeometry
import io.github.shohei0205.yamamuki.core.ElevationClass
import io.github.shohei0205.yamamuki.core.Heading
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.PlanOffset
import io.github.shohei0205.yamamuki.core.RingLabelGeometry
import io.github.shohei0205.yamamuki.core.PanGeometry
import io.github.shohei0205.yamamuki.core.MapCenter
import io.github.shohei0205.yamamuki.core.PeakLayout
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.core.elevationClass
import kotlin.math.ceil
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
private val FanShade = Color(0xFF7A6F45)
private val FanEdge = Color(0xFFC9B35A)
internal val TapeInk = Color(0xFF2E3A40)
internal val TapeSubtle = Color(0xFF6B7178)

/** 方位目盛りの 10° ごとと 5° ごとの線の長さ。 */
private const val TAPE_MAJOR_TICK_DP = 14f
private const val TAPE_MINOR_TICK_DP = 8f

/** 画面上部の方位目盛りに収める角度の幅。 */
private const val TAPE_SPAN_DEG = DialGeometry.TAPE_SPAN_DEG

/**
 * 方位盤の文字。設定の文字サイズ([scale])を山名・距離の目盛り・方位の表示に掛ける。
 * 上端の方位目盛りは高さが決まっているので倍率を掛けない。
 */
private class DialTextStyles(scale: Float) {
    val label = TextStyle(color = Color.Black, fontSize = 13.sp * scale, fontWeight = FontWeight.Bold)
    val others = TextStyle(color = TapeSubtle, fontSize = 11.sp * scale, fontWeight = FontWeight.Bold)
    val ringLabel = TextStyle(color = RingGray, fontSize = 12.sp * scale, fontWeight = FontWeight.Bold)
    val readout = TextStyle(color = Color.Black, fontSize = 15.sp * scale, fontWeight = FontWeight.Bold)
}

/**
 * 方位盤。現在地(画面下部の双眼鏡)から向いている方向を上にとり、山をアイコンと山名で描く。
 * アイコンの色と形は標高の区分([ElevationClass])で変える。
 * 表示する山は、現在地から見上げる角度(仰角)の大きい順に選び、すぐそばの山どうしは標高の高いほうを残す([PeakLayout])。
 * 画面に描くときは前回描いた山を先に置くので、向きを変えても、描いている山は実際に重なるまで消えない。
 * 重なって山名を省いた山はアイコンだけを描き、代表の山の山名の下に「ほか 3 山」と添える。
 * 描いた山(アイコンか山名)をタップすると [onMountainTap] を呼ぶ。代表の山なら、まとめた山を含む一覧を [onGroupTap] に渡す。
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
    /** 重なる山をまとめた代表の山をタップしたとき。代表の山を先頭に、まとめた山を優先順に並べて渡す。 */
    onGroupTap: (List<NearbyMountain>) -> Unit = {},
    onObserverTap: () -> Unit = {},
    /** 現在地がほぼ山頂のとき、その山。 */
    summit: NearbyMountain? = null,
    /** 現在地の標高(海抜)。方位の表示の後ろに添え、山の仰角の計算にも使う。null なら出さない(仰角は 0m とみなす)。 */
    altitudeM: Double? = null,
    /** 一度に表示する山の上限。 */
    maxPeaks: Int = 40,
    /** 文字の大きさ(標準 = 1.0 に対する倍率)。 */
    textScale: Float = 1f,
    latitude: Double? = null,
    longitude: Double? = null,
    viewportLatitude: Double? = latitude,
    viewportLongitude: Double? = longitude,
    compassHeadingDeg: Double = headingDeg,
    headingUp: Boolean = true,
    /** 上部の方位目盛りの引っ込み具合。0 で表示(ヘディングアップ)、1 でヘッダーの下端へ隠れる(手動位置モード)。 */
    tapeHidden: Float = 0f,
    /** 現在地から画面上部へ広がる視野の扇の濃さ(0〜1)。双眼鏡の短い視野は残りの (1 - 濃さ) で描く。 */
    viewFanAlpha: Float = 1f,
    /** 画面下端の余白(ジェスチャーバーなど)の高さ。視野の扇の外側の暗さだけを、ここまで描き足す。 */
    bottomBleed: Dp = 0.dp,
) {
    val styles = remember(textScale) { DialTextStyles(textScale) }
    val texts = rememberDialTexts()
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)
    val hitTargets = remember { HitTargets() }
    val peakSelection = remember { PeakSelection() }
    val currentOnTap by rememberUpdatedState(onMountainTap)
    val currentOnGroupTap by rememberUpdatedState(onGroupTap)
    val currentOnObserverTap by rememberUpdatedState(onObserverTap)
    val tapModifier = Modifier.pointerInput(Unit) {
        val slop = 8.dp.toPx()
        detectTapGestures { tap ->
            if (hitTargets.hitsObserver(tap, slop)) {
                currentOnObserverTap()
            } else {
                hitTargets.find(tap, slop)?.let { peak ->
                    if (peak.members.isEmpty()) currentOnTap(peak.mountain) else currentOnGroupTap(listOf(peak.mountain) + peak.members)
                }
            }
        }
    }
    // 視野の扇だけを画面下端の余白まで描くので、全体では切り抜かず、扇以外を描く範囲で切り抜く。
    Canvas(modifier.then(tapModifier)) {
        val headerHeight = DialGeometry.HEADER_HEIGHT_DP.dp.toPx()
        val tapeHeight = DialGeometry.TAPE_HEIGHT_DP.dp.toPx()
        val chartTop = DialGeometry.CHART_TOP_DP.dp.toPx()
        val origin = Offset(size.width / 2, size.height - DialGeometry.ORIGIN_BOTTOM_DP.dp.toPx())
        val pxPerKm = ((origin.y - chartTop) / rangeKm).toFloat()
        val offset = if (latitude != null && longitude != null && viewportLatitude != null && viewportLongitude != null)
            PanGeometry.observerOffset(MapCenter(latitude, longitude), MapCenter(viewportLatitude, viewportLongitude), headingDeg)
        else PlanOffset(0.0, 0.0)
        val observer = origin + Offset((offset.x * pxPerKm).toFloat(), (-offset.y * pxPerKm).toFloat())
        val observerRotation = Heading.delta(headingDeg, compassHeadingDeg).toFloat()
        if (viewFanAlpha > 0f) {
            clipRect(top = headerHeight + tapeHeight, bottom = size.height + bottomBleed.toPx()) {
                // 扇は画面の真上に固定する。手動位置モードへ切り替えて消える間も、端末の向きにつられて回らない。
                drawViewFan(observer, viewFanAlpha)
            }
        }
        clipRect {
            val coneAlpha = 1f - viewFanAlpha
            hitTargets.peaks = if (pxPerKm > 0f) {
                val previousAngle = RingLabelGeometry.rotatedAngle(hitTargets.ringLabelAngle, hitTargets.ringLabelHeading, headingDeg)
                hitTargets.ringLabelAngle = drawRings(observer, pxPerKm, rangeKm, chartTop, textMeasurer, styles, previousAngle, headingUp)
                hitTargets.ringLabelHeading = headingDeg
                drawPeaks(observer, pxPerKm, headingDeg, mountains, altitudeM, chartTop, textMeasurer, styles, texts, maxPeaks, peakSelection)
            } else {
                emptyList()
            }
            if (summit != null) {
                hitTargets.summit = drawSummit(observer, summit, textMeasurer, styles, observerRotation, coneAlpha)
                hitTargets.observer = null
            } else {
                hitTargets.observerCenter = observer
                hitTargets.observerRotation = observerRotation
                rotate(observerRotation, pivot = observer) {
                    hitTargets.observer = drawBinoculars(observer, coneAlpha)
                }
                hitTargets.summit = null
            }
            // 上部の青空と山並みは、地図の上に重ねる。手動位置モードで地図を動かしても、双眼鏡などがヘッダーに重ならない。
            drawHeaderScenery(DialBeige)
            if (tapeHidden < 1f) {
                // 目盛りはヘッダーの下に置き、手動位置モードではヘッダーの下端で切って、ヘッダーに重ねずに消す。
                // 方位の表示の文字は大きくできるので、目盛りの帯より長めに動かして隠しきる。
                clipRect(top = headerHeight) {
                    translate(top = headerHeight - tapeHidden * (chartTop - headerHeight) * 1.5f) {
                        drawTape(headingDeg, tapeHeight, textMeasurer)
                        drawReadout(headingDeg, altitudeM, tapeHeight, textMeasurer, styles, texts)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawRings(
    observer: Offset,
    pxPerKm: Float,
    rangeKm: Double,
    chartTop: Float,
    textMeasurer: TextMeasurer,
    styles: DialTextStyles,
    previousAngle: Double?,
    headingUp: Boolean,
): Double {
    val step = DialGeometry.ringStepKm(rangeKm)
    val farthestPx = hypot(max(kotlin.math.abs(observer.x), kotlin.math.abs(size.width - observer.x)),
        max(kotlin.math.abs(chartTop - observer.y), kotlin.math.abs(size.height - observer.y)))
    val nearestPx = hypot(max(0f, max(-observer.x, observer.x - size.width)),
        max(0f, max(chartTop - observer.y, observer.y - size.height)))
    var i = max(1, (nearestPx / (step * pxPerKm)).toInt())
    val rings = mutableListOf<Pair<Float, TextLayoutResult>>()
    var angle = -Math.PI / 2
    val pad = 3.dp.toPx()
    clipRect(top = chartTop, bottom = size.height - DialGeometry.ORIGIN_BOTTOM_DP.dp.toPx()) {
        while (step * i * pxPerKm <= farthestPx) {
            val km = step * i
            val radius = (km * pxPerKm).toFloat()
            drawCircle(RingGray, radius = radius, center = observer, style = Stroke(width = 3.dp.toPx()))
            val label = textMeasurer.measure(DialGeometry.ringLabel(km), styles.ringLabel.copy(color = Color(0xFF666666)))
            rings += radius to label
            i++
        }
        fun placements(direction: Double): List<Triple<TextLayoutResult, Offset, Box>> {
            val labels = mutableListOf<Triple<TextLayoutResult, Offset, Box>>()
            for ((radius, label) in rings) {
            val anchor = RingLabelGeometry.place(observer.x.toDouble(), observer.y.toDouble(), radius.toDouble(),
                0.0, chartTop.toDouble(), size.width.toDouble(), (size.height - DialGeometry.ORIGIN_BOTTOM_DP.dp.toPx()).toDouble(),
                (label.size.width + pad * 2).toDouble(), (label.size.height + pad * 2).toDouble(),
                direction)
            if (anchor != null) {
                val textOrigin = Offset(anchor.x.toFloat() - label.size.width / 2f, anchor.y.toFloat() - label.size.height / 2f)
                val box = Box(textOrigin.x - pad, textOrigin.y - pad,
                    textOrigin.x + label.size.width + pad, textOrigin.y + label.size.height + pad)
                if (labels.none { it.third.intersects(box) }) labels += Triple(label, textOrigin, box)
            }
            }
            return labels
        }
        if (!headingUp) angle = RingLabelGeometry.direction(observer.x.toDouble(), observer.y.toDouble(),
            0.0, chartTop.toDouble(), size.width.toDouble(), (size.height - DialGeometry.ORIGIN_BOTTOM_DP.dp.toPx()).toDouble(),
            previousAngle, visibleCount = { placements(it).size })
        for ((label, origin, box) in placements(angle)) {
            drawRoundRect(Color.White.copy(alpha = 0.85f), topLeft = Offset(box.left, box.top),
                size = Size(box.right - box.left, box.bottom - box.top), cornerRadius = CornerRadius(pad, pad))
            drawText(label, topLeft = origin)
        }
    }
    return angle
}

private class PlacedPeak(
    val mountain: NearbyMountain,
    val position: Offset,
    val label: TextLayoutResult,
    /** アイコンと山名を合わせた範囲。重なりの判定とタップの当たり判定に使う。 */
    val box: Box,
    /** 重なるので山名を省き、この山にまとめた山(優先順)。 */
    val members: List<NearbyMountain> = emptyList(),
)

/** 直近に描いた山。描画のたびに差し替え、タップ位置から山を引く。 */
private class HitTargets {
    var ringLabelAngle: Double? = null
    var ringLabelHeading: Double? = null
    var peaks: List<PlacedPeak> = emptyList()

    /** 現在地の山頂アイコンと山名。山と重なっても優先する。 */
    var summit: PlacedPeak? = null

    /** 双眼鏡の範囲。山頂アイコンを描いているときは null。山と重なっても優先する。 */
    var observer: Box? = null
    var observerCenter = Offset.Zero
    var observerRotation = 0f

    /** [tap] が双眼鏡に当たったか。枠を [slop] だけ広げて判定する。 */
    fun hitsObserver(tap: Offset, slop: Float): Boolean {
        val angle = Math.toRadians(-observerRotation.toDouble())
        val delta = tap - observerCenter
        val localTap = observerCenter + Offset(
            (delta.x * kotlin.math.cos(angle) - delta.y * kotlin.math.sin(angle)).toFloat(),
            (delta.x * kotlin.math.sin(angle) + delta.y * kotlin.math.cos(angle)).toFloat(),
        )
        return observer?.contains(localTap, slop) ?: false
    }

    /** [tap] を含む山のうち、アイコンが最も近いもの。枠を [slop] だけ広げて判定する。 */
    fun find(tap: Offset, slop: Float): PlacedPeak? {
        fun PlacedPeak.hit() = box.contains(tap, slop)
        summit?.takeIf { it.hit() }?.let { return it }
        return peaks.filter { it.hit() }.minByOrNull { (it.position - tap).getDistanceSquared() }
    }
}

private fun Box.contains(p: Offset, slop: Float) =
    p.x in left - slop..right + slop && p.y in top - slop..bottom + slop

/** 選んだ山の 1 件。位置は現在地からの px(北が上)、範囲は山の位置を原点にしたもの。 */
private class SelectedPeak(
    val mountain: NearbyMountain,
    val label: TextLayoutResult,
    val box: Box,
)

/**
 * 方位盤に出す山の候補。向きによらずに周り全体から選び、山の一覧・表示範囲などが変わったときだけ選び直す。
 * 前回選んだ山と前回描いた山を覚えておき、境目にある山が出たり消えたりしないようにする。
 */
private class PeakSelection {
    var key: List<Any?>? = null
    var selected: List<SelectedPeak> = emptyList()
    var selectedIds: Set<Long> = emptySet()
    var drawnIds: Set<Long> = emptySet()
}

/** 選び直すかどうかを決める、現在地から画面の角までの距離の刻み。地図を少し動かしただけでは選び直さない。 */
private val REACH_STEP = 64.dp

/** 新しく画面に出す山に求める、ほかの山との余白。境目で出たり消えたりしないようにする。 */
private val NEW_PEAK_MARGIN = 4.dp

private fun DrawScope.drawPeaks(
    observer: Offset,
    pxPerKm: Float,
    headingDeg: Double,
    mountains: List<NearbyMountain>,
    observerAltitudeM: Double?,
    chartTop: Float,
    textMeasurer: TextMeasurer,
    styles: DialTextStyles,
    texts: DialTexts,
    maxPeaks: Int,
    selection: PeakSelection,
): List<PlacedPeak> {
    val gap = 2.dp.toPx()
    val chartBottom = size.height - DialGeometry.ORIGIN_BOTTOM_DP.dp.toPx()

    // 山の位置が画面に入りうる、現在地からの最大の距離(画面の最も遠い角まで)。
    val farthestCorner = listOf(Offset(0f, chartTop), Offset(size.width, chartTop), Offset(0f, chartBottom), Offset(size.width, chartBottom))
        .maxOf { (it - observer).getDistance() }
    val step = REACH_STEP.toPx()
    val reachPx = ceil(farthestCorner / step) * step

    val key = listOf(mountains, pxPerKm, observerAltitudeM, styles, maxPeaks, reachPx, size.width, chartBottom - chartTop)
    if (selection.key != key) {
        val viewArea = size.width.toDouble() * (chartBottom - chartTop)
        selection.selected = PeakLayout.candidates(
            mountains, observerAltitudeM, selection.selectedIds,
            reachKm = (reachPx / pxPerKm).toDouble(),
            limit = PeakLayout.aroundLimit(maxPeaks, reachPx.toDouble(), viewArea),
        ).map { m ->
            val icon = PeakIcon.of(m.mountain.elevationClass())
            val halfWidth = icon.halfWidthDp.dp.toPx()
            val label = textMeasurer.measure(m.mountain.name, styles.label)
            val labelHalf = label.size.width / 2f
            val box = Box(
                left = min(-halfWidth, -labelHalf),
                top = -icon.heightDp.dp.toPx(),
                right = max(halfWidth, labelHalf),
                bottom = gap + label.size.height,
            )
            SelectedPeak(m, label, box)
        }
        selection.selectedIds = selection.selected.mapTo(HashSet()) { it.mountain.mountain.osmId }
        selection.key = key
    }

    val visible = selection.selected
        .map { s ->
            val o = DialGeometry.project(s.mountain.distanceKm, s.mountain.bearingDeg, headingDeg)
            s to Offset(observer.x + (o.x * pxPerKm).toFloat(), observer.y - (o.y * pxPerKm).toFloat())
        }
        .filter { (_, p) -> p.x in 0f..size.width && p.y - PeakIcon.MAX_HEIGHT_DP.dp.toPx() >= chartTop && p.y < chartBottom }
        .map { (s, p) ->
            PlacedPeak(s.mountain, p, s.label, Box(p.x + s.box.left, p.y + s.box.top, p.x + s.box.right, p.y + s.box.bottom))
        }
    val groups = PeakLayout.placeVisible(
        visible,
        limit = maxPeaks,
        box = { it.box },
        drawnBefore = { it.mountain.mountain.osmId in selection.drawnIds },
        neighbors = { a, b -> PeakLayout.areNeighbors(a.mountain, b.mountain) },
        elevationM = { it.mountain.mountain.elevationM },
        margin = NEW_PEAK_MARGIN.toPx(),
    )
    selection.drawnIds = groups.mapTo(HashSet()) { it.peak.mountain.mountain.osmId }

    // 代表の山の山名と「ほか 3 山」の場所を先に決め、まとめた山のアイコンはそこを避けて描く。
    val placedBoxes = groups.map { it.peak.box }
    val othersLabels = groups.map { group ->
        val peak = group.peak
        if (group.members.isEmpty()) return@map null
        // 「ほか 3 山」は山名の下に添える。ほかの山の山名と重なるときは添えない(タップすれば一覧は出る)。
        val others = textMeasurer.measure(texts.others(group.members.size), styles.others)
        val p = peak.position
        val top = p.y + gap + peak.label.size.height
        val half = others.size.width / 2f
        val below = Box(min(peak.box.left, p.x - half), top, max(peak.box.right, p.x + half), top + others.size.height)
        if (placedBoxes.any { it !== peak.box && it.intersects(below) }) null else others to below
    }
    val textBoxes = groups.map { it.peak.labelBox(gap) } + othersLabels.mapNotNull { it?.second }

    // まとめた山のアイコンは薄く描き、代表の山のアイコンと山名を上に重ねる。標高が不明な山と、
    // どれかの山名や「ほか 3 山」にかかる山は描かない(一覧には残る)。描いたアイコンを押すと一覧を開く。
    val memberTargets = mutableListOf<PlacedPeak>()
    for (group in groups) {
        val members = group.members.map { it.mountain }
        for (member in group.members) {
            if (member.mountain.mountain.elevationM == null) continue
            val icon = PeakIcon.of(member.mountain.mountain.elevationClass())
            val p = member.position
            val halfWidth = icon.halfWidthDp.dp.toPx()
            val iconBox = Box(p.x - halfWidth, p.y - icon.heightDp.dp.toPx(), p.x + halfWidth, p.y)
            if (textBoxes.any { it.intersects(iconBox) }) continue
            drawPeakIcon(p, icon, alpha = MEMBER_ICON_ALPHA)
            memberTargets += PlacedPeak(group.peak.mountain, p, group.peak.label, iconBox, members)
        }
    }
    val reps = groups.mapIndexed { i, group ->
        val peak = group.peak
        val p = peak.position
        drawPeakIcon(p, PeakIcon.of(peak.mountain.mountain.elevationClass()))
        drawText(peak.label, topLeft = Offset(p.x - peak.label.size.width / 2f, p.y + gap))
        var box = peak.box
        othersLabels[i]?.let { (others, below) ->
            drawText(others, topLeft = Offset(p.x - others.size.width / 2f, below.top))
            box = Box(min(box.left, below.left), box.top, max(box.right, below.right), below.bottom)
        }
        PlacedPeak(peak.mountain, p, peak.label, box, group.members.map { it.mountain })
    }
    return reps + memberTargets
}

/** 山名の範囲。 */
private fun PlacedPeak.labelBox(gap: Float): Box {
    val half = label.size.width / 2f
    return Box(position.x - half, position.y + gap, position.x + half, position.y + gap + label.size.height)
}

/** 代表の山にまとめた山のアイコンの濃さ。代表の山と見分けられるように薄くする。 */
private const val MEMBER_ICON_ALPHA = 0.45f

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

private fun DrawScope.drawPeakIcon(p: Offset, icon: PeakIcon, alpha: Float = 1f) {
    val halfWidth = icon.halfWidthDp.dp.toPx()
    val height = icon.heightDp.dp.toPx()
    // 3 種類とも同じ太さの縁取りにそろえる。
    val outline = Stroke(width = OUTLINE_WIDTH.toPx())
    when (icon) {
        PeakIcon.HILL -> {
            // 底辺を直径とする半楕円。縁取りで背景のベージュから浮かせる。
            val topLeft = Offset(p.x - halfWidth, p.y - height)
            val oval = Size(halfWidth * 2, height * 2)
            drawArc(HillGreen, startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = topLeft, size = oval, alpha = alpha)
            drawArc(
                PeakGreen,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = topLeft,
                size = oval,
                alpha = alpha,
                style = outline,
            )
        }
        PeakIcon.PEAK -> {
            drawTriangle(p, halfWidth, height, PeakYellow, alpha = alpha)
            drawTriangle(p, halfWidth, height, PeakGreen, outline, alpha)
        }
        PeakIcon.ALPINE -> {
            drawTriangle(p, halfWidth, height, PeakBrown, alpha = alpha)
            // 頂上から高さの 35% を白く塗って雪を表す。相似な三角形なので幅も同じ比率。
            val snow = 0.35f
            drawTriangle(Offset(p.x, p.y - height * (1 - snow)), halfWidth * snow, height * snow, SnowWhite, alpha = alpha)
            drawTriangle(p, halfWidth, height, PeakBrownDark, outline, alpha)
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
    alpha: Float = 1f,
) {
    val path = polygon(
        listOf(
            Offset(bottomCenter.x, bottomCenter.y - height),
            Offset(bottomCenter.x + halfWidth, bottomCenter.y),
            Offset(bottomCenter.x - halfWidth, bottomCenter.y),
        ),
    )
    drawPath(path, color, alpha = alpha, style = style)
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
    observerRotation: Float,
    coneAlpha: Float,
): PlacedPeak {
    val u = 1.dp.toPx()
    fun at(x: Float, y: Float) = Offset(center.x + x * u, center.y + y * u)

    val rock = polygon(listOf(at(-16f, 10f), at(-8f, -2f), at(-4f, 1f), at(2f, -8f), at(16f, 10f)))
    // 日の当たる面。右の尾根を明るくして立体に見せる。
    val lit = polygon(listOf(at(2f, -8f), at(16f, 10f), at(7f, 10f)))
    val flag = polygon(listOf(at(2f, -24f), at(13f, -20.5f), at(2f, -17f)))
    val poleTop = at(2f, -24f)
    val poleBottom = at(2f, -8f)

    if (coneAlpha > 0f) rotate(observerRotation, pivot = center) { drawViewCone(at(0f, -6f), coneAlpha) }

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
private fun DrawScope.drawViewCone(apex: Offset, alpha: Float) {
    val reach = 70.dp.toPx()
    val halfAngle = 22f
    drawArc(
        brush = Brush.radialGradient(
            colors = listOf(LensBlue.copy(alpha = 0.35f * alpha), LensBlue.copy(alpha = 0f)),
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
 * ヘディングアップで、上部の方位目盛りと同じ幅([TAPE_SPAN_DEG])の視野。[apex] から画面の外まで扇を広げ、
 * 扇の外側をうっすら暗くする。山や同心円より下に描き、山名を隠さない。
 */
private fun DrawScope.drawViewFan(apex: Offset, alpha: Float) {
    val reach = hypot(size.width, size.height) * 2
    val half = Math.toRadians(TAPE_SPAN_DEG / 2).toFloat()
    val left = apex + Offset(-reach * kotlin.math.sin(half), -reach * kotlin.math.cos(half))
    val right = apex + Offset(reach * kotlin.math.sin(half), -reach * kotlin.math.cos(half))
    val outside = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(apex.x - reach, apex.y - reach, apex.x + reach, apex.y + reach))
        addPath(polygon(listOf(apex, left, right)))
    }
    drawPath(outside, FanShade.copy(alpha = 0.16f * alpha))
    val edge = Path().apply {
        moveTo(left.x, left.y)
        lineTo(apex.x, apex.y)
        lineTo(right.x, right.y)
    }
    drawPath(edge, FanEdge.copy(alpha = alpha), style = Stroke(width = 1.5f.dp.toPx(), join = StrokeJoin.Round))
}

/**
 * 現在地を表す双眼鏡。対物レンズを上(向いている方位)に向け、前方へ広がる視野を薄く描いて
 * 「前を覗いている」ように見せる。同心円や山と重なっても埋もれないよう、白い縁取りを付ける。
 * タップの当たり判定用に、白い縁取りまで含めた範囲を返す。
 */
private fun DrawScope.drawBinoculars(center: Offset, coneAlpha: Float): Box {
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

    if (coneAlpha > 0f) drawViewCone(Offset(center.x, center.y - 10f * u), coneAlpha)
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
    val half = TAPE_SPAN_DEG / 2
    val baseline = 1.5.dp.toPx()
    // 帯は中央ほど明るく、左右の端で背景に溶かす。帯の両端は視野の扇の縁と同じ方位なので、下端の線も扇の縁と同じ金色にする。
    drawRect(
        Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f))),
        size = Size(size.width, tapeHeight),
    )
    drawRect(
        Brush.horizontalGradient(listOf(FanEdge.copy(alpha = 0f), FanEdge, FanEdge.copy(alpha = 0f))),
        topLeft = Offset(0f, tapeHeight - baseline),
        size = Size(size.width, baseline),
    )
    val labelBottom = tapeHeight - baseline - TAPE_MAJOR_TICK_DP.dp.toPx()
    for (tick in DialGeometry.tapeTicks(headingDeg, TAPE_SPAN_DEG)) {
        val x = center + (tick.offsetDeg / TAPE_SPAN_DEG * size.width).toFloat()
        // 線と数字は端へ行くほど少し薄くして、中央の方位に目が行くようにする。東西南北の文字は薄くしない。
        val edge = (kotlin.math.abs(tick.offsetDeg) / half).toFloat().coerceAtMost(1f)
        val ink = TapeInk.copy(alpha = 1f - 0.4f * edge * edge)
        val major = tick.angleDeg % 10 == 0
        val length = (if (major) TAPE_MAJOR_TICK_DP else TAPE_MINOR_TICK_DP).dp.toPx()
        drawLine(
            ink,
            Offset(x, tapeHeight - baseline),
            Offset(x, tapeHeight - baseline - length),
            strokeWidth = (if (major) 2.5f else 1.5f).dp.toPx(),
            cap = StrokeCap.Round,
        )
        val cardinal = DialGeometry.cardinalLabel(tick.angleDeg)
        val style = when {
            cardinal != null -> TextStyle(
                color = if (cardinal == "N") NorthRed else TapeInk,
                fontSize = if (cardinal.length == 1) 20.sp else 14.sp,
                fontWeight = FontWeight.Bold,
            )
            tick.angleDeg % 30 == 0 -> TextStyle(color = TapeSubtle.copy(alpha = ink.alpha), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            else -> null
        } ?: continue
        val label = textMeasurer.measure(cardinal ?: tick.angleDeg.toString(), style)
        drawText(label, topLeft = Offset(x - label.size.width / 2f, (labelBottom - label.size.height) / 2 + 1.dp.toPx()))
    }
}

/** 目盛りの中央を指す赤い印と、その下の淡い白の札に「北東 45°　標高 312m」(標高は分かるときだけ)。 */
private fun DrawScope.drawReadout(
    headingDeg: Double,
    altitudeM: Double?,
    tapeHeight: Float,
    textMeasurer: TextMeasurer,
    styles: DialTextStyles,
    texts: DialTexts,
) {
    val center = size.width / 2
    val caret = 5.dp.toPx()
    drawPath(
        polygon(
            listOf(
                Offset(center, tapeHeight - 2 * caret),
                Offset(center + caret, tapeHeight),
                Offset(center - caret, tapeHeight),
            ),
        ),
        NorthRed,
    )
    val (direction, altitude) = texts.readoutParts(headingDeg, altitudeM)
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append(direction)
        if (altitude.isNotEmpty()) {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = TapeSubtle))
            append(altitude)
            pop()
        }
    }
    val label = textMeasurer.measure(text, styles.readout.copy(color = TapeInk))
    val padX = 12.dp.toPx()
    val padY = 3.dp.toPx()
    val pill = Size(label.size.width + padX * 2, label.size.height + padY * 2)
    val topLeft = Offset(center - pill.width / 2, tapeHeight + 4.dp.toPx())
    val radius = CornerRadius(pill.height / 2)
    // 札は目盛りの帯と同じくらいの淡い白にして、帯より目立たせない。同心円と重なっても文字が読める程度の濃さは残す。
    drawRoundRect(Color.White.copy(alpha = 0.5f), topLeft, pill, radius)
    drawText(label, topLeft = topLeft + Offset(padX, padY))
}

/** 16 方位の名前の文字列リソース。[Heading.directionIndex] の番号の順(北から時計回り)。 */
private val DIRECTION_NAMES = listOf(
    R.string.direction_n, R.string.direction_nne, R.string.direction_ne, R.string.direction_ene,
    R.string.direction_e, R.string.direction_ese, R.string.direction_se, R.string.direction_sse,
    R.string.direction_s, R.string.direction_ssw, R.string.direction_sw, R.string.direction_wsw,
    R.string.direction_w, R.string.direction_wnw, R.string.direction_nw, R.string.direction_nnw,
)

/** 方位盤に描く文言。描画(DrawScope)の中では stringResource を呼べないので、先に文字列リソースから読んでおく。 */
internal class DialTexts(
    private val directionNames: List<String>,
    /** 「ほか %1$d 山」。 */
    private val othersFormat: String,
    /** 「　標高 %1$,dm」。 */
    private val altitudeFormat: String,
) {
    /** 16 方位の名前(北、北北東、…)。 */
    fun direction(headingDeg: Double): String = directionNames[Heading.directionIndex(headingDeg)]

    /** 重なって山名を省いた山の数を、代表の山の山名の下に添える文言。「ほか 3 山」。 */
    fun others(count: Int): String = String.format(othersFormat, count)

    /** 方位(「北東 45°」)と標高(「　標高 312m」、分からなければ空)に分けたもの。 */
    fun readoutParts(headingDeg: Double, altitudeM: Double?): Pair<String, String> {
        val deg = headingDeg.roundToInt() % 360
        val altitude = altitudeM?.let { String.format(altitudeFormat, Math.round(it)) } ?: ""
        return "${direction(headingDeg)} $deg°" to altitude
    }
}

@Composable
internal fun rememberDialTexts(): DialTexts {
    val names = DIRECTION_NAMES.map { stringResource(it) }
    val others = stringResource(R.string.dial_others)
    val altitude = stringResource(R.string.dial_altitude)
    return remember(names, others, altitude) { DialTexts(names, others, altitude) }
}

@Preview(widthDp = 320, heightDp = 560)
@Composable
private fun DialCanvasPreview() {
    fun peak(name: String, ele: Double, km: Double, bearing: Double) =
        NearbyMountain(Mountain(name.hashCode().toLong(), name, 0.0, 0.0, ele), km, bearing)
    DialCanvas(
        headingDeg = 0.0,
        mountains = listOf(
            // プレビュー用の仮の山名なので、文字列リソースにしない。
            peak("□□山", 1212.0, 8.0, 20.0), // 文言チェック対象外
            peak("○○山", 560.0, 12.5, -18.0), // 文言チェック対象外
            peak("○×山", 122.0, 3.5, -20.0), // 文言チェック対象外
            peak("△△岳", 2456.0, 15.0, 5.0), // 文言チェック対象外
        ),
        rangeKm = DialGeometry.DEFAULT_RANGE_KM,
        modifier = Modifier.fillMaxSize().background(DialBeige),
    )
}
