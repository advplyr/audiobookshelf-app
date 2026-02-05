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

  // Lock for synchronizing access to mutable state
  private val stateLock = Any()

  val dbManager: DbManager = DbManager()

  @Volatile
  private var _deviceData: DeviceData = dbManager.getDeviceData()
  val deviceData: DeviceData
    get() = synchronized(stateLock) { _deviceData }

  @Volatile
  private var _serverConnectionConfig: ServerConnectionConfig? = null
  var serverConnectionConfig: ServerConnectionConfig?
    get() = synchronized(stateLock) { _serverConnectionConfig }
    set(value) = synchronized(stateLock) { _serverConnectionConfig = value }

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

  @Volatile
  var widgetUpdater: WidgetEventEmitter? = null

  init {
    Log.d(tag, "Device Manager Singleton invoked")

    // Initialize device data with thread safety
    synchronized(stateLock) {
      // Initialize new sleep timer settings and shake sensitivity added in v0.9.61
      if (_deviceData.deviceSettings?.autoSleepTimerStartTime == null ||
                      _deviceData.deviceSettings?.autoSleepTimerEndTime == null
      ) {
        _deviceData.deviceSettings?.autoSleepTimerStartTime = "22:00"
        _deviceData.deviceSettings?.autoSleepTimerEndTime = "06:00"
        _deviceData.deviceSettings?.sleepTimerLength = 900000L
      }
      if (_deviceData.deviceSettings?.shakeSensitivity == null) {
        _deviceData.deviceSettings?.shakeSensitivity = ShakeSensitivitySetting.MEDIUM
      }
      // Initialize auto sleep timer auto rewind added in v0.9.64
      if (_deviceData.deviceSettings?.autoSleepTimerAutoRewindTime == null) {
        _deviceData.deviceSettings?.autoSleepTimerAutoRewindTime = 300000L // 5 minutes
      }
      // Initialize sleep timer almost done chime added in v0.9.81
      if (_deviceData.deviceSettings?.enableSleepTimerAlmostDoneChime == null) {
        _deviceData.deviceSettings?.enableSleepTimerAlmostDoneChime = false
      }

      // Language added in v0.9.69
      if (_deviceData.deviceSettings?.languageCode == null) {
        _deviceData.deviceSettings?.languageCode = "en-us"
      }

      if (_deviceData.deviceSettings?.downloadUsingCellular == null) {
        _deviceData.deviceSettings?.downloadUsingCellular = DownloadUsingCellularSetting.ALWAYS
      }

      if (_deviceData.deviceSettings?.streamingUsingCellular == null) {
        _deviceData.deviceSettings?.streamingUsingCellular = StreamingUsingCellularSetting.ALWAYS
      }
      if (_deviceData.deviceSettings?.androidAutoBrowseLimitForGrouping == null) {
        _deviceData.deviceSettings?.androidAutoBrowseLimitForGrouping = 100
      }
      if (_deviceData.deviceSettings?.androidAutoBrowseSeriesSequenceOrder == null) {
        _deviceData.deviceSettings?.androidAutoBrowseSeriesSequenceOrder =
                AndroidAutoBrowseSeriesSequenceOrderSetting.ASC
      }
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
    return id?.let {
      synchronized(stateLock) {
        _deviceData.serverConnectionConfigs.find { it.id == id }
      }
    }
  }

  /**
   * Reloads device data from database with thread safety.
   * Should be called after external modifications to device data.
   */
  fun reloadDeviceData() {
    synchronized(stateLock) {
      _deviceData = dbManager.getDeviceData()
    }
    Log.d(tag, "Device data reloaded from database")
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
    synchronized(stateLock) {
      _deviceData.lastPlaybackSession = playbackSession
      dbManager.saveDeviceData(_deviceData)
    }
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
