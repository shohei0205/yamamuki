// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.shohei0205.yamamuki.R
import io.github.shohei0205.yamamuki.YamamukiApp
import io.github.shohei0205.yamamuki.core.InstalledSurfaceData
import io.github.shohei0205.yamamuki.core.PeakDataCredits
import io.github.shohei0205.yamamuki.core.PeakDataCreditsReader
import io.github.shohei0205.yamamuki.core.SurfaceDataException
import io.github.shohei0205.yamamuki.core.SurfaceDataOffer
import io.github.shohei0205.yamamuki.core.SurfaceDataUpdater
import io.github.shohei0205.yamamuki.core.SurfaceDataset
import io.github.shohei0205.yamamuki.core.SurfaceManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 山データの選択画面の「地表データ」の状態。 */
data class SurfaceUiState(
    /** 取得済みの種類と、カタログにある種類。並びは [SurfaceDataset] の順。 */
    val entries: List<SurfaceDataEntry> = emptyList(),
    /** カタログで取得できる版を確かめている。 */
    val checking: Boolean = false,
    /** ダウンロードしている種類。していなければ null。 */
    val updating: SurfaceDataset? = null,
    /** ダウンロードの進み具合(0〜1)。 */
    val progress: Float = 0f,
    /** 大きさを見せて、ダウンロードするかを聞いている版。 */
    val offer: SurfaceOffer? = null,
    /** 取得に失敗したときの知らせ。 */
    val errorMessage: String? = null,
) {
    /** [dataset] を取得済みか。 */
    fun installed(dataset: SurfaceDataset): Boolean = entries.any { it.dataset == dataset && it.installed != null }
}

/** 選択画面の 1 行。 */
data class SurfaceDataEntry(
    val dataset: SurfaceDataset,
    /** 取得済みの版。まだなら null。 */
    val installed: InstalledSurfaceData?,
    /** カタログにある、まだ取り込んでいない版。 */
    val available: SurfaceManifest? = null,
) {
    /** 取得済みで、新しい版がある。 */
    val updateFound: Boolean get() = installed != null && available != null

    val experimental: Boolean get() = available?.experimental ?: installed?.experimental ?: false
}

/** ダウンロードするかを聞いている版。 */
data class SurfaceOffer(val dataset: SurfaceDataset, val manifest: SurfaceManifest)

/**
 * 地表データ(方位盤の背景に描く森林・水域・陰影)の確認・ダウンロード・削除。山データの選択画面と設定画面から使う。
 * 山データ([DialViewModel])とは別に持ち、方位盤の動きに影響しないようにする。
 */
class SurfaceDataViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YamamukiApp

    private val _state = MutableStateFlow(SurfaceUiState(entries = installedEntries()))
    val state: StateFlow<SurfaceUiState> = _state.asStateFlow()

    /** 確認して見つけた、まだ取り込んでいない版。 */
    private val offers = mutableMapOf<SurfaceDataset, SurfaceDataOffer>()
    private var checkJob: Job? = null

    private fun store(dataset: SurfaceDataset) = app.surfaceDataStores.of(dataset)

    private fun installedEntries(): List<SurfaceDataEntry> =
        SurfaceDataset.entries.mapNotNull { d -> store(d).load()?.let { SurfaceDataEntry(d, it) } }

    /**
     * 選択画面を開いた。取得済みの種類はすぐ出し、通信できればカタログで取り込める版を確かめて足す。データ本体は取らない。
     * 利用者が頼んだ通信ではないので、確かめられなくても知らせない(取得済みの種類はそのまま使える)。
     */
    fun open() {
        checkJob?.cancel()
        offers.clear()
        _state.update { it.copy(entries = installedEntries(), checking = true) }
        checkJob = viewModelScope.launch {
            for (d in SurfaceDataset.entries) {
                val installed = store(d).load()
                val entry = try {
                    when (val check = app.surfaceDataUpdater(d).check(installed)) {
                        is SurfaceDataUpdater.Check.UpToDate -> {
                            store(d).save(check.installed)
                            SurfaceDataEntry(d, check.installed)
                        }
                        is SurfaceDataUpdater.Check.Available -> {
                            offers[d] = check.offer
                            SurfaceDataEntry(d, installed, check.offer.manifest)
                        }
                        SurfaceDataUpdater.Check.Unavailable -> installed?.let { SurfaceDataEntry(d, it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Log.w(tag, msg, tr) は UnknownHostException を含むと何も出さないので文字列にして渡す。
                    Log.w(TAG, "地表データ(${d.id})の確認に失敗\n${e.stackTraceToString()}")
                    installed?.let { SurfaceDataEntry(d, it) }
                }
                setEntry(d, entry)
            }
            _state.update { it.copy(checking = false) }
        }
    }

    /** 選択画面の [d] の行を [entry] にする。null なら行を消す。 */
    private fun setEntry(d: SurfaceDataset, entry: SurfaceDataEntry?) {
        _state.update { s ->
            s.copy(entries = (s.entries.filter { it.dataset != d } + listOfNotNull(entry)).sortedBy { it.dataset.ordinal })
        }
    }

    /**
     * 選択画面で [d] を選んだ。取得済みならその種類を使い、まだなら大きさを見せてダウンロードするかを聞く。
     */
    fun choose(d: SurfaceDataset) {
        if (_state.value.updating != null) return
        if (store(d).load() != null) {
            app.settings.update { it.copy(surfaceDataset = d) }
        } else {
            offerDownload(d)
        }
    }

    /** [d] の新しい版(または、まだ取得していない版)の大きさを見せて、ダウンロードするかを聞く。 */
    fun offerDownload(d: SurfaceDataset) {
        if (_state.value.updating != null) return
        val offer = offers[d] ?: return
        _state.update { it.copy(offer = SurfaceOffer(d, offer.manifest)) }
    }

    fun answerOffer(download: Boolean) {
        val d = _state.value.offer?.dataset ?: return
        _state.update { it.copy(offer = null) }
        if (download) install(d)
    }

    /** 確認した版のデータ本体を取得して端末に置く。初めての種類なら、取り込めたらその種類を使う。 */
    private fun install(d: SurfaceDataset) {
        val offer = offers[d] ?: return
        if (_state.value.updating != null) return
        checkJob?.cancel()
        _state.update { it.copy(updating = d, progress = 0f, checking = false) }
        viewModelScope.launch {
            try {
                val installed = app.surfaceDataUpdater(d).install(offer) { p -> _state.update { it.copy(progress = p) } }
                store(d).save(installed)
                offers.remove(d)
                setEntry(d, SurfaceDataEntry(d, installed))
                app.settings.update { it.copy(surfaceDataset = d) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "地表データ(${d.id})の取得に失敗\n${e.stackTraceToString()}")
                val message = if (e is SurfaceDataException) {
                    app.getString(R.string.surface_data_error_invalid, e.message.orEmpty())
                } else {
                    app.getString(R.string.surface_data_error_network)
                }
                _state.update { it.copy(errorMessage = message) }
            } finally {
                _state.update { it.copy(updating = null) }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** [d] の地表データを端末から消す。使っている種類なら、方位盤の背景は地表なしに戻る。 */
    fun delete(d: SurfaceDataset) {
        if (_state.value.updating != null) return
        viewModelScope.launch {
            store(d).clear()
            if (app.settings.settings.value.surfaceDataset == d) app.settings.update { it.copy(showSurface = false) }
            // 行は「未取得」にしておき、取り込める版が分かっていれば選べるようにする。
            val known = offers[d]
            setEntry(d, known?.let { SurfaceDataEntry(d, null, it.manifest) })
            if (known != null) return@launch
            // 開いたときの確認で最新だった種類は版を覚えていないので、ダウンロードし直せるように確かめ直す。
            try {
                val check = app.surfaceDataUpdater(d).check(null) as? SurfaceDataUpdater.Check.Available ?: return@launch
                offers[d] = check.offer
                if (store(d).load() == null) setEntry(d, SurfaceDataEntry(d, null, check.offer.manifest))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "地表データ(${d.id})の確認に失敗\n${e.stackTraceToString()}")
            }
        }
    }

    /** 使っている種類の地表データの出典。取り込んだときに端末へ残した manifest から読む。取得していなければ null。 */
    suspend fun credits(): PeakDataCredits? {
        val store = store(app.settings.settings.value.surfaceDataset)
        if (store.load() == null) return null
        return store.files.readManifest()?.let { PeakDataCreditsReader.parse(it.toString(Charsets.UTF_8)) }
    }

    private companion object {
        const val TAG = "SurfaceDataViewModel"
    }
}
