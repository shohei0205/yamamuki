package io.github.shohei0205.yamamuki.core

import kotlin.math.floor

/**
 * キャッシュの単位となる緯度経度グリッドのタイル。
 * 取得済みかどうかをタイル単位で記録し、同じ範囲を何度も Overpass に問い合わせないようにする。
 */
data class Tile(val latIndex: Int, val lonIndex: Int) {
    val bounds: BoundingBox
        get() = BoundingBox(
            south = latIndex * SIZE_DEG,
            west = lonIndex * SIZE_DEG,
            north = (latIndex + 1) * SIZE_DEG,
            east = (lonIndex + 1) * SIZE_DEG,
        )

    companion object {
        /** 0.5° ≒ 南北 55km。半径 100km の範囲で 5×6 枚程度。 */
        const val SIZE_DEG = 0.5

        fun of(lat: Double, lon: Double): Tile =
            Tile(floor(lat / SIZE_DEG).toInt(), floor(lon / SIZE_DEG).toInt())

        fun covering(box: BoundingBox): List<Tile> {
            val sw = of(box.south, box.west)
            val ne = of(box.north, box.east)
            return (sw.latIndex..ne.latIndex).flatMap { la ->
                (sw.lonIndex..ne.lonIndex).map { lo -> Tile(la, lo) }
            }
        }

        /** タイル群をすべて含む最小の矩形。 */
        fun union(tiles: Collection<Tile>): BoundingBox {
            require(tiles.isNotEmpty())
            return BoundingBox(
                south = tiles.minOf { it.latIndex } * SIZE_DEG,
                west = tiles.minOf { it.lonIndex } * SIZE_DEG,
                north = (tiles.maxOf { it.latIndex } + 1) * SIZE_DEG,
                east = (tiles.maxOf { it.lonIndex } + 1) * SIZE_DEG,
            )
        }
    }
}
