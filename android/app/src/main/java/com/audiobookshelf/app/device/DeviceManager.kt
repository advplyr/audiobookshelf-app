package com.audiobookshelf.app.device

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.audiobookshelf.app.MediaPlayerWidget
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.updateAppWidget

/** Interface for widget event handling. */
interface WidgetEventEmitter {
  /**
   * Called when the player state changes.
   * @param pns The PlayerNotificationService instance.
   */
  fun onPlayerChanged(pns: PlayerNotificationService)

  /** Called when the player is closed. */
  fun onPlayerClosed()
}

/** Singleton object for managing device-related operations. */
object DeviceManager {
  const val tag = "DeviceManager"

  val dbManager: DbManager = DbManager()
  var deviceData: DeviceData = getDeviceDataSafely()
  var serverConnectionConfig: ServerConnectionConfig? = null

  private fun getDeviceDataSafely(): DeviceData {
    return try {
      Log.d(tag, "Loading device data...")
      val data = dbManager.getDeviceData()
      Log.d(tag, "Device data loaded successfully")

      // Ensure deviceSettings is not null and has all required fields
      if (data.deviceSettings == null) {
        Log.d(tag, "Device settings is null, creating defaults")
        data.deviceSettings = DeviceSettings.default()
      }

      // Initialize maxSimultaneousDownloads if missing
      if (data.deviceSettings?.maxSimultaneousDownloads == null) {
        Log.d(tag, "Initializing maxSimultaneousDownloads to 1")
        data.deviceSettings?.maxSimultaneousDownloads = 1
      }

      try {
        dbManager.saveDeviceData(data)
        Log.d(tag, "Device data saved successfully")
      } catch (e: Exception) {
        Log.e(tag, "Failed to save device data", e)
      }

      data
    } catch (e: Exception) {
      Log.e(tag, "Failed to load device data, using defaults", e)
      DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
    }
  }

  val serverConnectionConfigId
    get() = serverConnectionConfig?.id ?: ""
  val serverConnectionConfigName
    get() = serverConnectionConfig?.name ?: ""
  val serverConnectionConfigString
    get() = serverConnectionConfig?.name ?: "No server connection"
  val serverAddress
    get() = serverConnectionConfig?.address ?: ""
  val serverUserId
    get() = serverConnectionConfig?.userId ?: ""
  val token
    get() = serverConnectionConfig?.token ?: ""
  val serverVersion
    get() = serverConnectionConfig?.version ?: ""
  val isConnectedToServer
    get() = serverConnectionConfig != null

  var widgetUpdater: WidgetEventEmitter? = null

  /**
   * Encodes the given ID to a Base64 string.
   * @param id The ID to encode.
   * @return The Base64 encoded string.
   */
  fun getBase64Id(id: String): String {
    return android.util.Base64.encodeToString(
            id.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
    )
  }

  /**
   * Retrieves the server connection configuration for the given ID.
   * @param id The ID of the server connection configuration.
   * @return The ServerConnectionConfig instance or null if not found.
   */
  fun getServerConnectionConfig(id: String?): ServerConnectionConfig? {
    return id?.let { deviceData.serverConnectionConfigs.find { it.id == id } }
  }

  /**
   * Check if the currently connected server version is >= compareVersion Abs server only uses
   * major.minor.patch Note: Version is returned in Abs auth payloads starting v2.6.0 Note: Version
   * is saved with the server connection config starting after v0.9.81
   *
   * @example serverVersion=2.25.1 isServerVersionGreaterThanOrEqualTo("2.26.0") = false
   *
   * serverVersion=2.26.1 isServerVersionGreaterThanOrEqualTo("2.26.0") = true
   */
  fun isServerVersionGreaterThanOrEqualTo(compareVersion: String): Boolean {
    if (serverVersion == "") return false
    if (compareVersion == "") return true

    val serverVersionParts = serverVersion.split(".").map { it.toIntOrNull() ?: 0 }
    val compareVersionParts = compareVersion.split(".").map { it.toIntOrNull() ?: 0 }

    // Compare major, minor, and patch components
    for (i in 0 until maxOf(serverVersionParts.size, compareVersionParts.size)) {
      val serverVersionComponent = serverVersionParts.getOrElse(i) { 0 }
      val compareVersionComponent = compareVersionParts.getOrElse(i) { 0 }

      if (serverVersionComponent < compareVersionComponent) {
        return false // Server version is less than compareVersion
      } else if (serverVersionComponent > compareVersionComponent) {
        return true // Server version is greater than compareVersion
      }
    }

    return true // versions are equal in major, minor, and patch
  }

  /**
   * Checks the network connectivity status.
   * @param ctx The context to use for checking connectivity.
   * @return True if connected to the internet, false otherwise.
   */
  fun checkConnectivity(ctx: Context): Boolean {
    val connectivityManager =
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    if (capabilities != null) {
      if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        Log.i("Internet", "NetworkCapabilities.TRANSPORT_CELLULAR")
        return true
      } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        Log.i("Internet", "NetworkCapabilities.TRANSPORT_WIFI")
        return true
      } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
        Log.i("Internet", "NetworkCapabilities.TRANSPORT_ETHERNET")
        return true
      }
    }
    return false
  }

  /**
   * Sets the last playback session.
   * @param playbackSession The playback session to set.
   */
  fun setLastPlaybackSession(playbackSession: PlaybackSession) {
    deviceData.lastPlaybackSession = playbackSession
    dbManager.saveDeviceData(deviceData)
  }

  /**
   * Initializes the widget updater.
   * @param context The context to use for initializing the widget updater.
   */
  fun initializeWidgetUpdater(context: Context) {
    Log.d(tag, "Initializing widget updater")
    widgetUpdater =
            (object : WidgetEventEmitter {
              override fun onPlayerChanged(pns: PlayerNotificationService) {
                val isPlaying = pns.currentPlayer.isPlaying

                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, MediaPlayerWidget::class.java)
                val ids = appWidgetManager.getAppWidgetIds(componentName)
                val playbackSession = pns.getCurrentPlaybackSessionCopy()

                for (widgetId in ids) {
                  updateAppWidget(
                          context,
                          appWidgetManager,
                          widgetId,
                          playbackSession,
                          isPlaying,
                          PlayerNotificationService.isClosed
                  )
                }
              }

              override fun onPlayerClosed() {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, MediaPlayerWidget::class.java)
                val ids = appWidgetManager.getAppWidgetIds(componentName)
                for (widgetId in ids) {
                  updateAppWidget(
                          context,
                          appWidgetManager,
                          widgetId,
                          deviceData.lastPlaybackSession,
                          false,
                          PlayerNotificationService.isClosed
                  )
                }
              }
            })
  }
}
