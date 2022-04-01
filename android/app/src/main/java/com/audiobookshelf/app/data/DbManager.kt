package com.audiobookshelf.app.data

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import io.paperdb.Paper
import org.json.JSONObject

@CapacitorPlugin(name = "DbManager")
class DbManager : Plugin() {
  val tag = "DbManager"

  fun loadDeviceData(): DeviceData {
    return Paper.book("device").read("data") ?: DeviceData(mutableListOf(), null)
  }

  fun saveDeviceData(deviceData:DeviceData) {
    Paper.book("device").write("data", deviceData)
  }

  fun loadLocalMediaItems():List<LocalMediaItem> {
    var localMediaItems:MutableList<LocalMediaItem> = mutableListOf()
    Paper.book("localMediaItems").allKeys.forEach {
      var localMediaItem:LocalMediaItem? = Paper.book("localMediaItems").read(it)
      if (localMediaItem != null) {
        localMediaItems.add(localMediaItem)
      }
    }
    return localMediaItems
  }

  fun loadLocalMediaItem(localMediaItemId:String):LocalMediaItem? {
    return Paper.book("localMediaItems").read(localMediaItemId)
  }

  fun saveLocalMediaItems(localMediaItems:List<LocalMediaItem>) {
    localMediaItems.map {
      Paper.book("localMediaItems").write(it.id, it)
    }
  }

  fun saveLocalFolder(localFolder:LocalFolder) {
    Paper.book("localFolders").write(localFolder.id,localFolder)
  }

  fun loadLocalFolder(folderId:String):LocalFolder? {
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

  fun saveObject(db:String, key:String, value:JSONObject) {
    Log.d(tag, "Saving Object $key ${value.toString()}")
    Paper.book(db).write(key, value)
  }

  fun loadObject(db:String, key:String):JSONObject? {
    var json: JSONObject? = Paper.book(db).read(key)
    Log.d(tag, "Loaded Object $key $json")
    return json
  }

  @PluginMethod
  fun saveFromWebview(call: PluginCall) {
    var db = call.getString("db", "").toString()
    var key = call.getString("key", "").toString()
    var value = call.getObject("value")
    if (db == "" || key == "" || value == null) {
      Log.d(tag, "saveFromWebview Invalid key/value")
    } else {
      var json = value as JSONObject
      saveObject(db, key, json)
    }
    call.resolve()
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

  @PluginMethod
  fun localFoldersFromWebView(call:PluginCall) {
    var folders = getAllLocalFolders()
    var folderObjArray = jacksonObjectMapper().writeValueAsString(folders)
    var jsobj = JSObject()
    jsobj.put("folders", folderObjArray)
    call.resolve(jsobj)
  }

  @PluginMethod
  fun loadMediaItemsInFolder(call:PluginCall) {
    var folderId = call.getString("folderId", "").toString()
    var localMediaItems = loadLocalMediaItems().filter {
      it.folderId == folderId
    }

    var mediaItemsArray = jacksonObjectMapper().writeValueAsString(localMediaItems)
    var jsobj = JSObject()
    jsobj.put("localMediaItems", mediaItemsArray)
    call.resolve(jsobj)
  }
}
