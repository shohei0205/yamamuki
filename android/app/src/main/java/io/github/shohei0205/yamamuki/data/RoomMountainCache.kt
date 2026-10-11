package io.github.shohei0205.yamamuki.data

import io.github.shohei0205.yamamuki.core.BoundingBox
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.MountainCache
import io.github.shohei0205.yamamuki.core.PointGraphic
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
        dao.mountainsIn(box.south, box.west, box.north, box.east).map { it.toMountain() }

    /** 保存しているすべての山。山の検索で使う。 */
    suspend fun allMountains(): List<Mountain> = dao.allMountains().map { it.toMountain() }

    override suspend fun replaceTiles(tiles: Collection<Tile>, mountains: List<Mountain>, fetchedAtMillis: Long) {
        dao.replaceTiles(
            tiles = tiles.map { FetchedTileEntity(it.latIndex, it.lonIndex, fetchedAtMillis) },
            mountains = mountains.map { it.toEntity() },
        )
    }
}

private fun MountainEntity.toMountain() = Mountain(
    osmId = osmId,
    name = name,
    latitude = latitude,
    longitude = longitude,
    elevationM = elevationM,
    pointId = pointId,
    nameReading = nameReading,
    aliases = aliases,
    wikipediaUrl = wikipediaUrl,
    wikidataUrl = wikidataUrl,
    tags = tags,
    graphic = graphicSvg?.let { PointGraphic(it, graphicScale ?: 1.0) },
)

private fun Mountain.toEntity(): MountainEntity {
    val tile = Tile.of(latitude, longitude)
    return MountainEntity(
        osmId = osmId,
        name = name,
        latitude = latitude,
        longitude = longitude,
        elevationM = elevationM,
        tileLat = tile.latIndex,
        tileLon = tile.lonIndex,
        pointId = pointId,
        nameReading = nameReading,
        aliases = aliases,
        wikipediaUrl = wikipediaUrl,
        wikidataUrl = wikidataUrl,
        tags = tags,
        graphicSvg = graphic?.svg,
        graphicScale = graphic?.scale,
    )
}
