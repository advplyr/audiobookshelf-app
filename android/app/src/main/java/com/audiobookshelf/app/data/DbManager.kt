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

  fun getLocalLibraryItems():MutableList<LocalLibraryItem> {
    var localLibraryItems:MutableList<LocalLibraryItem> = mutableListOf()
    Paper.book("localLibraryItems").allKeys.forEach {
      var localLibraryItem:LocalLibraryItem? = Paper.book("localLibraryItems").read(it)
      if (localLibraryItem != null) {
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
        localLibraryItems.add(localLibraryItem)
//        }
      }
    }
    return localLibraryItems
  }

  fun getLocalLibraryItemsInFolder(folderId:String):List<LocalLibraryItem> {
    var localLibraryItems = getLocalLibraryItems()
    return localLibraryItems.filter {
      it.folderId == folderId
    }
  }

  fun getLocalLibraryItem(localLibraryItemId:String):LocalLibraryItem? {
    return Paper.book("localLibraryItems").read(localLibraryItemId)
  }

  fun removeLocalLibraryItem(localLibraryItemId:String) {
    Paper.book("localLibraryItems").delete(localLibraryItemId)
  }

  fun saveLocalLibraryItems(localLibraryItems:List<LocalLibraryItem>) {
    localLibraryItems.map {
        Paper.book("localLibraryItems").write(it.id, it)
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
    var localLibraryItems = getLocalLibraryItemsInFolder(folderId)
    localLibraryItems.forEach {
      Paper.book("localLibraryItems").delete(it.id)
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
