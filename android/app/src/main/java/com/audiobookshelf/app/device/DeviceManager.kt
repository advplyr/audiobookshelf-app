package com.audiobookshelf.app.device

import android.util.Log
import com.audiobookshelf.app.data.DbManager
import com.audiobookshelf.app.data.DeviceData
import com.audiobookshelf.app.data.ServerConnectionConfig

object DeviceManager {
  const val tag = "DeviceManager"

  val dbManager:DbManager = DbManager()
  var deviceData:DeviceData = dbManager.getDeviceData()
  var serverConnectionConfig: ServerConnectionConfig? = null

  val serverConnectionConfigId get() = serverConnectionConfig?.id ?: ""
  val serverAddress get() = serverConnectionConfig?.address ?: ""
  val serverUserId get() = serverConnectionConfig?.userId ?: ""
  val token get() = serverConnectionConfig?.token ?: ""
  val isConnectedToServer get() = serverConnectionConfig != null
  val hasLastServerConnectionConfig get() = deviceData.getLastServerConnectionConfig() != null

  init {
    Log.d(tag, "Device Manager Singleton invoked")
  }

  fun getBase64Id(id:String):String {
    return android.util.Base64.encodeToString(id.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
  }

  fun getServerConnectionConfig(id:String?):ServerConnectionConfig? {
    if (id == null) return null
    return deviceData.serverConnectionConfigs.find { it.id == id }
  }
}
