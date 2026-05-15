package com.audiobookshelf.app.player.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Centralized network state tracker used by both the ExoPlayer PlayerNotificationService
 * and the Media3 stack. This shares metered/unmetered connectivity detection across both paths.
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
  var initialized = false

  val isUnmeteredNetwork: Boolean
    get() = currentState.isUnmetered

  val hasConnectivity: Boolean
    get() = currentState.hasConnectivity

  fun initialize(context: Context) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      val connectivityManagerService =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
          ?: return
      connectivityManager = connectivityManagerService
      val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
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
      connectivityManagerService.registerDefaultNetworkCallback(defaultNetworkCallback)
      networkCallback = defaultNetworkCallback
      updateNetworkState(
        connectivityManagerService.getNetworkCapabilities(
          connectivityManagerService.activeNetwork
        )
      )
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

  private fun updateNetworkState(networkCapabilities: NetworkCapabilities?) {
    val hasConnectivity =
      networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val isUnmetered =
      networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    val newState = State(hasConnectivity, isUnmetered)
    if (newState == currentState) return
    currentState = newState
    listeners.forEach { listener -> listener.onNetworkStateChanged(newState) }
  }
}
