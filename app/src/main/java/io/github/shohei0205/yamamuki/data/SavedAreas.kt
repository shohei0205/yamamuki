package io.github.shohei0205.yamamuki.data

import android.content.Context
import io.github.shohei0205.yamamuki.core.Prefecture
import io.github.shohei0205.yamamuki.core.Tile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 事前ダウンロードを終えた地域。 */
data class SavedArea(
    val prefecture: Prefecture,
    val downloadedAtMillis: Long,
    /** ダウンロードした時点の山の数。 */
    val mountainCount: Int,
)

/**
 * 事前ダウンロードした地域の一覧を端末内(SharedPreferences)に保存する。
 * 山データそのものは通常のキャッシュ(Room)に入り、ここでは「どの地域を残すか」だけを覚える。
 */
class SavedAreas(context: Context) {
    private val prefs = context.getSharedPreferences("saved_areas", Context.MODE_PRIVATE)

    private val _areas = MutableStateFlow(load())
    /** 都道府県コード順。 */
    val areas: StateFlow<List<SavedArea>> = _areas.asStateFlow()

    fun put(area: SavedArea) {
        prefs.edit().putString(area.prefecture.code.toString(), "${area.downloadedAtMillis},${area.mountainCount}").apply()
        _areas.value = load()
    }

    fun remove(prefecture: Prefecture) {
        prefs.edit().remove(prefecture.code.toString()).apply()
        _areas.value = load()
    }

    /** 保存済みの地域のタイル。キャッシュを消去しても残す。 */
    fun tiles(): Set<Tile> = _areas.value.flatMap { it.prefecture.tiles }.toSet()

    private fun load(): List<SavedArea> = prefs.all.mapNotNull { (key, value) ->
        val prefecture = key.toIntOrNull()?.let(Prefecture::byCode) ?: return@mapNotNull null
        val parts = (value as? String)?.split(',') ?: return@mapNotNull null
        SavedArea(
            prefecture = prefecture,
            downloadedAtMillis = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null,
            mountainCount = parts.getOrNull(1)?.toIntOrNull() ?: 0,
        )
    }.sortedBy { it.prefecture.code }
}
