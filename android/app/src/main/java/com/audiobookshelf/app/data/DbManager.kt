package com.audiobookshelf.app.data

import android.util.Log
import io.paperdb.Paper
import org.json.JSONObject

class DbManager {
  val tag = "DbManager"

  fun getDeviceData(): DeviceData {
    return Paper.book("device").read("data") ?: DeviceData(mutableListOf(), null, null)
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

  fun getLocalMediaItem(localMediaItemId:String):LocalMediaItem? {
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
}
