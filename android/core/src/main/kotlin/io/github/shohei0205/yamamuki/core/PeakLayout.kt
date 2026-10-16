package io.github.shohei0205.yamamuki.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** 画面上の矩形(px)。 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun intersects(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * 表示の優先度のスコア。現在地から山を見上げる角度(仰角、°)。
 * 近くの低い山も、遠くの高い山も、見かけの高さで比べる。地平線の下に沈む遠くの山は低くなる。
 * 標高が分からない山は、いちばん後ろに回す。
 * [observerAltitudeM] は現在地の標高(海抜)。分からなければ 0m とみなす。
 */
fun NearbyMountain.displayScore(observerAltitudeM: Double?): Double {
    val ele = mountain.elevationM ?: return Double.NEGATIVE_INFINITY
    return GeoMath.elevationAngleDeg(observerAltitudeM ?: 0.0, ele, distanceKm)
}

/**
 * 山の持つタグ(「日本百名山」などの分類ラベル)の数。同じタグの重なりは 1 つと数える。
 * 方位盤では、タグの多い山ほど山名を出しやすくする([PeakLayout.TAG_BONUS_DEG])。タグの無い山データでは、どの山も 0。
 */
val Mountain.tagCount: Int get() = if (tags.isEmpty()) 0 else tags.filter { it.isNotEmpty() }.toSet().size

/** 表示の優先順: 仰角の大きい順、標高不明は後ろ、同じなら近い順。 */
fun displayPriority(observerAltitudeM: Double?): Comparator<NearbyMountain> =
    compareByDescending<NearbyMountain> { it.displayScore(observerAltitudeM) }
        .thenBy { it.distanceKm }

/** 画面に描く山 1 件([peak])と、その山に重なるので山名を省き、アイコンだけを描く山([members]、優先順)。 */
data class PeakGroup<T>(val peak: T, val members: List<T>)

/**
 * 方位盤に出す山の選び方。2 段階で決める。
 *
 * 1. 候補を選ぶ([candidates])。画面より一回り大きい、画面の周りの円から仰角の大きい順に選ぶ。
 *    現在地の周り全体から選ぶと、ほかの方角の山に候補の枠を取られ、向けた先に山が出ないことがある。
 *    山の一覧・表示範囲・文字の大きさなどが変わったときと、向きを変えたり地図を動かしたりして画面が円からはみ出したときだけ選び直す。
 *    前回描いた山は、円の中にある限り候補に残す。
 *    タグ([tagCount])の付いた山は、タグの数だけ優先する([TAG_BONUS_DEG])。
 *    ヘディングアップでは、正面に近い山ほど優先する([forwardBonus])。表示範囲を広げるほど、高い山を優先する([heightBonus])。
 * 2. 画面に描く山を決める([placeVisible])。描くたびに、画面に入る候補を今の向きで重ならないように並べる。
 *    重なる山は山名を省いてアイコンだけを残し、代表の山にまとめる。タグの多い山を代表にし、タグの数が同じなら、
 *    すぐそばの山どうしは標高の高いほうを代表にする。
 *    タグの数が同じなら前回描いた山を先に置くので、向きを変えても、描いている山が後から入ってきた山に押し出されない。
 *    新しく出す山は少し余白をとって判定し、境目で出たり消えたりしないようにする。
 */
object PeakLayout {
    /** 前回選んだ山の仰角に上乗せする値(°)。GPS の標高の揺れで、順位が入れ替わって点滅しないようにする。 */
    const val KEPT_BONUS_DEG = 0.3

    /**
     * 同じ山塊の主峰と肩・前衛峰とみなす、山どうしの距離(km)。
     * 奥穂高岳とジャンダルム(約 0.4km)、奥穂高岳と前穂高岳(約 1.5km)、槍ヶ岳と中岳(約 1km)が入り、
     * 尾根続きでも別の山として数えたい隣の峰(数 km 先)は入りにくい長さにする。
     */
    const val NEIGHBOR_KM = 3.0

    /**
     * ヘディングアップで、正面にある山の仰角に上乗せする値(°)。正面から離れるほど減らし、
     * 視野の扇の端([FORWARD_SPAN_DEG])で 0 にする。向けた先の山が、横の山に押し出されにくくする。
     */
    const val FORWARD_BONUS_DEG = 1.0

    /** [FORWARD_BONUS_DEG] を上乗せする、正面からの角度の範囲(°)。視野の扇の片側の幅。 */
    const val FORWARD_SPAN_DEG = DialGeometry.TAPE_SPAN_DEG / 2

    /** 正面([headingDeg])からのずれに応じた上乗せ(°)。正面で [FORWARD_BONUS_DEG]、[FORWARD_SPAN_DEG] より外で 0。 */
    fun forwardBonus(bearingDeg: Double, headingDeg: Double): Double =
        FORWARD_BONUS_DEG * max(0.0, 1 - abs(Heading.delta(headingDeg, bearingDeg)) / FORWARD_SPAN_DEG)

    /**
     * ヘディングアップで表示範囲を広げたときに、標高 1000m あたり仰角に上乗せする値の最大(°)。
     * 遠くまで表示しているときは、近くの低い山より遠くの高い山の名前を知りたいことが多いため。
     */
    const val HEIGHT_BONUS_DEG_PER_KM = 2.0

    /** 標高の上乗せを始める表示範囲(km)。これより狭いときは上乗せしない。 */
    const val HEIGHT_BONUS_START_KM = 20.0

    /** 標高の上乗せを最大にする表示範囲(km)。[HEIGHT_BONUS_START_KM] からここまで、だんだん増やす。 */
    const val HEIGHT_BONUS_FULL_KM = 60.0

    /** 表示範囲([rangeKm])に応じた、標高([elevationM])の上乗せ(°)。標高が分からない山は 0。 */
    fun heightBonus(elevationM: Double?, rangeKm: Double): Double {
        val elevation = elevationM ?: return 0.0
        val weight = ((rangeKm - HEIGHT_BONUS_START_KM) / (HEIGHT_BONUS_FULL_KM - HEIGHT_BONUS_START_KM)).coerceIn(0.0, 1.0)
        return weight * HEIGHT_BONUS_DEG_PER_KM * max(0.0, elevation) / 1000
    }

    /**
     * タグ(「日本百名山」など)1 つあたり、仰角に上乗せする値(°)。タグの付いた山の山名を出しやすくする。
     * 一律に先にはせず、すぐ近くの大きく見える山や正面の山とは見かけの高さで比べる。
     */
    const val TAG_BONUS_DEG = 3.0

    /**
     * 選ぶ順。[displayPriority] の順に、タグの数([tagCount])× [TAG_BONUS_DEG] を上乗せし、
     * 前回選んだ山([keptIds]、OSM の ID)は [KEPT_BONUS_DEG] だけ上乗せする。
     * ヘディングアップでは向いている方角([headingDeg])と表示範囲([rangeKm])を渡し、
     * 正面に近い山に [forwardBonus] を、表示範囲が広いときは高い山に [heightBonus] を上乗せする。
     */
    fun priorityOrder(
        mountains: List<NearbyMountain>,
        observerAltitudeM: Double?,
        keptIds: Set<Long> = emptySet(),
        headingDeg: Double? = null,
        rangeKm: Double? = null,
    ): List<NearbyMountain> {
        fun score(m: NearbyMountain) =
            m.displayScore(observerAltitudeM) + (if (m.mountain.osmId in keptIds) KEPT_BONUS_DEG else 0.0) +
                TAG_BONUS_DEG * m.mountain.tagCount +
                (headingDeg?.let { forwardBonus(m.bearingDeg, it) } ?: 0.0) +
                (rangeKm?.let { heightBonus(m.mountain.elevationM, it) } ?: 0.0)
        return mountains.map { it to score(it) }
            .sortedWith(compareByDescending<Pair<NearbyMountain, Double>> { it.second }.thenBy { it.first.distanceKm })
            .map { it.first }
    }

    /** [a] と [b] が [NEIGHBOR_KM] 以内にある、同じ山塊の山か。 */
    fun areNeighbors(a: NearbyMountain, b: NearbyMountain): Boolean =
        GeoMath.distanceKm(a.mountain.latitude, a.mountain.longitude, b.mountain.latitude, b.mountain.longitude) <= NEIGHBOR_KM

    /**
     * 候補の山。[priorityOrder] の順で、円の中心から [reachKm] 以内(画面に入りうる距離)の山を最大 [limit] 件。
     * 円の中心は、現在地から [centerBearingDeg] の方角へ [centerKm] 離れた点。0 なら現在地。
     * 前回描いた山([drawnIds])は、円の中にあれば上限によらず残す(選び直したときに、描いている山が消えないようにする)。
     * [headingDeg] は [priorityOrder] に渡す(ヘディングアップのときだけ)。
     */
    fun candidates(
        mountains: List<NearbyMountain>,
        observerAltitudeM: Double?,
        keptIds: Set<Long>,
        reachKm: Double,
        limit: Int,
        centerKm: Double = 0.0,
        centerBearingDeg: Double = 0.0,
        drawnIds: Set<Long> = emptySet(),
        headingDeg: Double? = null,
    ): List<NearbyMountain> {
        val inReach = priorityOrder(mountains, observerAltitudeM, keptIds, headingDeg)
            .filter { planeDistanceKm(it.distanceKm, it.bearingDeg, centerKm, centerBearingDeg) <= reachKm }
        var room = limit.coerceAtLeast(0) - inReach.count { it.mountain.osmId in drawnIds }
        return inReach.filter { it.mountain.osmId in drawnIds || room-- > 0 }
    }

    /** 現在地から見た 2 点(距離 km と方角)の、方位盤の平面上での距離(km)。 */
    fun planeDistanceKm(aKm: Double, aBearingDeg: Double, bKm: Double, bBearingDeg: Double): Double {
        val delta = Math.toRadians(Heading.delta(aBearingDeg, bBearingDeg))
        return sqrt(max(0.0, aKm * aKm + bKm * bKm - 2 * aKm * bKm * cos(delta)))
    }

    /**
     * 候補の円から選ぶ山の上限。画面に [maxPeaks] 件までの密度になるよう、
     * 半径 [reachPx] の円の面積と画面の面積 [viewAreaPx] の比で増やす。
     */
    fun aroundLimit(maxPeaks: Int, reachPx: Double, viewAreaPx: Double): Int {
        if (viewAreaPx <= 0 || maxPeaks <= 0) return maxPeaks.coerceAtLeast(0)
        val ratio = max(1.0, PI * reachPx * reachPx / viewAreaPx)
        return ceil(maxPeaks * ratio).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    /**
     * 画面に描く山を決める。画面に入る山([visible]、優先順)を、今の向きの範囲([box]、画面上の位置)で重ならないように置く。
     *
     * - すぐそばの山どうし([neighbors])が今重なるときは、タグ([tagCount])の多い順、タグの数が同じなら標高([elevationM])の高い順に決め、
     *   先の山を残してあとの山を省く。どの順で山が来ても同じ結果になる。
     * - 残りはタグの多い順に、タグの数が同じなら前回描いた山([drawnBefore])を先に、それぞれ優先順に置き、置いた山と重なる山を省く。最大 [limit] 件。
     *   優先度の高い山が画面の端から入ってきたり、向きが変わったりしても、描いている山は、タグの多い山と重なるか、実際に重なるまで消えない。
     * - 新しく出す山は、範囲を [margin] だけ広げて判定する(境目で出たり消えたりしないようにする)。
     * - 省いた山は、重なっている置いた山(すぐそばの高い山があればその山)の [PeakGroup.members] に入れる。
     *   上限で省いた山など、どの山とも重ならない山は入れない。
     *
     * 返す順は優先順のまま。
     */
    fun <T> placeVisible(
        visible: List<T>,
        limit: Int,
        box: (T) -> Box,
        drawnBefore: (T) -> Boolean,
        neighbors: (T, T) -> Boolean,
        elevationM: (T) -> Double?,
        margin: Float = 0f,
        tagCount: (T) -> Int = { 0 },
    ): List<PeakGroup<T>> {
        val boxes = visible.map(box)
        val drawn = visible.map(drawnBefore)
        val heights = visible.map { elevationM(it) ?: Double.NEGATIVE_INFINITY }
        val tags = visible.map(tagCount)
        // タグの多い山が先、タグの数が同じなら標高の高い山が先。
        fun before(j: Int, i: Int) = tags[j] > tags[i] || (tags[j] == tags[i] && heights[j] > heights[i])
        fun testBox(i: Int): Box = boxes[i].let { b ->
            if (drawn[i]) b else Box(b.left - margin, b.top - margin, b.right + margin, b.bottom + margin)
        }

        // そばの山どうしは、タグの多い順、標高の高い順に決める。すでに残した先の山と今重なる山は省く。
        val dominator = IntArray(visible.size) { -1 }
        val kept = mutableListOf<Int>()
        for (i in visible.indices.sortedWith(compareByDescending<Int> { tags[it] }.thenByDescending { heights[it] })) {
            val d = kept.firstOrNull { j ->
                before(j, i) && boxes[j].intersects(testBox(i)) && neighbors(visible[i], visible[j])
            }
            if (d != null) dominator[i] = d else kept += i
        }

        val free = visible.indices.filter { dominator[it] < 0 }
        val placed = mutableListOf<Int>()
        for (i in free.sortedWith(compareByDescending<Int> { tags[it] }.thenBy { if (drawn[it]) 0 else 1 })) {
            if (placed.size >= limit) break
            val b = testBox(i)
            if (placed.none { boxes[it].intersects(b) }) placed += i
        }

        val placedSet = placed.toHashSet()
        val members = HashMap<Int, MutableList<Int>>()
        for (i in visible.indices) {
            if (i in placedSet) continue
            val b = testBox(i)
            val owner = dominator[i].takeIf { it in placedSet } ?: placed.firstOrNull { boxes[it].intersects(b) }
            if (owner != null) members.getOrPut(owner) { mutableListOf() } += i
        }
        return visible.indices.filter { it in placedSet }.map { i ->
            PeakGroup(visible[i], members[i].orEmpty().sorted().map { visible[it] })
        }
    }
}
