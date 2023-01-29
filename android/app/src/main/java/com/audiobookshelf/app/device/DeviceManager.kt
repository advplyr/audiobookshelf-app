package com.audiobookshelf.app.device

import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService

interface WidgetEventEmitter {
  fun onPlayerChanged(pns:PlayerNotificationService)
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
  }

  fun getBase64Id(id:String):String {
    return android.util.Base64.encodeToString(id.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
  }

  fun getServerConnectionConfig(id:String?):ServerConnectionConfig? {
    if (id == null) return null
    return deviceData.serverConnectionConfigs.find { it.id == id }
  }
}
