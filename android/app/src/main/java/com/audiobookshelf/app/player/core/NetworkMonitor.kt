package com.audiobookshelf.app.player.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.CopyOnWriteArraySet

/** Tracks connectivity and metering state for both playback implementations. */
object NetworkMonitor {
  data class State(val hasConnectivity: Boolean, val isUnmetered: Boolean)

  fun interface Listener {
    /** True when delivering the current state to a newly registered listener. */
    fun onNetworkStateChanged(state: State, isRegistrationReplay: Boolean)
  }

  private val INITIAL_STATE = State(hasConnectivity = false, isUnmetered = false)

  @Volatile
  private var currentState = INITIAL_STATE

  private val listeners = CopyOnWriteArraySet<Listener>()
  private var connectivityManager: ConnectivityManager? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  @Volatile
  private var isInitialized = false

  val isUnmeteredNetwork: Boolean
    get() = currentState.isUnmetered

  val hasConnectivity: Boolean
    get() = currentState.hasConnectivity

  fun initialize(context: Context) {
    if (isInitialized) return
    synchronized(this) {
      if (isInitialized) return
      val manager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
          ?: return
      connectivityManager = manager
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
      manager.registerDefaultNetworkCallback(defaultNetworkCallback)
      networkCallback = defaultNetworkCallback
      // Read the initial state without reporting a change.
      currentState = readNetworkState(
        manager.getNetworkCapabilities(
          manager.activeNetwork
        )
      )
      isInitialized = true
    }
  }

  fun addListener(listener: Listener) {
    // Replay while locked so a state change cannot overtake registration.
    synchronized(this) {
      listeners.add(listener)
      listener.onNetworkStateChanged(currentState, true)
    }
  }

  fun removeListener(listener: Listener) {
    synchronized(this) {
      listeners.remove(listener)
      if (listeners.isEmpty()) release()
    }
  }

  // Unregister the callback when unused, but retain the last known state for readers.
  // Callers hold the lock so the emptiness check and release happen atomically with add/remove.
  private fun release() {
    if (!isInitialized) return
    networkCallback?.let { callback ->
      try {
        connectivityManager?.unregisterNetworkCallback(callback)
      } catch (_: IllegalArgumentException) {
        // Already unregistered
      }
    }
    networkCallback = null
    connectivityManager = null
    isInitialized = false
  }

  private fun readNetworkState(networkCapabilities: NetworkCapabilities?): State {
    val hasConnectivity =
      networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val isUnmetered =
      networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    return State(hasConnectivity, isUnmetered)
  }

  private fun updateNetworkState(networkCapabilities: NetworkCapabilities?) {
    val newState = readNetworkState(networkCapabilities)
    // Update under the lock, then notify listeners without holding it.
    val listenersToNotify = synchronized(this) {
      if (newState == currentState) return
      currentState = newState
      listeners.toList()
    }
    listenersToNotify.forEach { listener -> listener.onNetworkStateChanged(newState, false) }
  }
}
