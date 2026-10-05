package io.github.shohei0205.yamamuki.data

import io.github.shohei0205.yamamuki.core.BoundingBox
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.MountainCache
import io.github.shohei0205.yamamuki.core.Tile

class RoomMountainCache(private val dao: MountainDao) : MountainCache {

    override suspend fun fetchedAt(tiles: Collection<Tile>): Map<Tile, Long> {
        if (tiles.isEmpty()) return emptyMap()
        val wanted = tiles.toSet()
        return dao.tilesIn(
            minLat = tiles.minOf { it.latIndex },
            maxLat = tiles.maxOf { it.latIndex },
            minLon = tiles.minOf { it.lonIndex },
            maxLon = tiles.maxOf { it.lonIndex },
        )
            .map { Tile(it.tileLat, it.tileLon) to it.fetchedAtMillis }
            .filter { it.first in wanted }
            .toMap()
    }

    override suspend fun mountainsIn(box: BoundingBox): List<Mountain> =
        dao.mountainsIn(box.south, box.west, box.north, box.east).map {
            Mountain(it.osmId, it.name, it.latitude, it.longitude, it.elevationM)
        }

    override suspend fun replaceTiles(tiles: Collection<Tile>, mountains: List<Mountain>, fetchedAtMillis: Long) {
        dao.replaceTiles(
            tiles = tiles.map { FetchedTileEntity(it.latIndex, it.lonIndex, fetchedAtMillis) },
            mountains = mountains.map {
                val tile = Tile.of(it.latitude, it.longitude)
                MountainEntity(it.osmId, it.name, it.latitude, it.longitude, it.elevationM, tile.latIndex, tile.lonIndex)
            },
        )
    }
}
