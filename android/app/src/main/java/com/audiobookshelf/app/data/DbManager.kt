package com.audiobookshelf.app.data

import android.util.Log
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

  fun getLocalMediaItemsInFolder(folderId:String):List<LocalMediaItem> {
    var localMediaItems = loadLocalMediaItems()
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
}
