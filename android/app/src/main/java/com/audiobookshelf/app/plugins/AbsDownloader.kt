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
            Log.d(tag, "Download ${downloadItemPart.filename} bytes $totalBytes | bytes dled $bytesDownloadedSoFar | downloadStatus $downloadStatus")

            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
              Log.d(tag, "Download ${downloadItemPart.filename} Done")
//              downloadItem.downloadItemParts.remove(downloadItemPart)
              downloadItemPart.completed = true
            } else if (downloadStatus == DownloadManager.STATUS_FAILED) {
              Log.d(tag, "Download ${downloadItemPart.filename} Failed")
              downloadItem.downloadItemParts.remove(downloadItemPart)
//              downloadItemPart.completed = true
            } else {
              //update progress
              val percentProgress = if (totalBytes > 0) ((bytesDownloadedSoFar * 100L) / totalBytes) else 0
              Log.d(tag, "${downloadItemPart.filename} Progress = $percentProgress%")
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
//
//  @PluginMethod
//  fun download(call: PluginCall) {
//    var audiobookId = call.data.getString("audiobookId", "audiobook").toString()
//    var url = call.data.getString("downloadUrl", "unknown").toString()
//    var coverDownloadUrl = call.data.getString("coverDownloadUrl", "").toString()
//    var title = call.data.getString("title", "Audiobook").toString()
//    var filename = call.data.getString("filename", "audiobook.mp3").toString()
//    var coverFilename = call.data.getString("coverFilename", "cover.png").toString()
//    var downloadFolderUrl = call.data.getString("downloadFolderUrl", "").toString()
//    var folder = DocumentFileCompat.fromUri(context, Uri.parse(downloadFolderUrl))!!
//    Log.d(tag, "Called download: $url | Folder: ${folder.name} | $downloadFolderUrl")
//
//    var dlfilename = audiobookId + "." + File(filename).extension
//    var coverdlfilename = audiobookId + "." + File(coverFilename).extension
//    Log.d(tag, "DL Filename $dlfilename | Cover DL Filename $coverdlfilename")
//
//    var canWriteToFolder = folder.canWrite()
//    if (!canWriteToFolder) {
//      Log.e(tag, "Error Cannot Write to Folder ${folder.baseName}")
//      val ret = JSObject()
//      ret.put("error", "Cannot write to ${folder.baseName}")
//      call.resolve(ret)
//      return
//    }
//
//    var dlRequest = DownloadManager.Request(Uri.parse(url))
//    dlRequest.setTitle("Ab: $title")
//    dlRequest.setDescription("Downloading to ${folder.name}")
//    dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
//    dlRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, dlfilename)
//
//    var audiobookDownloadId = downloadManager.enqueue(dlRequest)
//    var coverDownloadId:Long? = null
//
//    if (coverDownloadUrl != "") {
//      var coverDlRequest = DownloadManager.Request(Uri.parse(coverDownloadUrl))
//      coverDlRequest.setTitle("Cover: $title")
//      coverDlRequest.setDescription("Downloading to ${folder.name}")
//      coverDlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)
//      coverDlRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, coverdlfilename)
//      coverDownloadId = downloadManager.enqueue(coverDlRequest)
//    }
//
//    var progressReceiver : (id:Long, prog: Long) -> Unit = { id:Long, prog: Long ->
//      if (id == audiobookDownloadId) {
//        var jsobj = JSObject()
//        jsobj.put("audiobookId", audiobookId)
//        jsobj.put("progress", prog)
//        notifyListeners("onDownloadProgress", jsobj)
//      }
//    }
//
//    var coverDocFile:DocumentFile? = null
//
//    var doneReceiver : (id:Long, success: Boolean) -> Unit = { id:Long, success: Boolean ->
//      Log.d(tag, "RECEIVER DONE $id, SUCCES? $success")
//      var docfile:DocumentFile? = null
//
//      // Download was complete, now find downloaded file
//      if (id == coverDownloadId) {
//        docfile = DocumentFileCompat.fromPublicFolder(context, PublicDirectory.DOWNLOADS, coverdlfilename)
//        Log.d(tag, "Move Cover File ${docfile?.name}")
//
//        // For unknown reason, Android 10 test was using the title set in "setTitle" for the dl manager as the filename
//        //  check if this was the case
//        if (docfile?.name == null) {
//          docfile = DocumentFileCompat.fromPublicFolder(context, PublicDirectory.DOWNLOADS, "Cover: $title")
//          Log.d(tag, "Cover File name attempt 2 ${docfile?.name}")
//        }
//      } else if (id == audiobookDownloadId) {
//        docfile = DocumentFileCompat.fromPublicFolder(context, PublicDirectory.DOWNLOADS, dlfilename)
//        Log.d(tag, "Move Audiobook File ${docfile?.name}")
//
//        if (docfile?.name == null) {
//          docfile = DocumentFileCompat.fromPublicFolder(context, PublicDirectory.DOWNLOADS, "Ab: $title")
//          Log.d(tag, "File name attempt 2 ${docfile?.name}")
//        }
//      }
//
//      // Callback for moving the downloaded file
//      var callback = object : FileCallback() {
//        override fun onPrepare() {
//          Log.d(tag, "PREPARING MOVE FILE")
//        }
//        override fun onFailed(errorCode:ErrorCode) {
//          Log.e(tag, "FAILED MOVE FILE $errorCode")
//
//          docfile?.delete()
//          coverDocFile?.delete()
//
//          if (id == audiobookDownloadId) {
//            var jsobj = JSObject()
//            jsobj.put("audiobookId", audiobookId)
//            jsobj.put("error", "Move failed")
//            notifyListeners("onDownloadFailed", jsobj)
//          }
//        }
//        override fun onCompleted(result:Any) {
//          var resultDocFile = result as DocumentFile
//          var simplePath = resultDocFile.getSimplePath(context)
//          var storageId = resultDocFile.getStorageId(context)
//          var size = resultDocFile.length()
//          Log.d(tag, "Finished Moving File, NAME: ${resultDocFile.name} | URI:${resultDocFile.uri} | AbsolutePath:${resultDocFile.getAbsolutePath(context)} | $storageId | SimplePath: $simplePath")
//
//          var abFolder = folder.findFolder(title)
//          var jsobj = JSObject()
//          jsobj.put("audiobookId", audiobookId)
//          jsobj.put("downloadId", id)
//          jsobj.put("storageId", storageId)
//          jsobj.put("storageType", resultDocFile.getStorageType(context))
//          jsobj.put("folderUrl", abFolder?.uri)
//          jsobj.put("folderName", abFolder?.name)
//          jsobj.put("downloadFolderUrl", downloadFolderUrl)
//          jsobj.put("contentUrl", resultDocFile.uri)
//          jsobj.put("basePath", resultDocFile.getBasePath(context))
//          jsobj.put("filename", filename)
//          jsobj.put("simplePath", simplePath)
//          jsobj.put("size", size)
//
//          if (resultDocFile.name == filename) {
//            Log.d(tag, "Audiobook Finishing Moving")
//          } else if (resultDocFile.name == coverFilename) {
//            coverDocFile = docfile
//            Log.d(tag, "Audiobook Cover Finished Moving")
//            jsobj.put("isCover", true)
//          }
//          notifyListeners("onDownloadComplete", jsobj)
//        }
//      }
//
//      // After file is downloaded, move the files into an audiobook directory inside the user selected folder
//        if (id == coverDownloadId) {
//          docfile?.moveFileTo(context, folder, FileDescription(coverFilename, title, MimeType.IMAGE), callback)
//        } else if (id == audiobookDownloadId) {
//          docfile?.moveFileTo(context, folder, FileDescription(filename, title, MimeType.AUDIO), callback)
//        }
//    }
//
//    var progressUpdater = DownloadProgressUpdater(downloadManager, audiobookDownloadId, progressReceiver, doneReceiver)
//    progressUpdater.run()
//    if (coverDownloadId != null) {
//      var coverProgressUpdater = DownloadProgressUpdater(downloadManager, coverDownloadId, progressReceiver, doneReceiver)
//      coverProgressUpdater.run()
//    }
//
//    val ret = JSObject()
//    ret.put("audiobookDownloadId", audiobookDownloadId)
//    ret.put("coverDownloadId", coverDownloadId)
//    call.resolve(ret)
//  }
//
//
//internal class DownloadProgressUpdater(private val manager: DownloadManager, private val downloadId: Long, private var receiver: (Long, Long) -> Unit, private var doneReceiver: (Long, Boolean) -> Unit) : Thread() {
//    private val query: DownloadManager.Query = DownloadManager.Query()
//    private var totalBytes: Int = 0
//    private var TAG = "DownloadProgressUpdater"
//
//    init {
//      query.setFilterById(this.downloadId)
//    }
//
//    override fun run() {
//      Log.d(TAG, "RUN FOR ID $downloadId")
//      var keepRunning = true
//      var increment = 0
//      while (keepRunning) {
//        Thread.sleep(500)
//        increment++
//
//        if (increment % 4 == 0) {
//          Log.d(TAG, "Loop $increment : $downloadId")
//        }
//
//        manager.query(query).use {
//          if (it.moveToFirst()) {
//            //get total bytes of the file
//            if (totalBytes <= 0) {
//              totalBytes = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
//              if (totalBytes <= 0) {
//                Log.e(TAG, "Download Is 0 Bytes $downloadId")
//                doneReceiver(downloadId, false)
//                keepRunning = false
//                this.interrupt()
//                return
//              }
//            }
//
//            val downloadStatus = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
//            val bytesDownloadedSoFar = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
//
//            if (increment % 4 == 0) {
//              Log.d(TAG, "BYTES $increment : $downloadId : $bytesDownloadedSoFar : TOTAL: $totalBytes")
//            }
//
//            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL || downloadStatus == DownloadManager.STATUS_FAILED) {
//              if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
//                doneReceiver(downloadId, true)
//              } else {
//                doneReceiver(downloadId, false)
//              }
//              keepRunning = false
//              this.interrupt()
//            } else {
//              //update progress
//              val percentProgress = ((bytesDownloadedSoFar * 100L) / totalBytes)
//              receiver(downloadId, percentProgress)
//            }
//          } else {
//            Log.e(TAG, "NOT FOUND IN QUERY")
//            keepRunning = false
//          }
//        }
//      }
//    }
//  }
//}
