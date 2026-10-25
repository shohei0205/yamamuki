// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki

import android.app.Application
import io.github.shohei0205.yamamuki.core.MountainRepository
import io.github.shohei0205.yamamuki.core.OverpassClient
import io.github.shohei0205.yamamuki.core.PeakCatalogCache
import io.github.shohei0205.yamamuki.core.PeakData
import io.github.shohei0205.yamamuki.core.PeakDataChannel
import io.github.shohei0205.yamamuki.core.PeakDataUpdater
import io.github.shohei0205.yamamuki.core.PeakDataset
import io.github.shohei0205.yamamuki.data.CacheManager
import io.github.shohei0205.yamamuki.data.MountainDatabase
import io.github.shohei0205.yamamuki.data.PeakDataStores
import io.github.shohei0205.yamamuki.data.RoomMountainCache
import io.github.shohei0205.yamamuki.data.SavedAreas
import io.github.shohei0205.yamamuki.settings.AppSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.MutableSharedFlow

class YamamukiApp : Application() {

    private val database: MountainDatabase by lazy {
        // キャッシュを作り直したら山データが無くなるので、事前ダウンロード済みの地域と、取り込み済みの全国の山データの
        // 記録も消す。記録が残ると、山データが無いのに「最新です」となり取り直せない。
        MountainDatabase.create(this, onCacheReset = {
            savedAreas.clear()
            peakDataStores.clearAll()
        })
    }

    val cacheManager: CacheManager by lazy { CacheManager(this, database) }

    val settings: AppSettings by lazy { AppSettings(this) }

    val savedAreas: SavedAreas by lazy { SavedAreas(this) }

    /** 方位盤の外(事前ダウンロード)でキャッシュを書き換えたときに流す。方位盤はキャッシュを読み直す。 */
    val cacheChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 取り込み済みの山データの記録。山データの種類ごとに持つ。 */
    val peakDataStores: PeakDataStores by lazy { PeakDataStores(this) }

    private val http: HttpClient by lazy {
        HttpClient(OkHttp) {
            // Overpass は集計が終わるまで応答を返さず 20 秒以上かかることがある。
            // socketTimeout を指定しないと OkHttp 既定の 10 秒で読み込みが打ち切られる。
            install(HttpTimeout) {
                requestTimeoutMillis = 90_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 75_000
            }
        }
    }

    private val overpass: OverpassClient by lazy { OverpassClient(http, userAgent = USER_AGENT) }

    private val mountainCaches: Map<PeakDataset, RoomMountainCache> by lazy {
        PeakDataset.entries.associateWith { RoomMountainCache(database.mountainDao(), it) }
    }

    private val mountainRepositories: Map<PeakDataset, MountainRepository> by lazy {
        PeakDataset.entries.associateWith { MountainRepository(remote = overpass, cache = mountainCache(it)) }
    }

    /** 種類ごとの取得元で、取ったカタログを使い回す(選択画面で種類の数だけ取り直さないように)。 */
    private val catalogCache = PeakCatalogCache()

    private val peakDataUpdaters: Map<PeakDataset, PeakDataUpdater> by lazy {
        PeakDataset.entries.associateWith {
            PeakDataUpdater(
                PeakData.source(peakDataChannel, it, http, userAgent = USER_AGENT, catalogCache = catalogCache),
                mountainCache(it),
                peakDataStores.of(it).archive,
                dataset = it,
            )
        }
    }

    /** 保存している [dataset] の山データ。方位盤は [mountainRepository] を通して読み、山の検索だけは全件を直接読む。 */
    fun mountainCache(dataset: PeakDataset): RoomMountainCache = mountainCaches.getValue(dataset)

    /**
     * [dataset] の山データを読む。方位盤は通信しないで読む。Overpass から取るのは、止めている事前ダウンロード
     * ([Features.AREA_DOWNLOAD]、osm-peaks だけ)のときだけ。
     */
    fun mountainRepository(dataset: PeakDataset): MountainRepository = mountainRepositories.getValue(dataset)

    /** yamamuki-data が配る [dataset] の全国の山データを取得して、キャッシュに取り込む。どこから読むかは [peakDataChannel]。 */
    fun peakDataUpdater(dataset: PeakDataset): PeakDataUpdater = peakDataUpdaters.getValue(dataset)

    private companion object {
        const val USER_AGENT = "yamamuki-android/0.1 (+https://github.com/shohei0205/yamamuki)"

        /**
         * 山データの読み先。配布版はカタログの stable、開発版は既定でカタログの dev(無ければ stable)、
         * Gradle のプロパティ peakDataSource=dev で作った開発版は、osm-peaks だけ yamamuki-data の開発版を直接読む。
         */
        val peakDataChannel = when {
            BuildConfig.PEAK_DATA_DEV -> PeakDataChannel.DATA_DEV
            BuildConfig.DEBUG -> PeakDataChannel.DEV
            else -> PeakDataChannel.STABLE
        }
    }
}
