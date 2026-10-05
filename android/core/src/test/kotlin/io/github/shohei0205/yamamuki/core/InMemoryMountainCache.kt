package io.github.shohei0205.yamamuki.core

class InMemoryMountainCache : MountainCache {
    val tiles = mutableMapOf<Tile, Long>()
    val mountains = mutableMapOf<Long, Mountain>()

    override suspend fun fetchedAt(tiles: Collection<Tile>): Map<Tile, Long> =
        tiles.mapNotNull { t -> this.tiles[t]?.let { t to it } }.toMap()

    override suspend fun mountainsIn(box: BoundingBox): List<Mountain> =
        mountains.values.filter { box.contains(it.latitude, it.longitude) }

    override suspend fun replaceTiles(tiles: Collection<Tile>, mountains: List<Mountain>, fetchedAtMillis: Long) {
        val set = tiles.toSet()
        this.mountains.values.removeAll { Tile.of(it.latitude, it.longitude) in set }
        mountains.forEach { this.mountains[it.osmId] = it }
        set.forEach { this.tiles[it] = fetchedAtMillis }
    }
}
