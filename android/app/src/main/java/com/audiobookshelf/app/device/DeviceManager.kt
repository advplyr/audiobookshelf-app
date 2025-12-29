package com.audiobookshelf.app.device

import android.appwidget.AppWidgetManager
import android.content.*
import android.net.*
import android.util.Log
import com.audiobookshelf.app.*
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.*

/** Interface for widget event handling. */
interface WidgetEventEmitter {
  /**
   * Called when the player state changes, providing the info required to update the widget.
   */
  fun onPlayerChanged(snapshot: WidgetPlaybackSnapshot)

  /** Called when the player is closed. */
  fun onPlayerClosed()
}

/** Singleton object for managing device-related operations. */
object DeviceManager {
  const val tag = "DeviceManager"

  val dbManager: DbManager = DbManager()
  var deviceData: DeviceData = dbManager.getDeviceData()
  var serverConnectionConfig: ServerConnectionConfig? = null

  val serverConnectionConfigId get() = serverConnectionConfig?.id ?: ""
  val serverConnectionConfigName get() = serverConnectionConfig?.name ?: ""
  val serverConnectionConfigString get() = serverConnectionConfig?.name ?: "No server connection"
  val serverAddress
    get() = serverConnectionConfig?.address ?: ""
  val serverUserId
    get() = serverConnectionConfig?.userId ?: ""
  val token
    get() = serverConnectionConfig?.token ?: ""
  val serverVersion get() = serverConnectionConfig?.version ?: ""
  val isConnectedToServer
    get() = serverConnectionConfig != null

  var widgetUpdater: WidgetEventEmitter? = null

  init {
    Log.d(tag, "Device Manager Singleton invoked")

    // Initialize new sleep timer settings and shake sensitivity added in v0.9.61
    if (deviceData.deviceSettings?.autoSleepTimerStartTime == null ||
                    deviceData.deviceSettings?.autoSleepTimerEndTime == null
    ) {
      deviceData.deviceSettings?.autoSleepTimerStartTime = "22:00"
      deviceData.deviceSettings?.autoSleepTimerEndTime = "06:00"
      deviceData.deviceSettings?.sleepTimerLength = 900000L
    }
    if (deviceData.deviceSettings?.shakeSensitivity == null) {
      deviceData.deviceSettings?.shakeSensitivity = ShakeSensitivitySetting.MEDIUM
    }
    // Initialize auto sleep timer auto rewind added in v0.9.64
    if (deviceData.deviceSettings?.autoSleepTimerAutoRewindTime == null) {
      deviceData.deviceSettings?.autoSleepTimerAutoRewindTime = 300000L // 5 minutes
    }
    // Initialize sleep timer almost done chime added in v0.9.81
    if (deviceData.deviceSettings?.enableSleepTimerAlmostDoneChime == null) {
      deviceData.deviceSettings?.enableSleepTimerAlmostDoneChime = false
    }

    // Language added in v0.9.69
    if (deviceData.deviceSettings?.languageCode == null) {
      deviceData.deviceSettings?.languageCode = "en-us"
    }

    if (deviceData.deviceSettings?.downloadUsingCellular == null) {
      deviceData.deviceSettings?.downloadUsingCellular = DownloadUsingCellularSetting.ALWAYS
    }

    if (deviceData.deviceSettings?.streamingUsingCellular == null) {
      deviceData.deviceSettings?.streamingUsingCellular = StreamingUsingCellularSetting.ALWAYS
    }
    if (deviceData.deviceSettings?.androidAutoBrowseLimitForGrouping == null) {
      deviceData.deviceSettings?.androidAutoBrowseLimitForGrouping = 100
    }
    if (deviceData.deviceSettings?.androidAutoBrowseSeriesSequenceOrder == null) {
      deviceData.deviceSettings?.androidAutoBrowseSeriesSequenceOrder =
              AndroidAutoBrowseSeriesSequenceOrderSetting.ASC
    }
  }

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
   * Check if the currently connected server version is >= compareVersion
   * Abs server only uses major.minor.patch
   * Note: Version is returned in Abs auth payloads starting v2.6.0
   * Note: Version is saved with the server connection config starting after v0.9.81
   *
   * @example
   * serverVersion=2.25.1
   * isServerVersionGreaterThanOrEqualTo("2.26.0") = false
   *
   * serverVersion=2.26.1
   * isServerVersionGreaterThanOrEqualTo("2.26.0") = true
   */
  fun isServerVersionGreaterThanOrEqualTo(compareVersion:String):Boolean {
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
   * Retrieves the last saved playback session from persistent storage.
   * @return The last PlaybackSession, or null if none is saved.
   */
  fun getLastPlaybackSession(): PlaybackSession? {
    return deviceData.lastPlaybackSession
  }

  /**
   * Initializes the widget updater.
   * @param context The context to use for initializing the widget updater.
   */
  fun initializeWidgetUpdater(context: Context) {
    Log.d(tag, "Initializing widget updater")
    widgetUpdater =
      (object : WidgetEventEmitter {
        override fun onPlayerChanged(snapshot: WidgetPlaybackSnapshot) {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, MediaPlayerWidget::class.java)
                val ids = appWidgetManager.getAppWidgetIds(componentName)
                for (widgetId in ids) {
                  updateAppWidget(
                          context,
                          appWidgetManager,
                          widgetId,
                    snapshot
                  )
                }
              }

              override fun onPlayerClosed() {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, MediaPlayerWidget::class.java)
                val ids = appWidgetManager.getAppWidgetIds(componentName)
                val lastSession = deviceData.lastPlaybackSession
                for (widgetId in ids) {
                  updateAppWidget(
                          context,
                          appWidgetManager,
                          widgetId,
                    lastSession?.toWidgetSnapshot(context, isPlaying = false, isClosed = true)
                  )
                }
              }
            })
  }
}
