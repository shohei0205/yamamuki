package io.github.shohei0205.yamamuki.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.YamamukiApp
import io.github.shohei0205.yamamuki.core.DownloadProgress
import io.github.shohei0205.yamamuki.core.Prefecture
import io.github.shohei0205.yamamuki.data.SavedArea
import io.github.shohei0205.yamamuki.sensor.connectivityUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ダウンロード中の地域と進み具合。 */
data class RunningDownload(
    val prefecture: Prefecture,
    val progress: DownloadProgress,
    /** [progress] を受け取った時刻([SystemClock.elapsedRealtime])。待っている秒数を数えるのに使う。 */
    val sinceMillis: Long = SystemClock.elapsedRealtime(),
)

/** ダウンロードが終わった・中断した・失敗したことの知らせ。[resume] があれば続きから取得できる。 */
data class DownloadNotice(
    val message: String,
    val resume: Prefecture? = null,
    val resumeRefresh: Boolean = false,
)

data class AreaDownloadUiState(
    val savedAreas: List<SavedArea> = emptyList(),
    val running: RunningDownload? = null,
    val notice: DownloadNotice? = null,
    /** 端末が通信できる状態か。圏外ではダウンロードを始めさせない。 */
    val connected: Boolean = true,
)

/**
 * 山データの事前ダウンロード。画面を閉じてもダウンロードは続くよう、方位盤と同じくアクティビティ単位で持つ。
 * 同時に進めるのは 1 地域だけ。
 */
class AreaDownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YamamukiApp
    private val repository = app.mountainRepository
    private val savedAreas = app.savedAreas

    private val _state = MutableStateFlow(AreaDownloadUiState(savedAreas = savedAreas.areas.value))
    val state: StateFlow<AreaDownloadUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 始めたダウンロードごとに増やす。中断したあとに古いジョブが遅れて状態を書き換えないよう、自分の番のときだけ書く。 */
    private var generation = 0

    init {
        viewModelScope.launch { savedAreas.areas.collect { areas -> _state.update { it.copy(savedAreas = areas) } } }
        viewModelScope.launch { connectivityUpdates(application).collect { c -> _state.update { it.copy(connected = c) } } }
        // 前回アプリを閉じたときなどに途中で終わっていたら、続きから再開できるよう知らせる。
        savedAreas.pending?.let { (prefecture, refresh) ->
            _state.update {
                it.copy(
                    notice = DownloadNotice(
                        app.getString(R.string.area_notice_pending, prefecture.name),
                        resume = prefecture,
                        resumeRefresh = refresh,
                    ),
                )
            }
        }
    }

    /**
     * [prefecture] をダウンロードする。取得済みで新しいタイルは飛ばすので、中断や失敗のあとは続きから取得する。
     * @param refresh 保存済みの地域を取り直す(取得済みのタイルも問い合わせる)。
     */
    fun start(prefecture: Prefecture, refresh: Boolean = false) {
        if (_state.value.running != null) return
        val id = ++generation
        savedAreas.pending = prefecture to refresh
        _state.update {
            it.copy(running = RunningDownload(prefecture, DownloadProgress(0, prefecture.tiles.size)), notice = null)
        }
        job = viewModelScope.launch {
            var lastDone = -1
            try {
                val count = repository.downloadTiles(
                    prefecture.tiles,
                    forceRefresh = refresh,
                    maxAgeMillis = MAX_AGE_MILLIS,
                ) { progress ->
                    if (id != generation) return@downloadTiles
                    _state.update { it.copy(running = RunningDownload(prefecture, progress)) }
                    // 区画を書き込んだときだけ、方位盤に読み直してもらう(最初の知らせと取り直しの知らせでは書いていない)。
                    if (lastDone >= 0 && progress.doneTiles > lastDone) app.cacheChanges.tryEmit(Unit)
                    lastDone = progress.doneTiles
                }
                if (id != generation) return@launch
                savedAreas.put(SavedArea(prefecture, System.currentTimeMillis(), count))
                savedAreas.pending = null
                _state.update {
                    it.copy(running = null, notice = DownloadNotice(app.getString(R.string.area_notice_done, prefecture.name, count)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "事前ダウンロードに失敗\n${e.stackTraceToString()}")
                if (id != generation) return@launch
                _state.update {
                    val done = it.running?.progress
                    it.copy(
                        running = null,
                        notice = DownloadNotice(
                            if (done == null) {
                                app.getString(R.string.area_notice_failed, prefecture.name)
                            } else {
                                app.getString(R.string.area_notice_failed_progress, prefecture.name, done.doneTiles, done.totalTiles)
                            },
                            resume = prefecture,
                            resumeRefresh = refresh,
                        ),
                    )
                }
            }
        }
    }

    /** ダウンロードを中断する。取得済みの区画は残り、もう一度ダウンロードすると続きから取得する。 */
    fun cancel() {
        val running = _state.value.running ?: return
        generation++
        job?.cancel()
        _state.update {
            it.copy(
                running = null,
                notice = DownloadNotice(
                    app.getString(
                        R.string.area_notice_canceled,
                        running.prefecture.name,
                        running.progress.doneTiles,
                        running.progress.totalTiles,
                    ),
                    resume = running.prefecture,
                    resumeRefresh = savedAreas.pending?.second ?: false,
                ),
            )
        }
    }

    /** 知らせを閉じる。途中で終わったダウンロードの知らせなら、再開の案内もやめる。 */
    fun dismissNotice() {
        if (_state.value.notice?.resume != null) savedAreas.pending = null
        _state.update { it.copy(notice = null) }
    }

    /** 保存済みの地域を消す。ほかの保存済みの地域と重なる区画は残す。通信しないので圏外でもできる。 */
    fun delete(area: SavedArea) {
        if (_state.value.running != null) return
        viewModelScope.launch {
            savedAreas.remove(area.prefecture)
            val keep = savedAreas.tiles()
            app.cacheManager.removeTiles(area.prefecture.tiles.filter { it !in keep })
            app.cacheChanges.tryEmit(Unit)
        }
    }

    private companion object {
        const val TAG = "AreaDownloadViewModel"

        /** 取得済みのタイルを取り直さずに使う期間(30 日)。設定の「取得したデータを使う期間」をなくしたので固定にした。 */
        const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
