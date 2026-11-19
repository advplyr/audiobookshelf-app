package com.audiobookshelf.wear.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.audiobookshelf.wear.data.AuthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Decides whether server requests go straight from the watch or through the phone app. A server
 * that's only reachable on the home network can't be reached over the watch's Bluetooth internet,
 * but the phone on home Wi-Fi can reach it and relays for the watch.
 */
class ServerRoute(context: Context, private val auth: AuthStore, private val phoneLink: PhoneLink, private val scope: CoroutineScope) {
  enum class Route { DIRECT, PHONE, OFFLINE }

  private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
  private val _route = MutableStateFlow(Route.DIRECT)
  val route: StateFlow<Route> = _route.asStateFlow()
  val current get() = _route.value

  private val probeClient = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .callTimeout(6, TimeUnit.SECONDS)
    .build()
  private var probeJob: Job? = null

  fun start() {
    connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) = probeSoon()
      override fun onLost(network: Network) = probeSoon()
    })
    scope.launch {
      auth.account.map { it?.serverAddress }.distinctUntilChanged().collect { probeSoon() }
    }
    // Switch back to the direct connection once it works again
    scope.launch {
      while (true) {
        delay(RECHECK_MS)
        if (current != Route.DIRECT) probe()
      }
    }
  }

  fun probeSoon() {
    probeJob?.cancel()
    probeJob = scope.launch {
      delay(1_000)
      probe()
    }
  }

  private suspend fun probe() = withContext(Dispatchers.IO) {
    val account = auth.current ?: return@withContext
    val direct = try {
      val request = Request.Builder().url("${account.serverAddress}/ping")
        .apply { account.customHeaders.forEach { (k, v) -> header(k, v) } }
        .build()
      probeClient.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
      false
    }
    val route = when {
      direct -> Route.DIRECT
      phoneLink.isPhoneReachable() -> Route.PHONE
      else -> Route.OFFLINE
    }
    if (route != _route.value) Log.i(TAG, "Server route: $route")
    _route.value = route
  }

  /** A direct request couldn't connect. Returns true if requests should now go through the phone. Blocking. */
  fun directFailed(): Boolean {
    val phone = phoneLink.isPhoneReachable()
    _route.value = if (phone) Route.PHONE else Route.OFFLINE
    return phone
  }

  companion object {
    private const val TAG = "ServerRoute"
    private const val RECHECK_MS = 2 * 60_000L
  }
}
