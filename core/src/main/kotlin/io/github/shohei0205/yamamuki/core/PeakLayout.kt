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
 * 方位盤に出す山の選び方。
 *
 * 端末の向きを変えるたびに選び直すと、画面の端の出入りや回転による重なり方の変化で山が点滅する。
 * そこで、地図をどの向きに回しても重ならない組み合わせを、現在地の周り全体(360°)から選んでおく。
 * 選び直すのは、山の一覧・表示範囲・文字の大きさなどが変わったときだけにする。
 * 画面には、選んだ山のうち画面に入るものを描くだけにする。
 */
object PeakLayout {
    /** 前回選んだ山の仰角に上乗せする値(°)。GPS の標高の揺れで、順位が入れ替わって点滅しないようにする。 */
    const val KEPT_BONUS_DEG = 0.3

    /** 前回どちらも選んだ 2 つの山は、重なりの判定の距離をこの割合に縮め、少し近づいても残す。 */
    const val KEPT_CLEARANCE_RATIO = 0.9

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
     * 地図をどの向きに回しても重ならないように、優先順の [items] から先に置いたものと重なるものを捨て、最大 [limit] 件を返す。
     * [position] は現在地からの位置(px、向きは問わない)、[box] は山の位置を原点にした範囲。
     * [keptBefore] が true のもの同士は、重なりの判定を [KEPT_CLEARANCE_RATIO] だけ甘くする。
     * [limit] 件に達したら残りは取り出さないので、遅延評価の列を渡せば以降の山名の計測などを省ける。
     */
    fun <T> selectAround(
        items: Sequence<T>,
        limit: Int = Int.MAX_VALUE,
        position: (T) -> PlanOffset,
        box: (T) -> Box,
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
            val clear = placed.none { p ->
                val ratio = if (kept && p.kept) KEPT_CLEARANCE_RATIO else 1.0
                hypot(at.x - p.at.x, at.y - p.at.y) < clearance(b, p.box) * ratio
            }
            if (clear) placed += Placed(item, at, b, kept)
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
     * 画面に入る山([visible]、優先順)を [limit] 件までに絞る。前回描いた山([drawnBefore])を先に残し、
     * 優先度の高い山が画面の端から入ってきても、描いている山を押し出さないようにする。返す順は優先順のまま。
     */
    fun <T> capVisible(visible: List<T>, limit: Int, drawnBefore: (T) -> Boolean): List<T> {
        if (visible.size <= limit) return visible
        val (old, new) = visible.indices.partition { drawnBefore(visible[it]) }
        val keep = (old + new).take(limit).toSet()
        return visible.filterIndexed { i, _ -> i in keep }
    }
}
