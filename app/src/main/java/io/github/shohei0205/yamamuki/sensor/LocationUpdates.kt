package io.github.shohei0205.yamamuki.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Bundle
import android.os.Looper
import io.github.shohei0205.yamamuki.core.LocationFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

/**
 * 現在地を流す。最初に端末が持っている直近の位置を流し、以降は GPS とネットワーク位置の更新を流す。
 * 精度が直前よりはっきり悪い位置(山で GPS の合間に届くネットワーク位置など)は [LocationFilter] で捨てる。
 * 呼び出し側で位置情報の権限を確認してから collect すること。
 */
@SuppressLint("MissingPermission")
fun locationUpdates(context: Context): Flow<Location> = callbackFlow {
    val locationManager = context.getSystemService(LocationManager::class.java)
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { it in locationManager.allProviders }

    val filter = LocationFilter()
    // 時刻は端末の時計に左右されない起動からの経過時間で比べる(GPS とネットワーク位置で時計がずれることがある)。
    fun send(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        if (filter.accept(location.elapsedRealtimeNanos / 1_000_000, accuracy)) trySend(location)
    }

    providers.mapNotNull { locationManager.getLastKnownLocation(it) }
        .maxByOrNull { it.elapsedRealtimeNanos }
        ?.let { send(it) }

    // Android 10 以前は onStatusChanged などが抽象メソッドのため、ラムダではなく全メソッドを実装する。
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            send(location)
        }

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    providers.forEach {
        locationManager.requestLocationUpdates(it, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, listener, Looper.getMainLooper())
    }
    awaitClose { locationManager.removeUpdates(listener) }
}

private const val UPDATE_INTERVAL_MS = 5_000L
private const val UPDATE_DISTANCE_M = 20f

/**
 * 現在地の標高(海抜, m)。GPS の altitude は楕円体からの高さで、日本では海抜より 30〜40m ほど高いため、
 * Android 14 以降のジオイドモデル(AltitudeConverter)で海抜に直す。直せないとき(Android 13 以前、
 * 高さを持たないネットワーク位置など)は null。ファイルを読むのでメインスレッドでは呼ばないこと。
 */
fun mslAltitudeM(context: Context, location: Location): Double? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
    if (location.hasMslAltitude()) return location.mslAltitudeMeters
    if (!location.hasAltitude()) return null
    return try {
        val copy = Location(location)
        AltitudeConverter().addMslAltitudeToLocation(context, copy)
        if (copy.hasMslAltitude()) copy.mslAltitudeMeters else null
    } catch (e: IOException) {
        null
    }
}
