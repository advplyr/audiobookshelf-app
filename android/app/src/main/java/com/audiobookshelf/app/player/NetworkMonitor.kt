package com.audiobookshelf.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Centralized network state tracker used by both the legacy PlayerNotificationService
 * and the Media3 stack. This lets us retire the legacy service without losing the
 * ability to detect metered/unmetered connectivity.
 */
object NetworkMonitor {
  data class State(val hasConnectivity: Boolean, val isUnmetered: Boolean)

  fun interface Listener {
    fun onNetworkStateChanged(state: State)
  }

  @Volatile
  private var currentState = State(hasConnectivity = false, isUnmetered = false)

  private val listeners = CopyOnWriteArraySet<Listener>()
  private var connectivityManager: ConnectivityManager? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  @Volatile
  private var initialized = false

  val isUnmeteredNetwork: Boolean
    get() = currentState.isUnmetered

  val hasNetworkConnectivity: Boolean
    get() = currentState.hasConnectivity

  fun initialize(context: Context) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      val manager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
          ?: return
      connectivityManager = manager
      val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
          network: Network,
          networkCapabilities: NetworkCapabilities
        ) {
          super.onCapabilitiesChanged(network, networkCapabilities)
          updateNetworkState(networkCapabilities)
        }

        override fun onLost(network: Network) {
          super.onLost(network)
          updateNetworkState(null)
        }
      }
      manager.registerDefaultNetworkCallback(callback)
      networkCallback = callback
      updateNetworkState(manager.getNetworkCapabilities(manager.activeNetwork))
      initialized = true
    }
  }

  fun addListener(listener: Listener) {
    listeners.add(listener)
    listener.onNetworkStateChanged(currentState)
  }

  fun removeListener(listener: Listener) {
    listeners.remove(listener)
  }

  private fun updateNetworkState(capabilities: NetworkCapabilities?) {
    val hasConnectivity =
      capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val isUnmetered =
      capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    val newState = State(hasConnectivity, isUnmetered)
    if (newState == currentState) return
    currentState = newState
    listeners.forEach { listener -> listener.onNetworkStateChanged(newState) }
  }
}
