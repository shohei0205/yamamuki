package io.github.shohei0205.yamamuki.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

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

/** 表示の優先順: 仰角の大きい順、標高不明は後ろ、同じなら近い順。 */
fun displayPriority(observerAltitudeM: Double?): Comparator<NearbyMountain> =
    compareByDescending<NearbyMountain> { it.displayScore(observerAltitudeM) }
        .thenBy { it.distanceKm }

/**
 * 方位盤に出す山の選び方。2 段階で決める。
 *
 * 1. 候補を選ぶ([selectAround])。山の一覧・表示範囲・文字の大きさなどが変わったときだけ、現在地の周り全体(360°)から選ぶ。
 *    すぐそばにあって、地図の向きによっては重なる山どうし(主峰と肩・前衛峰など)は、標高の高いほうだけを残す。
 * 2. 画面に描く山を決める([placeVisible])。描くたびに、画面に入る候補を今の向きで重ならないように並べる。
 *    前回描いた山を先に置くので、向きを変えても、描いている山が後から入ってきた山に押し出されない。
 *    新しく出す山は少し余白をとって判定し、境目で出たり消えたりしないようにする。
 */
object PeakLayout {
    /** 前回選んだ山の仰角に上乗せする値(°)。GPS の標高の揺れで、順位が入れ替わって点滅しないようにする。 */
    const val KEPT_BONUS_DEG = 0.3

    /** 前回どちらも選んだ 2 つの山は、重なりの判定の距離をこの割合に縮め、少し近づいても残す。 */
    const val KEPT_CLEARANCE_RATIO = 0.9

    /** 同じ山塊の主峰と肩・前衛峰とみなす、山どうしの距離(km)。奥穂高岳とジャンダルム、前穂高岳と明神岳など。 */
    const val NEIGHBOR_KM = 3.0

    /**
     * 選ぶ順。[displayPriority] の順に、前回選んだ山([keptIds]、OSM の ID)は [KEPT_BONUS_DEG] だけ上乗せする。
     */
    fun priorityOrder(
        mountains: List<NearbyMountain>,
        observerAltitudeM: Double?,
        keptIds: Set<Long> = emptySet(),
    ): List<NearbyMountain> {
        fun score(m: NearbyMountain) =
            m.displayScore(observerAltitudeM) + if (m.mountain.osmId in keptIds) KEPT_BONUS_DEG else 0.0
        return mountains.sortedWith(compareByDescending<NearbyMountain> { score(it) }.thenBy { it.distanceKm })
    }

    /** [a] と [b] が [NEIGHBOR_KM] 以内にある、同じ山塊の山か。 */
    fun areNeighbors(a: NearbyMountain, b: NearbyMountain): Boolean =
        GeoMath.distanceKm(a.mountain.latitude, a.mountain.longitude, b.mountain.latitude, b.mountain.longitude) <= NEIGHBOR_KM

    /** [a] が [b] より高い山か。標高が分からない山は、どの山より低いとみなす。 */
    fun isHigher(a: NearbyMountain, b: NearbyMountain): Boolean =
        (a.mountain.elevationM ?: Double.NEGATIVE_INFINITY) > (b.mountain.elevationM ?: Double.NEGATIVE_INFINITY)

    /**
     * 2 つの山の位置がこの距離(px)より近いと、地図の向きによってはアイコンや山名が重なる。
     * [a] と [b] は、それぞれの山の位置を原点にしたアイコンと山名の範囲。山名は地図を回しても水平のまま。
     */
    fun clearance(a: Box, b: Box): Double {
        // b の位置から見た a の位置がこの矩形に入ると重なる。位置の差は地図と一緒に回るので、
        // 矩形の最も遠い角までの距離より近ければ、どこかの向きで重なる。
        val dx = max(abs(a.right - b.left), abs(b.right - a.left))
        val dy = max(abs(a.bottom - b.top), abs(b.bottom - a.top))
        return hypot(dx.toDouble(), dy.toDouble())
    }

    /**
     * 候補の山を選ぶ。優先順の [items] を順に置き、すでに置いた山のうち [neighbors] で、地図の向きによっては
     * 重なるものがあれば、標高の高いほう([higher])だけを残す。高い山が後から来たら、先に置いた低い山と入れ替える。
     * そばにない山どうしは、ここでは間引かない(画面に描くときに [placeVisible] で今の向きで判定する)。
     * [position] は現在地からの位置(px、向きは問わない)、[box] は山の位置を原点にした範囲。
     * [keptBefore] が true のもの同士は、重なりの判定を [KEPT_CLEARANCE_RATIO] だけ甘くする。
     * 最大 [limit] 件を返す。[limit] 件に達したら残りは取り出さないので、遅延評価の列を渡せば以降の山名の計測などを省ける。
     */
    fun <T> selectAround(
        items: Sequence<T>,
        limit: Int = Int.MAX_VALUE,
        position: (T) -> PlanOffset,
        box: (T) -> Box,
        neighbors: (T, T) -> Boolean,
        higher: (T, T) -> Boolean,
        keptBefore: (T) -> Boolean = { false },
    ): List<T> {
        class Placed(val item: T, val at: PlanOffset, val box: Box, val kept: Boolean)
        val placed = mutableListOf<Placed>()
        val iterator = items.iterator()
        while (placed.size < limit && iterator.hasNext()) {
            val item = iterator.next()
            val at = position(item)
            val b = box(item)
            val kept = keptBefore(item)
            val rivals = placed.filter { p ->
                val ratio = if (kept && p.kept) KEPT_CLEARANCE_RATIO else 1.0
                neighbors(item, p.item) && hypot(at.x - p.at.x, at.y - p.at.y) < clearance(b, p.box) * ratio
            }
            if (rivals.all { higher(item, it.item) }) {
                placed.removeAll(rivals)
                placed += Placed(item, at, b, kept)
            }
        }
        return placed.map { it.item }
    }

    /**
     * 周り全体から選ぶ山の上限。画面に [maxPeaks] 件までの密度になるよう、
     * 現在地から [reachPx] の円の面積と画面の面積 [viewAreaPx] の比で増やす。
     */
    fun aroundLimit(maxPeaks: Int, reachPx: Double, viewAreaPx: Double): Int {
        if (viewAreaPx <= 0 || maxPeaks <= 0) return maxPeaks.coerceAtLeast(0)
        val ratio = max(1.0, PI * reachPx * reachPx / viewAreaPx)
        return ceil(maxPeaks * ratio).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    /**
     * 画面に描く山を決める。画面に入る山([visible]、優先順)を、今の向きの範囲([box]、画面上の位置)で重ならないように置き、
     * 最大 [limit] 件を返す。前回描いた山([drawnBefore])を先に置くので、優先度の高い山が画面の端から入ってきたり、
     * 向きが変わったりしても、描いている山は実際に重なるまで消えない。
     * 新しく出す山は、範囲を [margin] だけ広げて判定する(境目で出たり消えたりしないようにする)。返す順は優先順のまま。
     */
    fun <T> placeVisible(
        visible: List<T>,
        limit: Int,
        box: (T) -> Box,
        drawnBefore: (T) -> Boolean,
        margin: Float = 0f,
    ): List<T> {
        val (old, new) = visible.indices.partition { drawnBefore(visible[it]) }
        val drawn = old.toHashSet()
        val placed = mutableListOf<Box>()
        val keep = mutableSetOf<Int>()
        for (i in old + new) {
            if (keep.size >= limit) break
            val b = box(visible[i])
            val test = if (i in drawn) b else Box(b.left - margin, b.top - margin, b.right + margin, b.bottom + margin)
            if (placed.none { it.intersects(test) }) {
                placed += b
                keep += i
            }
        }
        return visible.filterIndexed { i, _ -> i in keep }
    }
}
