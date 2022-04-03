package com.audiobookshelf.app.data

import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import io.paperdb.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

@CapacitorPlugin(name = "DbManager")
class DbManager : Plugin() {
  val tag = "DbManager"

  fun getDeviceData(): DeviceData {
    return Paper.book("device").read("data") ?: DeviceData(mutableListOf(), null)
  }
  fun saveDeviceData(deviceData:DeviceData) {
    Paper.book("device").write("data", deviceData)
  }

  fun getLocalMediaItems():MutableList<LocalMediaItem> {
    var localMediaItems:MutableList<LocalMediaItem> = mutableListOf()
    Paper.book("localMediaItems").allKeys.forEach {
      var localMediaItem:LocalMediaItem? = Paper.book("localMediaItems").read(it)
      if (localMediaItem != null) {
        // TODO: Check to make sure all file paths exist
//        if (localMediaItem.coverContentUrl != null) {
//          var file = DocumentFile.fromSingleUri(ctx)
//          if (!file.exists()) {
//            Log.e(tag, "Local media item cover url does not exist ${localMediaItem.coverContentUrl}")
//            removeLocalMediaItem(localMediaItem.id)
//          } else {
//            localMediaItems.add(localMediaItem)
//          }
//        } else {
          localMediaItems.add(localMediaItem)
//        }
      }
    }
    return localMediaItems
  }

  fun getLocalMediaItemsInFolder(folderId:String):List<LocalMediaItem> {
    var localMediaItems = getLocalMediaItems()
    return localMediaItems.filter {
      it.folderId == folderId
    }
  }

  fun loadLocalMediaItem(localMediaItemId:String):LocalMediaItem? {
    return Paper.book("localMediaItems").read(localMediaItemId)
  }

  fun removeLocalMediaItem(localMediaItemId:String) {
    Paper.book("localMediaItems").delete(localMediaItemId)
  }

  fun saveLocalMediaItems(localMediaItems:List<LocalMediaItem>) {
      localMediaItems.map {
        Paper.book("localMediaItems").write(it.id, it)
      }
  }

  fun saveLocalFolder(localFolder:LocalFolder) {
    Paper.book("localFolders").write(localFolder.id,localFolder)
  }

  fun getLocalFolder(folderId:String):LocalFolder? {
    return Paper.book("localFolders").read(folderId)
  }

  fun getAllLocalFolders():List<LocalFolder> {
    var localFolders:MutableList<LocalFolder> = mutableListOf()
    Paper.book("localFolders").allKeys.forEach {
      var localFolder:LocalFolder? = Paper.book("localFolders").read(it)
      if (localFolder != null) {
        localFolders.add(localFolder)
      }
    }
    return localFolders
  }

  fun removeLocalFolder(folderId:String) {
    var localMediaItems = getLocalMediaItemsInFolder(folderId)
    localMediaItems.forEach {
      Paper.book("localMediaItems").delete(it.id)
    }
    Paper.book("localFolders").delete(folderId)
  }

  fun saveObject(db:String, key:String, value:JSONObject) {
    Log.d(tag, "Saving Object $key ${value.toString()}")
    Paper.book(db).write(key, value)
  }

  fun loadObject(db:String, key:String):JSONObject? {
    var json: JSONObject? = Paper.book(db).read(key)
    Log.d(tag, "Loaded Object $key $json")
    return json
  }

  //
  // Database calls from webview
  //
  @PluginMethod
  fun getDeviceData_WV(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var deviceData = getDeviceData()
      call.resolve(JSObject(jacksonObjectMapper().writeValueAsString(deviceData)))
    }
  }

  @PluginMethod
  fun getLocalFolders_WV(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var folders = getAllLocalFolders()
      var folderObjArray = jacksonObjectMapper().writeValueAsString(folders)
      var jsobj = JSObject()
      jsobj.put("folders", folderObjArray)
      call.resolve(jsobj)
    }
  }

  @PluginMethod
  fun getLocalFolder_WV(call:PluginCall) {
    var folderId = call.getString("folderId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      getLocalFolder(folderId)?.let {
        var folderObj = jacksonObjectMapper().writeValueAsString(it)
        call.resolve(JSObject(folderObj))
      } ?: call.resolve()
    }
  }

  @PluginMethod
  fun getLocalMediaItemsInFolder_WV(call:PluginCall) {
    var folderId = call.getString("folderId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      var localMediaItems = getLocalMediaItemsInFolder(folderId)
      var mediaItemsArray = jacksonObjectMapper().writeValueAsString(localMediaItems)
      var jsobj = JSObject()
      jsobj.put("localMediaItems", mediaItemsArray)
      call.resolve(jsobj)
    }
  }

  @PluginMethod
  fun setCurrentServerConnectionConfig_WV(call:PluginCall) {
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
        saveDeviceData(DeviceManager.deviceData)
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

        if (shouldSave) saveDeviceData(DeviceManager.deviceData)
      }

      DeviceManager.serverConnectionConfig = serverConnectionConfig
      call.resolve(JSObject(jacksonObjectMapper().writeValueAsString(DeviceManager.serverConnectionConfig)))
    }
  }

  @PluginMethod
  fun removeServerConnectionConfig_WV(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      var serverConnectionConfigId = call.getString("serverConnectionConfigId", "").toString()
      DeviceManager.deviceData.serverConnectionConfigs = DeviceManager.deviceData.serverConnectionConfigs.filter { it.id != serverConnectionConfigId } as MutableList<ServerConnectionConfig>
      if (DeviceManager.deviceData.lastServerConnectionConfigId == serverConnectionConfigId) {
        DeviceManager.deviceData.lastServerConnectionConfigId = null
      }
      saveDeviceData(DeviceManager.deviceData)
      if (DeviceManager.serverConnectionConfig?.id == serverConnectionConfigId) {
        DeviceManager.serverConnectionConfig = null
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun logout_WV(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      DeviceManager.serverConnectionConfig = null
      DeviceManager.deviceData.lastServerConnectionConfigId = null
      saveDeviceData(DeviceManager.deviceData)
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
        saveObject(db, key, json)
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
    var json = loadObject(db, key)
    var jsobj = JSObject.fromJSONObject(json)
    call.resolve(jsobj)
  }
}
