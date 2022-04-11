package com.audiobookshelf.app.data

import android.util.Log
import com.audiobookshelf.app.plugins.AbsDownloader
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

  fun getLocalLibraryItems(mediaType:String? = null):MutableList<LocalLibraryItem> {
    var localLibraryItems:MutableList<LocalLibraryItem> = mutableListOf()
    Paper.book("localLibraryItems").allKeys.forEach {
      var localLibraryItem:LocalLibraryItem? = Paper.book("localLibraryItems").read(it)
      if (localLibraryItem != null && (mediaType.isNullOrEmpty() || mediaType == localLibraryItem?.mediaType)) {
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

  fun getLocalLibraryItemByLLId(libraryItemId:String):LocalLibraryItem? {
    return getLocalLibraryItems().find { it.libraryItemId == libraryItemId }
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

  fun saveLocalLibraryItem(localLibraryItem:LocalLibraryItem) {
    Paper.book("localLibraryItems").write(localLibraryItem.id, localLibraryItem)
  }

  fun saveLocalFolder(localFolder:LocalFolder) {
    Paper.book("localFolders").write(localFolder.id,localFolder)
  }

  fun getLocalFolder(folderId:String):LocalFolder? {
    return Paper.book("localFolders").read(folderId)
  }

  fun getAllLocalFolders():List<LocalFolder> {
    var localFolders:MutableList<LocalFolder> = mutableListOf()
    Paper.book("localFolders").allKeys.forEach { localFolderId ->
      Paper.book("localFolders").read<LocalFolder>(localFolderId)?.let {
        localFolders.add(it)
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

  fun saveDownloadItem(downloadItem: AbsDownloader.DownloadItem) {
    Paper.book("downloadItems").write(downloadItem.id, downloadItem)
  }

  fun removeDownloadItem(downloadItemId:String) {
    Paper.book("downloadItems").delete(downloadItemId)
  }

  fun getDownloadItems():List<AbsDownloader.DownloadItem> {
    var downloadItems:MutableList<AbsDownloader.DownloadItem> = mutableListOf()
    Paper.book("downloadItems").allKeys.forEach { downloadItemId ->
      Paper.book("downloadItems").read<AbsDownloader.DownloadItem>(downloadItemId)?.let {
        downloadItems.add(it)
      }
    }
    return downloadItems
  }

  fun saveLocalMediaProgress(mediaProgress:LocalMediaProgress) {
    Paper.book("localMediaProgress").write(mediaProgress.id,mediaProgress)
  }
  // For books this will just be the localLibraryItemId for podcast episodes this will be "{localLibraryItemId}-{episodeId}"
  fun getLocalMediaProgress(localMediaProgressId:String):LocalMediaProgress? {
    return Paper.book("localMediaProgress").read(localMediaProgressId)
  }
  fun getAllLocalMediaProgress():List<LocalMediaProgress> {
    var mediaProgress:MutableList<LocalMediaProgress> = mutableListOf()
    Paper.book("localMediaProgress").allKeys.forEach { localMediaProgressId ->
      Paper.book("localMediaProgress").read<LocalMediaProgress>(localMediaProgressId)?.let {
        mediaProgress.add(it)
      }
    }
    return mediaProgress
  }
  fun removeLocalMediaProgress(localMediaProgressId:String) {
    Paper.book("localMediaProgress").delete(localMediaProgressId)
  }

  fun saveLocalPlaybackSession(playbackSession:PlaybackSession) {
    Paper.book("localPlaybackSession").write(playbackSession.id,playbackSession)
  }
  fun getLocalPlaybackSession(playbackSessionId:String):PlaybackSession? {
    return Paper.book("localPlaybackSession").read(playbackSessionId)
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
