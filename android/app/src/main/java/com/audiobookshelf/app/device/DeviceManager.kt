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
  var deviceData: DeviceData = dbManager.getDeviceData()
  var serverConnectionConfig: ServerConnectionConfig? = null

  val serverConnectionConfigId
    get() = serverConnectionConfig?.id ?: ""
  val serverAddress
    get() = serverConnectionConfig?.address ?: ""
  val serverUserId
    get() = serverConnectionConfig?.userId ?: ""
  val token
    get() = serverConnectionConfig?.token ?: ""
  val isConnectedToServer
    get() = serverConnectionConfig != null

  var widgetUpdater: WidgetEventEmitter? = null

  init {
    Log.d(tag, "Device Manager Singleton invoked")

    // Default settings if they have not been set yet. Removes Elvis operator for null safety due to
    // variables being non-nullable.
    deviceData.deviceSettings?.apply {
      // Sleep timer settings, added v0.9.61
      autoSleepTimerStartTime = "22:00"
      autoSleepTimerEndTime = "06:00"
      sleepTimerLength = 900000L
      shakeSensitivity = ShakeSensitivitySetting.MEDIUM
      // Auto sleep timer auto rewind, added v0.9.64
      autoSleepTimerAutoRewindTime = 300000L // 5 minutes
      // Langugage code, added v0.9.69
      languageCode = "en-us"
      // Download and streaming using cellular, added v0.9.75
      downloadUsingCellular = DownloadUsingCellularSetting.ALWAYS
      streamingUsingCellular = StreamingUsingCellularSetting.ALWAYS
      // Android Auto settings, added v0.9.78
      androidAutoBrowseLimitForGrouping = 100
      androidAutoBrowseSeriesSequenceOrder = AndroidAutoBrowseSeriesSequenceOrderSetting.ASC
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
    if (id == null) return null
    return deviceData.serverConnectionConfigs.find { it.id == id }
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
