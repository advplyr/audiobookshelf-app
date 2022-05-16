package com.bookshelf.app.device

import android.util.Log
import com.bookshelf.app.data.DbManager
import com.bookshelf.app.data.DeviceData
import com.bookshelf.app.data.ServerConnectionConfig

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
    return android.util.Base64.encodeToString(id.toByteArray(), android.util.Base64.NO_WRAP)
  }
}
