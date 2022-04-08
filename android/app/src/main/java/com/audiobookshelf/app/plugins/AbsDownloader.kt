package com.audiobookshelf.app.plugins

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.*


@CapacitorPlugin(name = "AbsDownloader")
class AbsDownloader : Plugin() {
  private val tag = "AbsDownloader"

  lateinit var mainActivity: MainActivity
  lateinit var downloadManager: DownloadManager
  lateinit var apiHandler: ApiHandler
  lateinit var folderScanner: FolderScanner

  data class DownloadItemPart(
    val id: String,
    val filename: String,
    val destinationPath:String,
    val itemTitle: String,
    val serverPath: String,
    val localFolderName: String,
    val localFolderId: String,
    val audioTrack: AudioTrack?,
    var completed:Boolean,
    @JsonIgnore val uri: Uri,
    @JsonIgnore val destinationUri: Uri,
    var downloadId: Long?,
    var progress: Long
  ) {
    @JsonIgnore
    fun getDownloadRequest(): DownloadManager.Request {
      var dlRequest = DownloadManager.Request(uri)
      dlRequest.setTitle(filename)
      dlRequest.setDescription("Downloading to $localFolderName for book $itemTitle")
      dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      dlRequest.setDestinationUri(destinationUri)
      return dlRequest
    }
  }

  data class DownloadItem(
    val id: String,
    val mediaType: String,
    val itemFolderPath:String,
    val localFolder: LocalFolder,
    val itemTitle: String,
    val media:MediaType,
    val downloadItemParts: MutableList<DownloadItemPart>
  )

  var downloadQueue: MutableList<DownloadItem> = mutableListOf()

  override fun load() {
    mainActivity = (activity as MainActivity)
    downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    folderScanner = FolderScanner(mainActivity)
    apiHandler = ApiHandler(mainActivity)

    var recieverEvent: (evt: String, id: Long) -> Unit = { evt: String, id: Long ->
      if (evt == "complete") {
      }
      if (evt == "clicked") {
        Log.d(tag, "Clicked $id back in the downloader")
      }
    }
    mainActivity.registerBroadcastReceiver(recieverEvent)

    Log.d(tag, "Build SDK ${Build.VERSION.SDK_INT}")
  }

  @PluginMethod
  fun downloadLibraryItem(call: PluginCall) {
    var libraryItemId = call.data.getString("libraryItemId").toString()
    var localFolderId = call.data.getString("localFolderId").toString()
    Log.d(tag, "Download library item $libraryItemId to folder $localFolderId")

    if (downloadQueue.find { it.id == libraryItemId } != null) {
      Log.d(tag, "Download already started for this library item $libraryItemId")
      return call.resolve(JSObject("{\"error\":\"Download already started for this library item\"}"))
    }

    apiHandler.getLibraryItem(libraryItemId) { libraryItem ->
      Log.d(tag, "Got library item from server ${libraryItem.id}")
      var localFolder = DeviceManager.dbManager.getLocalFolder(localFolderId)
      if (localFolder != null) {
        startLibraryItemDownload(libraryItem, localFolder)
        call.resolve()
      } else {
        call.resolve(JSObject("{\"error\":\"Local Folder Not Found\"}"))
      }
    }
  }

  // Clean folder path so it can be used in URL
  fun cleanRelPath(relPath: String): String {
    var cleanedRelPath = relPath.replace("\\", "/").replace("%", "%25").replace("#", "%23")
    return if (cleanedRelPath.startsWith("/")) cleanedRelPath.substring(1) else cleanedRelPath
  }

  // Item filenames could be the same if they are in subfolders, this will make them unique
  fun getFilenameFromRelPath(relPath: String): String {
    var cleanedRelPath = relPath.replace("\\", "_").replace("/", "_")
    return if (cleanedRelPath.startsWith("_")) cleanedRelPath.substring(1) else cleanedRelPath
  }

  fun getAbMetadataText(libraryItem:LibraryItem):String {
    var bookMedia = libraryItem.media as com.audiobookshelf.app.data.Book
    var fileString = ";ABMETADATA1\n"
//    fileString += "#libraryItemId=${libraryItem.id}\n"
//    fileString += "title=${bookMedia.metadata.title}\n"
//    fileString += "author=${bookMedia.metadata.authorName}\n"
//    fileString += "narrator=${bookMedia.metadata.narratorName}\n"
//    fileString += "series=${bookMedia.metadata.seriesName}\n"
    return fileString
  }

  fun startLibraryItemDownload(libraryItem: LibraryItem, localFolder: LocalFolder) {
    if (libraryItem.mediaType == "book") {
      var bookTitle = libraryItem.media.metadata.title
      var tracks = libraryItem.media.getAudioTracks()
      Log.d(tag, "Starting library item download with ${tracks.size} tracks")
      var itemFolderPath = localFolder.absolutePath + "/" + bookTitle
      var downloadItem = DownloadItem(libraryItem.id, libraryItem.mediaType, itemFolderPath, localFolder, bookTitle, libraryItem.media, mutableListOf())

      // Create download item part for each audio track
      tracks.forEach { audioTrack ->
        var serverPath = "/s/item/${libraryItem.id}/${cleanRelPath(audioTrack.relPath)}"
        var destinationFilename = getFilenameFromRelPath(audioTrack.relPath)
        Log.d(tag, "Audio File Server Path $serverPath | AF RelPath ${audioTrack.relPath} | LocalFolder Path ${localFolder.absolutePath} | DestName ${destinationFilename}")
        var destinationFile = File("$itemFolderPath/$destinationFilename")

        if (destinationFile.exists()) {
          Log.d(tag, "Audio file already exists, removing it from ${destinationFile.absolutePath}")
          destinationFile.delete()
        }

        var destinationUri = Uri.fromFile(destinationFile)
        var downloadUri = Uri.parse("${DeviceManager.serverAddress}${serverPath}?token=${DeviceManager.token}")
        Log.d(tag, "Audio File Destination Uri $destinationUri | Download URI $downloadUri")
        var downloadItemPart = DownloadItemPart(DeviceManager.getBase64Id(destinationFile.absolutePath), destinationFilename, destinationFile.absolutePath, bookTitle, serverPath, localFolder.name, localFolder.id, audioTrack, false, downloadUri, destinationUri, null, 0)

        downloadItem.downloadItemParts.add(downloadItemPart)

        var dlRequest = downloadItemPart.getDownloadRequest()
        var downloadId = downloadManager.enqueue(dlRequest)
        downloadItemPart.downloadId = downloadId
      }

      if (downloadItem.downloadItemParts.isNotEmpty()) {
        // Add cover download item
        if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
          var serverPath = "/api/items/${libraryItem.id}/cover?format=jpeg"
          var destinationFilename = "cover.jpg"
          var destinationFile = File("$itemFolderPath/$destinationFilename")

          if (destinationFile.exists()) {
            Log.d(tag, "Cover already exists, removing it from ${destinationFile.absolutePath}")
            destinationFile.delete()
          }

          var destinationUri = Uri.fromFile(destinationFile)
          var downloadUri = Uri.parse("${DeviceManager.serverAddress}${serverPath}&token=${DeviceManager.token}")
          var downloadItemPart = DownloadItemPart(DeviceManager.getBase64Id(destinationFile.absolutePath), destinationFilename, destinationFile.absolutePath, bookTitle, serverPath, localFolder.name, localFolder.id, null, false, downloadUri, destinationUri, null, 0)

          downloadItem.downloadItemParts.add(downloadItemPart)

          var dlRequest = downloadItemPart.getDownloadRequest()
          var downloadId = downloadManager.enqueue(dlRequest)
          downloadItemPart.downloadId = downloadId
        }

        // TODO: Cannot create new text file here but can download here... ??
//        var abmetadataFile = File(itemFolderPath, "abmetadata.abs")
//        abmetadataFile.createNewFileIfPossible()
//        abmetadataFile.writeText(getAbMetadataText(libraryItem))

        downloadQueue.add(downloadItem)
        startWatchingDownloads(downloadItem)
        DeviceManager.dbManager.saveDownloadItem(downloadItem)
      }
    } else {
      // TODO: Download podcast episode(s)
    }
  }

  fun startWatchingDownloads(downloadItem: DownloadItem) {
    GlobalScope.launch(Dispatchers.IO) {
      while (downloadItem.downloadItemParts.find { !it.completed } != null) { // While some item is not completed
        var numPartsBefore = downloadItem.downloadItemParts.size
        checkDownloads(downloadItem)

        // Keep database updated as item parts finish downloading
        if (downloadItem.downloadItemParts.size > 0 && downloadItem.downloadItemParts.size != numPartsBefore) {
          Log.d(tag, "Save download item on num parts changed from $numPartsBefore to ${downloadItem.downloadItemParts.size}")
          DeviceManager.dbManager.saveDownloadItem(downloadItem)
        }

        notifyListeners("onItemDownloadUpdate", JSObject(jacksonObjectMapper().writeValueAsString(downloadItem)))
        delay(500)
      }

      var localLibraryItem = folderScanner.scanDownloadItem(downloadItem)
      DeviceManager.dbManager.removeDownloadItem(downloadItem.id)
      downloadQueue.remove(downloadItem)

      Log.d(tag, "Item download complete ${downloadItem.itemTitle} | local library item id: ${localLibraryItem?.id} | Items remaining in Queue ${downloadQueue.size}")

      var jsobj = JSObject()
      jsobj.put("libraryItemId", downloadItem.id)
      jsobj.put("localFolderId", downloadItem.localFolder.id)
      if (localLibraryItem != null) {
        jsobj.put("localLibraryItem", JSObject(jacksonObjectMapper().writeValueAsString(localLibraryItem)))
      }
      notifyListeners("onItemDownloadComplete", jsobj)
    }
  }

  fun checkDownloads(downloadItem: DownloadItem) {
    var itemParts = downloadItem.downloadItemParts.map { it }
    for (downloadItemPart in itemParts) {
      if (downloadItemPart.downloadId != null) {
        var dlid = downloadItemPart.downloadId!!
        val query = DownloadManager.Query().setFilterById(dlid)
        downloadManager.query(query).use {
          if (it.moveToFirst()) {
            val totalBytes = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val downloadStatus = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
            val bytesDownloadedSoFar = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} bytes $totalBytes | bytes dled $bytesDownloadedSoFar | downloadStatus $downloadStatus")

            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
              Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Done")
//              downloadItem.downloadItemParts.remove(downloadItemPart)
              downloadItemPart.completed = true
            } else if (downloadStatus == DownloadManager.STATUS_FAILED) {
              Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Failed")
              downloadItem.downloadItemParts.remove(downloadItemPart)
//              downloadItemPart.completed = true
            } else {
              //update progress
              val percentProgress = if (totalBytes > 0) ((bytesDownloadedSoFar * 100L) / totalBytes) else 0
              Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Progress = $percentProgress%")
              downloadItemPart.progress = percentProgress
            }
          } else {
            Log.d(tag, "Download ${downloadItemPart.filename} not found in dlmanager")
            downloadItem.downloadItemParts.remove(downloadItemPart)
          }
        }
      }
    }
  }
}
