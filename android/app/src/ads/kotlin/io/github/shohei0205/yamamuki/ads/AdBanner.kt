package io.github.shohei0205.yamamuki.ads

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import io.github.shohei0205.yamamuki.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 方位盤のヘッダーに出すバナー広告(320×50)。広告を入れるビルドだけにある(build.gradle.kts の ads)。
 * 読み込めないとき(圏外など)は何も描かないので、後ろのヘッダーの絵がそのまま見える。
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adView = remember {
        startMobileAds(context)
        AdView(context).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            loadAd(AdRequest.Builder().build())
        }
    }
    // 画面が隠れている間は広告の更新を止める。
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, adView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> adView.resume()
                Lifecycle.Event.ON_PAUSE -> adView.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            adView.destroy()
        }
    }
    AndroidView(factory = { adView }, modifier = modifier.size(320.dp, 50.dp))
}

private val started = AtomicBoolean(false)

/** AdMob の SDK を一度だけ起動する。時間がかかるので、Google の案内どおり画面の処理とは別のスレッドで行う。 */
private fun startMobileAds(context: Context) {
    if (!started.compareAndSet(false, true)) return
    val appContext = context.applicationContext
    Thread { MobileAds.initialize(appContext) }.start()
}
