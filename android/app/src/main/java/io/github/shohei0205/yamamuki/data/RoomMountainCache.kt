package io.github.shohei0205.yamamuki.data

import io.github.shohei0205.yamamuki.core.BoundingBox
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.MountainCache
import io.github.shohei0205.yamamuki.core.PeakDataset
import io.github.shohei0205.yamamuki.core.TagGroup
import io.github.shohei0205.yamamuki.core.TagGroups
import io.github.shohei0205.yamamuki.core.PointGraphic
import io.github.shohei0205.yamamuki.core.Tile

/** 山データの種類 [dataset] の山を、DB(Room)に保存する。ほかの種類の山には触れない。 */
class RoomMountainCache(private val dao: MountainDao, val dataset: PeakDataset) : MountainCache {
    private val id = dataset.id

    override suspend fun fetchedAt(tiles: Collection<Tile>): Map<Tile, Long> {
        if (tiles.isEmpty()) return emptyMap()
        val wanted = tiles.toSet()
        return dao.tilesIn(
            dataset = id,
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
        dao.mountainsIn(id, box.south, box.west, box.north, box.east).map { it.toMountain() }

    /** 保存しているすべての山。山の検索で使う。 */
    suspend fun allMountains(): List<Mountain> = dao.allMountains(id).map { it.toMountain() }

    /** 保存しているすべての山のタグから作った、山のグループの一覧(名前順)。 */
    suspend fun peakGroups(): List<TagGroup> {
        val converter = StringListConverter()
        return TagGroups.of(dao.tagColumns(id).map(converter::toList))
    }

    override suspend fun replaceTiles(tiles: Collection<Tile>, mountains: List<Mountain>, fetchedAtMillis: Long) {
        dao.replaceTiles(
            dataset = id,
            tiles = tiles.map { FetchedTileEntity(id, it.latIndex, it.lonIndex, fetchedAtMillis) },
            mountains = mountains.map { it.toEntity(id) },
        )
    }

    /** 保存している山の数。 */
    suspend fun count(): Int = dao.countMountains(id)

    /** この種類の山と取得済みのタイルをすべて消す。 */
    suspend fun clear() = dao.clearAll(id)
}

private fun MountainEntity.toMountain() = Mountain(
    id = pointId,
    name = name,
    latitude = latitude,
    longitude = longitude,
    elevationM = elevationM,
    nameReading = nameReading,
    aliases = aliases,
    wikipediaUrl = wikipediaUrl,
    wikidataUrl = wikidataUrl,
    tags = tags,
    graphic = graphicSvg?.let { PointGraphic(it, graphicScale ?: 1.0) },
)

private fun Mountain.toEntity(dataset: String): MountainEntity {
    val tile = Tile.of(latitude, longitude)
    return MountainEntity(
        dataset = dataset,
        pointId = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        elevationM = elevationM,
        tileLat = tile.latIndex,
        tileLon = tile.lonIndex,
        nameReading = nameReading,
        aliases = aliases,
        wikipediaUrl = wikipediaUrl,
        wikidataUrl = wikidataUrl,
        tags = tags,
        graphicSvg = graphic?.svg,
        graphicScale = graphic?.scale,
    )
}
