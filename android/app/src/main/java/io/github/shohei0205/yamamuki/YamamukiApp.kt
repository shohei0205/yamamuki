package io.github.shohei0205.yamamuki

import android.app.Application
import io.github.shohei0205.yamamuki.core.MountainRepository
import io.github.shohei0205.yamamuki.core.OverpassClient
import io.github.shohei0205.yamamuki.core.PeakData
import io.github.shohei0205.yamamuki.core.PeakDataChannel
import io.github.shohei0205.yamamuki.core.PeakDataUpdater
import io.github.shohei0205.yamamuki.data.CacheManager
import io.github.shohei0205.yamamuki.data.MountainDatabase
import io.github.shohei0205.yamamuki.data.PeakDataStore
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
            peakDataStore.clear()
        })
    }

    val cacheManager: CacheManager by lazy { CacheManager(this, database) }

    val settings: AppSettings by lazy { AppSettings(this) }

    val savedAreas: SavedAreas by lazy { SavedAreas(this) }

    /** 方位盤の外(事前ダウンロード)でキャッシュを書き換えたときに流す。方位盤はキャッシュを読み直す。 */
    val cacheChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val peakDataStore: PeakDataStore by lazy { PeakDataStore(this) }

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

    private val cache: RoomMountainCache by lazy { RoomMountainCache(database.mountainDao()) }

    val mountainRepository: MountainRepository by lazy {
        MountainRepository(remote = OverpassClient(http, userAgent = USER_AGENT), cache = cache)
    }

    /** yamamuki-data が配る全国の山データを取得して、キャッシュに取り込む。どこから読むかは [peakDataChannel]。 */
    val peakDataUpdater: PeakDataUpdater by lazy {
        PeakDataUpdater(PeakData.source(peakDataChannel, http, userAgent = USER_AGENT), cache, peakDataStore.archive)
    }

    private companion object {
        const val USER_AGENT = "yamamuki-android/0.1 (+https://github.com/shohei0205/yamamuki)"

        /**
         * 山データの読み先。配布版は参照先ファイルの stable、開発版は既定で参照先ファイルの dev(無ければ stable)、
         * Gradle のプロパティ peakDataSource=dev で作った開発版は yamamuki-data の開発版を直接読む。
         */
        val peakDataChannel = when {
            BuildConfig.PEAK_DATA_DEV -> PeakDataChannel.DATA_DEV
            BuildConfig.DEBUG -> PeakDataChannel.DEV
            else -> PeakDataChannel.STABLE
        }
    }
}
