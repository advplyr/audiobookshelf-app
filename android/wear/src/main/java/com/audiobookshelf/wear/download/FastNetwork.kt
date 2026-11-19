package com.audiobookshelf.wear.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * On Wear OS the default network is often the Bluetooth proxy through the phone, which is far too
 * slow for audiobooks. This asks the system to bring up Wi-Fi or LTE for the download, the
 * approach Google recommends for large transfers on watches.
 */
class FastNetwork private constructor(
  private val connectivityManager: ConnectivityManager,
  private val callback: ConnectivityManager.NetworkCallback,
  val network: Network
) {
  fun bind(client: OkHttpClient): OkHttpClient = client.newBuilder()
    .socketFactory(network.socketFactory)
    .dns(object : Dns {
      override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
    })
    .build()

  fun release() {
    connectivityManager.unregisterNetworkCallback(callback)
  }

  companion object {
    private const val TAG = "FastNetwork"
    private const val TIMEOUT_MS = 15_000L

    /** Returns null when the default network is already fast, or Wi-Fi/LTE could not be brought up in time. */
    suspend fun acquire(context: Context): FastNetwork? {
      val cm = context.getSystemService(ConnectivityManager::class.java)
      val activeCaps = cm.getNetworkCapabilities(cm.activeNetwork)
      if (activeCaps != null && (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))) {
        return null
      }

      val available = CompletableDeferred<Network>()
      val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          available.complete(network)
        }
      }
      val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .build()
      cm.requestNetwork(request, callback)

      val network = withTimeoutOrNull(TIMEOUT_MS) { available.await() }
      if (network == null) {
        Log.w(TAG, "No Wi-Fi or LTE available, downloading over the default network")
        cm.unregisterNetworkCallback(callback)
        return null
      }
      return FastNetwork(cm, callback, network)
    }
  }
}
