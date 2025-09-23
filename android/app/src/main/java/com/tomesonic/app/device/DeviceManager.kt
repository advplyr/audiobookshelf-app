package com.tomesonic.app.device

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.tomesonic.app.MediaPlayerWidget
import com.tomesonic.app.data.*
import com.tomesonic.app.managers.DbManager
import com.tomesonic.app.player.PlayerNotificationService
import com.tomesonic.app.updateAppWidget

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

    // Initialize server connection config from persisted last server connection config ID
    Log.d(tag, "DeviceManager init: About to initialize server connection config")
    Log.d(tag, "DeviceManager init: lastServerConnectionConfigId = ${deviceData.lastServerConnectionConfigId}")
    Log.d(tag, "DeviceManager init: Available server configs count = ${deviceData.serverConnectionConfigs.size}")
    initializeServerConnectionConfig()
    Log.d(tag, "DeviceManager init: After initialization, serverConnectionConfig = ${serverConnectionConfig?.name}")
  }

  /**
   * Initialize the server connection config from the persisted last server connection config ID
   */
  private fun initializeServerConnectionConfig() {
    Log.d(tag, "initializeServerConnectionConfig: Starting initialization")
    deviceData.lastServerConnectionConfigId?.let { configId ->
      Log.d(tag, "initializeServerConnectionConfig: Found lastServerConnectionConfigId = $configId")
      val config = getServerConnectionConfig(configId)
      if (config != null) {
        serverConnectionConfig = config
        Log.d(tag, "initializeServerConnectionConfig: Successfully initialized server connection config: ${config.name} (${config.id})")
        Log.d(tag, "initializeServerConnectionConfig: Server address: ${config.address}")
      } else {
        Log.w(tag, "initializeServerConnectionConfig: Could not find server connection config for ID: $configId")
        Log.w(tag, "initializeServerConnectionConfig: Available config IDs: ${deviceData.serverConnectionConfigs.map { it.id }}")
      }
    } ?: run {
      Log.d(tag, "initializeServerConnectionConfig: No last server connection config ID found")
      Log.d(tag, "initializeServerConnectionConfig: Available server configs count: ${deviceData.serverConnectionConfigs.size}")
      if (deviceData.serverConnectionConfigs.isNotEmpty()) {
        Log.d(tag, "initializeServerConnectionConfig: Available configs: ${deviceData.serverConnectionConfigs.map { "${it.name} (${it.id})" }}")

        // If there's only one server config and no last config is set, use it as the default
        if (deviceData.serverConnectionConfigs.size == 1) {
          val defaultConfig = deviceData.serverConnectionConfigs.first()
          Log.d(tag, "initializeServerConnectionConfig: Auto-selecting single available server config: ${defaultConfig.name}")
          serverConnectionConfig = defaultConfig
          deviceData.lastServerConnectionConfigId = defaultConfig.id
          // Save the updated device data
          dbManager.saveDeviceData(deviceData)
          Log.d(tag, "initializeServerConnectionConfig: Set and saved default server config: ${defaultConfig.name} (${defaultConfig.id})")
        }
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
