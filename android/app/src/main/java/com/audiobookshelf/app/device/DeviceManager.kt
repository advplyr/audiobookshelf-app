package com.audiobookshelf.app.device

import android.util.Log
import com.anggrayudi.storage.file.id
import com.audiobookshelf.app.data.DbManager
import com.audiobookshelf.app.data.DeviceData
import com.audiobookshelf.app.data.ServerConfig

object DeviceManager {
  val tag = "DeviceManager"
  val dbManager:DbManager = DbManager()
  var deviceData:DeviceData = dbManager.loadDeviceData()
  var currentServerConfig: ServerConfig? = null

  val serverAddress get() = currentServerConfig?.address ?: ""
  val token get() = currentServerConfig?.token ?: ""

  init {
    Log.d(tag, "Device Manager Singleton invoked")
  }

  fun getBase64Id(id:String):String {
    return android.util.Base64.encodeToString(id.toByteArray(), android.util.Base64.DEFAULT)
  }
}
