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

interface WidgetEventEmitter {
  fun onPlayerChanged(pns:PlayerNotificationService)
  fun onPlayerClosed()
}

object DeviceManager {
  const val tag = "DeviceManager"

  val dbManager: DbManager = DbManager()
  var deviceData: DeviceData = dbManager.getDeviceData()
  var serverConnectionConfig: ServerConnectionConfig? = null

  val serverConnectionConfigId get() = serverConnectionConfig?.id ?: ""
  val serverAddress get() = serverConnectionConfig?.address ?: ""
  val serverUserId get() = serverConnectionConfig?.userId ?: ""
  val token get() = serverConnectionConfig?.token ?: ""
  val isConnectedToServer get() = serverConnectionConfig != null

  var widgetUpdater:WidgetEventEmitter? = null

  init {
    Log.d(tag, "Device Manager Singleton invoked")

    // Initialize new sleep timer settings and shake sensitivity added in v0.9.61
    if (deviceData.deviceSettings?.autoSleepTimerStartTime == null || deviceData.deviceSettings?.autoSleepTimerEndTime == null) {
      deviceData.deviceSettings?.autoSleepTimerStartTime = "22:00"
      deviceData.deviceSettings?.autoSleepTimerStartTime = "06:00"
      deviceData.deviceSettings?.sleepTimerLength = 900000L
    }
    if (deviceData.deviceSettings?.shakeSensitivity == null) {
      deviceData.deviceSettings?.shakeSensitivity = ShakeSensitivitySetting.MEDIUM
    }
    // Initialize auto sleep timer auto rewind added in v0.9.64
    if (deviceData.deviceSettings?.autoSleepTimerAutoRewindTime == null) {
      deviceData.deviceSettings?.autoSleepTimerAutoRewindTime = 300000L // 5 minutes
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
  }

  fun getBase64Id(id:String):String {
    return android.util.Base64.encodeToString(id.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
  }

  fun getServerConnectionConfig(id:String?):ServerConnectionConfig? {
    if (id == null) return null
    return deviceData.serverConnectionConfigs.find { it.id == id }
  }

  fun checkConnectivity(ctx:Context): Boolean {
    val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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

  fun setLastPlaybackSession(playbackSession:PlaybackSession) {
    deviceData.lastPlaybackSession = playbackSession
    dbManager.saveDeviceData(deviceData)
  }

  fun initializeWidgetUpdater(context:Context) {
    Log.d(tag, "Initializing widget updater")
    widgetUpdater = (object : WidgetEventEmitter {
      override fun onPlayerChanged(pns: PlayerNotificationService) {

        val isPlaying = pns.currentPlayer.isPlaying

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, MediaPlayerWidget::class.java)
        val ids = appWidgetManager.getAppWidgetIds(componentName)
        val playbackSession = pns.getCurrentPlaybackSessionCopy()

        for (widgetId in ids) {
          updateAppWidget(context, appWidgetManager, widgetId, playbackSession, isPlaying, PlayerNotificationService.isClosed)
        }
      }
      override fun onPlayerClosed() {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, MediaPlayerWidget::class.java)
        val ids = appWidgetManager.getAppWidgetIds(componentName)
        for (widgetId in ids) {
          updateAppWidget(context, appWidgetManager, widgetId, deviceData.lastPlaybackSession, false, PlayerNotificationService.isClosed)
        }
      }
    })
  }
}
