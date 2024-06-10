package com.audiobookshelf.app.managers

import android.app.DownloadManager
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.callback.FileCallback
import com.anggrayudi.storage.file.DocumentFileCompat
import com.anggrayudi.storage.file.MimeType
import com.anggrayudi.storage.file.getAbsolutePath
import com.anggrayudi.storage.file.moveFileTo
import com.anggrayudi.storage.media.FileDescription
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

class DownloadItemManager(var downloadManager:DownloadManager, private var folderScanner: FolderScanner, var mainActivity: MainActivity, private var clientEventEmitter:DownloadEventEmitter) {
  val tag = "DownloadItemManager"
  private val maxSimultaneousDownloads = 3
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  enum class DownloadCheckStatus {
    InProgress,
    Successful,
    Failed
  }

  var downloadItemQueue: MutableList<DownloadItem> = mutableListOf() // All pending and downloading items
  var currentDownloadItemParts: MutableList<DownloadItemPart> = mutableListOf() // Item parts currently being downloaded

  interface DownloadEventEmitter {
    fun onDownloadItem(downloadItem:DownloadItem)
    fun onDownloadItemPartUpdate(downloadItemPart:DownloadItemPart)
    fun onDownloadItemComplete(jsobj:JSObject)
  }

  interface InternalProgressCallback {
    fun onProgress(totalBytesWritten:Long, progress: Long)
    fun onComplete(failed: Boolean)
  }

  companion object {
    var isDownloading:Boolean = false
  }

  fun addDownloadItem(downloadItem:DownloadItem) {
    DeviceManager.dbManager.saveDownloadItem(downloadItem)
    Log.i(tag, "Add download item ${downloadItem.media.metadata.title}")

    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    checkUpdateDownloadQueue()
  }

  private fun checkUpdateDownloadQueue() {
    for (downloadItem in downloadItemQueue) {
      val numPartsToGet = maxSimultaneousDownloads - currentDownloadItemParts.size
      val nextDownloadItemParts = downloadItem.getNextDownloadItemParts(numPartsToGet)
      Log.d(tag, "checkUpdateDownloadQueue: numPartsToGet=$numPartsToGet, nextDownloadItemParts=${nextDownloadItemParts.size}")

      if (nextDownloadItemParts.size > 0) {
        nextDownloadItemParts.forEach {
          if (it.isInternalStorage) {
            val file = File(it.finalDestinationPath)
            file.parentFile?.mkdirs()

            val internalProgressCallback = (object : InternalProgressCallback {
              override fun onProgress(totalBytesWritten:Long, progress: Long) {
                it.bytesDownloaded = totalBytesWritten
                it.progress = progress
              }
              override fun onComplete(failed:Boolean) {
                it.failed = failed
                it.completed = true
              }
            })

            Log.d(tag, "Start internal download to destination path ${it.finalDestinationPath} from ${it.serverUrl}")
            InternalDownloadManager(file, internalProgressCallback).download(it.serverUrl)
            it.downloadId = 1
            currentDownloadItemParts.add(it)
          } else {
            val dlRequest = it.getDownloadRequest()
            val downloadId = downloadManager.enqueue(dlRequest)
            it.downloadId = downloadId
            Log.d(tag, "checkUpdateDownloadQueue: Starting download item part, downloadId=$downloadId")
            currentDownloadItemParts.add(it)
          }
        }
      }

      if (currentDownloadItemParts.size >= maxSimultaneousDownloads) {
        break
      }
    }

    if (currentDownloadItemParts.size > 0) startWatchingDownloads()
  }

  private fun startWatchingDownloads() {
    if (isDownloading) return // Already watching

    GlobalScope.launch(Dispatchers.IO) {
      Log.d(tag, "Starting watching downloads")
      isDownloading = true

      while (currentDownloadItemParts.size > 0) {
        val itemParts = currentDownloadItemParts.filter { !it.isMoving }.map { it }
        for (downloadItemPart in itemParts) {
          if (downloadItemPart.isInternalStorage) {
            clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

            if (downloadItemPart.completed) {
              val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
              downloadItem?.let {
                checkDownloadItemFinished(it)
              }
              currentDownloadItemParts.remove(downloadItemPart)
            }
          } else {
            val downloadCheckStatus = checkDownloadItemPart(downloadItemPart)
            clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

            // Will move to final destination, remove current item parts, and check if download item is finished
            handleDownloadItemPartCheck(downloadCheckStatus, downloadItemPart)
          }
        }

        delay(500)

        if (currentDownloadItemParts.size < maxSimultaneousDownloads) {
          checkUpdateDownloadQueue()
        }
      }

      Log.d(tag, "Finished watching downloads")
      isDownloading = false
    }
  }

  private fun checkDownloadItemPart(downloadItemPart:DownloadItemPart):DownloadCheckStatus {
    val downloadId = downloadItemPart.downloadId ?: return DownloadCheckStatus.Failed

    val query = DownloadManager.Query().setFilterById(downloadId)
    downloadManager.query(query).use {
      if (it.moveToFirst()) {
        val bytesColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val statusColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val bytesDownloadedColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)

        val totalBytes = if (bytesColumnIndex >= 0) it.getInt(bytesColumnIndex) else 0
        val downloadStatus = if (statusColumnIndex >= 0) it.getInt(statusColumnIndex) else 0
        val bytesDownloadedSoFar = if (bytesDownloadedColumnIndex >= 0) it.getLong(bytesDownloadedColumnIndex) else 0
        Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} bytes $totalBytes | bytes dled $bytesDownloadedSoFar | downloadStatus $downloadStatus")

        return when (downloadStatus) {
          DownloadManager.STATUS_SUCCESSFUL -> {
            Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Successful")
            downloadItemPart.completed = true
            downloadItemPart.progress = 1
            downloadItemPart.bytesDownloaded = bytesDownloadedSoFar

            DownloadCheckStatus.Successful
          }
          DownloadManager.STATUS_FAILED -> {
            Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Failed")
            downloadItemPart.completed = true
            downloadItemPart.failed = true

            DownloadCheckStatus.Failed
          }
          else -> {
            val percentProgress = if (totalBytes > 0) ((bytesDownloadedSoFar * 100L) / totalBytes) else 0
            Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Progress = $percentProgress%")
            downloadItemPart.progress = percentProgress
            downloadItemPart.bytesDownloaded = bytesDownloadedSoFar

            DownloadCheckStatus.InProgress
          }
        }
      } else {
        Log.d(tag, "Download ${downloadItemPart.filename} not found in dlmanager")
        downloadItemPart.completed = true
        downloadItemPart.failed = true
        return DownloadCheckStatus.Failed
      }
    }
  }

  private fun handleDownloadItemPartCheck(downloadCheckStatus:DownloadCheckStatus, downloadItemPart:DownloadItemPart) {
    val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
    if (downloadItem == null) {
      Log.e(tag, "Download item part finished but download item not found ${downloadItemPart.filename}")
      currentDownloadItemParts.remove(downloadItemPart)
    } else if (downloadCheckStatus == DownloadCheckStatus.Successful) {
      val file = DocumentFileCompat.fromUri(mainActivity, downloadItemPart.destinationUri)
      Log.d(tag, "DOWNLOAD: DESTINATION URI ${downloadItemPart.destinationUri}")

      val fcb = object : FileCallback() {
        override fun onPrepare() {
          Log.d(tag, "DOWNLOAD: PREPARING MOVE FILE")
        }
        override fun onFailed(errorCode: ErrorCode) {
          Log.e(tag, "DOWNLOAD: FAILED TO MOVE FILE $errorCode")
          downloadItemPart.failed = true
          downloadItemPart.isMoving = false
          file?.delete()
          checkDownloadItemFinished(downloadItem)
          currentDownloadItemParts.remove(downloadItemPart)
        }
        override fun onCompleted(result:Any) {
          Log.d(tag, "DOWNLOAD: FILE MOVE COMPLETED")
          val resultDocFile = result as DocumentFile
          Log.d(tag, "DOWNLOAD: COMPLETED FILE INFO (name=${resultDocFile.name}) ${resultDocFile.getAbsolutePath(mainActivity)}")

          // Rename to fix appended .mp3 on m4b/m4a files
          //  REF: https://github.com/anggrayudi/SimpleStorage/issues/94
          val docNameLowerCase = resultDocFile.name?.lowercase(Locale.getDefault()) ?: ""
          if (docNameLowerCase.endsWith(".m4b.mp3")|| docNameLowerCase.endsWith(".m4a.mp3")) {
            resultDocFile.renameTo(downloadItemPart.filename)
          }

          downloadItemPart.moved = true
          downloadItemPart.isMoving = false
          checkDownloadItemFinished(downloadItem)
          currentDownloadItemParts.remove(downloadItemPart)
        }
      }

      val localFolderFile = DocumentFileCompat.fromUri(mainActivity, Uri.parse(downloadItemPart.localFolderUrl))
      if (localFolderFile == null) {
        // fAILED
        downloadItemPart.failed = true
        Log.e(tag, "Local Folder File from uri is null")
        checkDownloadItemFinished(downloadItem)
        currentDownloadItemParts.remove(downloadItemPart)
      } else {
        downloadItemPart.isMoving = true
        val mimetype = if (downloadItemPart.audioTrack != null) MimeType.AUDIO else MimeType.IMAGE
        val fileDescription = FileDescription(downloadItemPart.filename, downloadItemPart.finalDestinationSubfolder, mimetype)
        file?.moveFileTo(mainActivity, localFolderFile, fileDescription, fcb)
      }

    } else if (downloadCheckStatus != DownloadCheckStatus.InProgress) {
      checkDownloadItemFinished(downloadItem)
      currentDownloadItemParts.remove(downloadItemPart)
    }
  }

  private fun checkDownloadItemFinished(downloadItem:DownloadItem) {
    if (downloadItem.isDownloadFinished) {
      Log.i(tag, "Download Item finished ${downloadItem.media.metadata.title}")

      GlobalScope.launch(Dispatchers.IO) {
        folderScanner.scanDownloadItem(downloadItem) { downloadItemScanResult ->
          Log.d(tag, "Item download complete ${downloadItem.itemTitle} | local library item id: ${downloadItemScanResult?.localLibraryItem?.id}")

          val jsobj = JSObject()
          jsobj.put("libraryItemId", downloadItem.id)
          jsobj.put("localFolderId", downloadItem.localFolder.id)

          downloadItemScanResult?.localLibraryItem?.let { localLibraryItem ->
            jsobj.put("localLibraryItem", JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
          }
          downloadItemScanResult?.localMediaProgress?.let { localMediaProgress ->
            jsobj.put("localMediaProgress", JSObject(jacksonMapper.writeValueAsString(localMediaProgress)))
          }

          launch(Dispatchers.Main) {
            clientEventEmitter.onDownloadItemComplete(jsobj)
            downloadItemQueue.remove(downloadItem)
            DeviceManager.dbManager.removeDownloadItem(downloadItem.id)
          }
        }
      }
    }
  }
}
