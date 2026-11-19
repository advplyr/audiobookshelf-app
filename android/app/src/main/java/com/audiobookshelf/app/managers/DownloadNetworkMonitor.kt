package com.audiobookshelf.app.managers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.audiobookshelf.app.plugins.AbsLogger

/**
 * Tracks the default network so queued downloads wait for a usable connection instead of burning
 * retries, and resume as soon as one returns.
 *
 * NET_CAPABILITY_VALIDATED is deliberately not required: self-hosted servers are often reached over
 * a LAN that Android reports as having no internet access.
 */
class DownloadNetworkMonitor(context: Context, private val onChange: () -> Unit) {
  private val tag = "DownloadNetworkMonitor"
  private val connectivityManager =
          context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  @Volatile
  var isConnected: Boolean = false
    private set

  /**
   * True when the default network is metered. Metered is what the download policy keys off, so a
   * metered Wi-Fi hotspot counts as mobile data and a carrier-unmetered cellular plan does not.
   */
  @Volatile
  var isMetered: Boolean = false
    private set

  @Volatile private var defaultNetwork: Network? = null

  private val callback =
          object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
              defaultNetwork = network
              update(capabilities)
            }

            override fun onLost(network: Network) {
              // A late onLost for the previous default must not mark a new default as offline.
              if (defaultNetwork != null && network != defaultNetwork) return
              defaultNetwork = null
              update(null)
            }
          }

  companion object {
    /** Current state without waiting for a callback, for one-off checks from the UI. */
    fun isMeteredNow(context: Context): Boolean {
      val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
      return !isUnmetered(capabilities)
    }

    private fun isUnmetered(capabilities: NetworkCapabilities): Boolean =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
  }

  init {
    defaultNetwork = connectivityManager.activeNetwork
    update(connectivityManager.getNetworkCapabilities(defaultNetwork), notify = false)
    try {
      connectivityManager.registerDefaultNetworkCallback(callback)
    } catch (e: RuntimeException) {
      AbsLogger.error(tag, "Could not register network callback: ${e.message}")
    }
  }

  private fun update(capabilities: NetworkCapabilities?, notify: Boolean = true) {
    val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val metered = connected && !isUnmetered(capabilities!!)
    if (connected == isConnected && metered == isMetered) return
    isConnected = connected
    isMetered = metered
    AbsLogger.info(tag, "Network changed: connected=$connected metered=$metered")
    if (notify) onChange()
  }

  fun destroy() {
    try {
      connectivityManager.unregisterNetworkCallback(callback)
    } catch (e: RuntimeException) {
      AbsLogger.error(tag, "Could not unregister network callback: ${e.message}")
    }
  }
}
