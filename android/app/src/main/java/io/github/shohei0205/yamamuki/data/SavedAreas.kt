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
    private val pendingPrefs = context.getSharedPreferences("area_download_pending", Context.MODE_PRIVATE)

    private val _areas = MutableStateFlow(load())
    /** 都道府県の一覧([Prefecture.ALL])と同じ順。 */
    val areas: StateFlow<List<SavedArea>> = _areas.asStateFlow()

    fun put(area: SavedArea) {
        prefs.edit().putString(area.prefecture.code.toString(), "${area.downloadedAtMillis},${area.mountainCount}").apply()
        _areas.value = load()
    }

    fun remove(prefecture: Prefecture) {
        prefs.edit().remove(prefecture.code.toString()).apply()
        _areas.value = load()
    }

    /** 保存済みの地域と途中で終わった地域の記録をすべて消す。山データのキャッシュが作り直されたときに使う。 */
    fun clear() {
        prefs.edit().clear().apply()
        pendingPrefs.edit().clear().apply()
        _areas.value = emptyList()
    }

    /**
     * 途中で終わったダウンロード(アプリを閉じた・失敗した・中断した)。次に開いたときに続きから再開できるよう覚えておく。
     * 値は都道府県と、取り直し(更新)だったか。
     */
    var pending: Pair<Prefecture, Boolean>?
        get() {
            val prefecture = Prefecture.byCode(pendingPrefs.getInt(KEY_PENDING_CODE, 0)) ?: return null
            return prefecture to pendingPrefs.getBoolean(KEY_PENDING_REFRESH, false)
        }
        set(value) {
            pendingPrefs.edit().apply {
                if (value == null) {
                    clear()
                } else {
                    putInt(KEY_PENDING_CODE, value.first.code)
                    putBoolean(KEY_PENDING_REFRESH, value.second)
                }
            }.apply()
        }

    /** 保存済みの地域と、途中で終わった地域のタイル。キャッシュを消去しても残す(続きから再開できるように)。 */
    fun tiles(): Set<Tile> =
        (_areas.value.map { it.prefecture } + listOfNotNull(pending?.first)).flatMap { it.tiles }.toSet()

    private fun load(): List<SavedArea> = prefs.all.mapNotNull { (key, value) ->
        val prefecture = key.toIntOrNull()?.let(Prefecture::byCode) ?: return@mapNotNull null
        val parts = (value as? String)?.split(',') ?: return@mapNotNull null
        SavedArea(
            prefecture = prefecture,
            downloadedAtMillis = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null,
            mountainCount = parts.getOrNull(1)?.toIntOrNull() ?: 0,
        )
    }.sortedBy { Prefecture.ALL.indexOf(it.prefecture) }

    private companion object {
        const val KEY_PENDING_CODE = "code"
        const val KEY_PENDING_REFRESH = "refresh"
    }
}
