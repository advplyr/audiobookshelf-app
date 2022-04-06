package com.audiobookshelf.app.data

import android.util.Log
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject

@CapacitorPlugin(name = "AbsDatabase")
class AbsDatabase : Plugin() {
  val tag = "AbsDatabase"

  @PluginMethod
  fun getDeviceData(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var deviceData = DeviceManager.dbManager.getDeviceData()
      call.resolve(JSObject(jacksonObjectMapper().writeValueAsString(deviceData)))
    }
  }

  @PluginMethod
  fun getLocalFolders(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var folders = DeviceManager.dbManager.getAllLocalFolders()
      var folderObjArray = jacksonObjectMapper().writeValueAsString(folders)
      var jsobj = JSObject()
      jsobj.put("folders", folderObjArray)
      call.resolve(jsobj)
    }
  }

  @PluginMethod
  fun getLocalFolder(call:PluginCall) {
    var folderId = call.getString("folderId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      DeviceManager.dbManager.getLocalFolder(folderId)?.let {
        var folderObj = jacksonObjectMapper().writeValueAsString(it)
        call.resolve(JSObject(folderObj))
      } ?: call.resolve()
    }
  }

  @PluginMethod
  fun getLocalLibraryItem(call:PluginCall) {
    var id = call.getString("id", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      var localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(id)
      if (localLibraryItem == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonObjectMapper().writeValueAsString(localLibraryItem)))
      }
    }
  }

  @PluginMethod
  fun getLocalLibraryItems(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var localLibraryItems = DeviceManager.dbManager.getLocalLibraryItems()
      var jsobj = JSObject()
      jsobj.put("localLibraryItems", jacksonObjectMapper().writeValueAsString(localLibraryItems))
      call.resolve(jsobj)
    }
  }

  @PluginMethod
  fun getLocalLibraryItemsInFolder(call:PluginCall) {
    var folderId = call.getString("folderId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      var localMediaItems = DeviceManager.dbManager.getLocalLibraryItemsInFolder(folderId)
      var mediaItemsArray = jacksonObjectMapper().writeValueAsString(localMediaItems)
      var jsobj = JSObject()
      jsobj.put("localLibraryItems", mediaItemsArray)
      call.resolve(jsobj)
    }
  }

  @PluginMethod
  fun setCurrentServerConnectionConfig(call:PluginCall) {
    var serverConnectionConfigId = call.getString("id", "").toString()
    var serverConnectionConfig = DeviceManager.deviceData.serverConnectionConfigs.find { it.id == serverConnectionConfigId }

    var username = call.getString("username", "").toString()
    var token = call.getString("token", "").toString()

    GlobalScope.launch(Dispatchers.IO) {
      if (serverConnectionConfig == null) { // New Server Connection
        var serverAddress = call.getString("address", "").toString()

        // Create new server connection config
        var sscId = DeviceManager.getBase64Id("$serverAddress@$username")
        var sscIndex = DeviceManager.deviceData.serverConnectionConfigs.size
        serverConnectionConfig = ServerConnectionConfig(sscId, sscIndex, "$serverAddress ($username)", serverAddress, username, token)

        // Add and save
        DeviceManager.deviceData.serverConnectionConfigs.add(serverConnectionConfig!!)
        DeviceManager.deviceData.lastServerConnectionConfigId = serverConnectionConfig?.id
        DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      } else {
        var shouldSave = false
        if (serverConnectionConfig?.username != username || serverConnectionConfig?.token != token) {
          serverConnectionConfig?.username = username
          serverConnectionConfig?.name = "${serverConnectionConfig?.address} (${serverConnectionConfig?.username})"
          serverConnectionConfig?.token = token
          shouldSave = true
        }

        // Set last connection config
        if (DeviceManager.deviceData.lastServerConnectionConfigId != serverConnectionConfigId) {
          DeviceManager.deviceData.lastServerConnectionConfigId = serverConnectionConfigId
          shouldSave = true
        }

        if (shouldSave) DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      }

      DeviceManager.serverConnectionConfig = serverConnectionConfig
      call.resolve(JSObject(jacksonObjectMapper().writeValueAsString(DeviceManager.serverConnectionConfig)))
    }
  }

  @PluginMethod
  fun removeServerConnectionConfig(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var serverConnectionConfigId = call.getString("serverConnectionConfigId", "").toString()
      DeviceManager.deviceData.serverConnectionConfigs = DeviceManager.deviceData.serverConnectionConfigs.filter { it.id != serverConnectionConfigId } as MutableList<ServerConnectionConfig>
      if (DeviceManager.deviceData.lastServerConnectionConfigId == serverConnectionConfigId) {
        DeviceManager.deviceData.lastServerConnectionConfigId = null
      }
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      if (DeviceManager.serverConnectionConfig?.id == serverConnectionConfigId) {
        DeviceManager.serverConnectionConfig = null
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun logout(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      DeviceManager.serverConnectionConfig = null
      DeviceManager.deviceData.lastServerConnectionConfigId = null
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      call.resolve()
    }
  }

  //
  // Generic Webview calls to db
  //
  @PluginMethod
  fun saveFromWebview(call: PluginCall) {
    var db = call.getString("db", "").toString()
    var key = call.getString("key", "").toString()
    var value = call.getObject("value")

    GlobalScope.launch(Dispatchers.IO) {
      if (db == "" || key == "" || value == null) {
        Log.d(tag, "saveFromWebview Invalid key/value")
      } else {
        var json = value as JSONObject
        DeviceManager.dbManager.saveObject(db, key, json)
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun loadFromWebview(call:PluginCall) {
    var db = call.getString("db", "").toString()
    var key = call.getString("key", "").toString()
    if (db == "" || key == "") {
      Log.d(tag, "loadFromWebview Invalid Key")
      call.resolve()
      return
    }
    var json = DeviceManager.dbManager.loadObject(db, key)
    var jsobj = JSObject.fromJSONObject(json)
    call.resolve(jsobj)
  }
}
