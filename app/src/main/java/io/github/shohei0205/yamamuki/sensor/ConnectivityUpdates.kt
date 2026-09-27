package io.github.shohei0205.yamamuki.sensor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 端末が通信できる状態か(圏外・機内モードでないか)を流す。
 * 圏外なら通信を試す前に分かるので、取得を控えてオフラインとして動かすのに使う。
 * つながっていると判定されても、電波が弱いなどで実際の通信は失敗することがある。
 */
fun connectivityUpdates(context: Context): Flow<Boolean> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    fun NetworkCapabilities?.hasInternet() = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    // 既定のネットワーク(Wi-Fi かモバイル通信)の変化を受け取る。失うと、ほかにつながる回線があれば onAvailable が続く。
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(manager.getNetworkCapabilities(network).hasInternet())
        }

        override fun onLost(network: Network) {
            trySend(false)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            trySend(networkCapabilities.hasInternet())
        }
    }
    trySend(manager.getNetworkCapabilities(manager.activeNetwork).hasInternet())
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
