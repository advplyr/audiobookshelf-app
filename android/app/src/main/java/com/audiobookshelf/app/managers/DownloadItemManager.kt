package com.audiobookshelf.app.managers

import android.app.DownloadManager
import android.net.Uri
import android.os.StatFs
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
import com.audiobookshelf.app.plugins.AbsLogger
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import java.io.File
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Manages download items and their parts. */
class DownloadItemManager(
        var downloadManager: DownloadManager,
        private var folderScanner: FolderScanner,
        var mainActivity: MainActivity,
        private var clientEventEmitter: DownloadEventEmitter
) {
  val tag = "DownloadItemManager"
  private val maxSimultaneousDownloads = 3
  private var jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  enum class DownloadCheckStatus {
    InProgress,
    Successful,
    Failed
  }

  var downloadItemQueue: MutableList<DownloadItem> =
          mutableListOf() // All pending and downloading items
  var currentDownloadItemParts: MutableList<DownloadItemPart> =
          mutableListOf() // Item parts currently being downloaded

  interface DownloadEventEmitter {
    fun onDownloadItem(downloadItem: DownloadItem)
    fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart)
    fun onDownloadItemComplete(jsobj: JSObject)
  }

  interface InternalProgressCallback {
    fun onProgress(totalBytesWritten: Long, progress: Long)
    fun onComplete(failed: Boolean)
  }

  companion object {
    var isDownloading: Boolean = false
  }

  /** Adds a download item to the queue and starts processing the queue. */
  fun addDownloadItem(downloadItem: DownloadItem) {
    DeviceManager.dbManager.saveDownloadItem(downloadItem)
    Log.i(tag, "Add download item ${downloadItem.media.metadata.title}")
    AbsLogger.info(tag, "Adding ${downloadItem.media.metadata.title} to download queue")

    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    checkUpdateDownloadQueue()
  }

  /** Checks and updates the download queue. */
  private fun checkUpdateDownloadQueue() {
    for (downloadItem in downloadItemQueue) {
      val numPartsToGet = maxSimultaneousDownloads - currentDownloadItemParts.size
      val nextDownloadItemParts = downloadItem.getNextDownloadItemParts(numPartsToGet)
      Log.d(
              tag,
              "checkUpdateDownloadQueue: numPartsToGet=$numPartsToGet, nextDownloadItemParts=${nextDownloadItemParts.size}"
      )

      nextDownloadItemParts.forEach { startInternalDownload(it) }

      if (currentDownloadItemParts.size >= maxSimultaneousDownloads) {
        break
      }
    }

    if (currentDownloadItemParts.isNotEmpty()) startWatchingDownloads()
  }

  /** Starts an internal download. */
  private fun startInternalDownload(downloadItemPart: DownloadItemPart) {
    // Create internal download location at temp directory location
    Log.d(tag, "Creating internal download location at ${downloadItemPart.destinationUri.path}")
    val file = File(downloadItemPart.destinationUri.path ?: "")
    file.parentFile?.mkdirs()

    // Check if enough internal storage is available for downloading
    val statFs = StatFs(file.parentFile?.absolutePath ?: mainActivity.filesDir.absolutePath)
    val availableBytes = statFs.availableBytes
    val fileSize = downloadItemPart.fileSize

    Log.d(
            tag,
            "Attempting to start ${downloadItemPart.filename}. Available: ${availableBytes / (1024)} KB, Required: ${fileSize / (1024)} KB"
    )

    // The margin is based on how many simultaneous downloads can be running at once
    if (availableBytes < fileSize * maxSimultaneousDownloads) {
      AbsLogger.error(
              tag,
              "Not enough internal storage for ${downloadItemPart.filename}. Available: ${availableBytes / (1024)} KB, Required: ${fileSize / (1024)} KB. Trying again..."
      )
      Log.w(tag, "Not enough internal storage for ${downloadItemPart.filename}, skipping for now")
      return
    }

    val internalProgressCallback =
            object : InternalProgressCallback {
              override fun onProgress(totalBytesWritten: Long, progress: Long) {
                // Store time progress was last changed to prevent stale downloads from getting
                // stuck in an infinite loop
                if (downloadItemPart.bytesDownloaded != totalBytesWritten) {
                  downloadItemPart.lastUpdateTime = System.currentTimeMillis()
                }
                downloadItemPart.bytesDownloaded = totalBytesWritten
                downloadItemPart.progress = progress
              }

              override fun onComplete(failed: Boolean) {
                downloadItemPart.failed = failed
                downloadItemPart.completed = true
              }
            }

    // Check if file already exists in shared storage
    if (!downloadItemPart.isInternalStorage) {
      val destinationFile =
              DocumentFileCompat.fromFullPath(mainActivity, downloadItemPart.finalDestinationPath)
      if (destinationFile != null && destinationFile.exists()) {
        Log.d(
                tag,
                "File already exists in shared storage at path ${downloadItemPart.finalDestinationPath}"
        )
        downloadItemPart.completed = true
        downloadItemPart.moved = true
        downloadItemPart.failed = false
        downloadItemPart.downloadId = 1
        currentDownloadItemParts.add(downloadItemPart)
        clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)
        return
      } else {
        Log.d(
                tag,
                "File does not exist in shared storage at path ${downloadItemPart.finalDestinationPath}"
        )
      }
    }

    Log.d(
            tag,
            "Start internal download to destination path ${downloadItemPart.finalDestinationPath} from ${downloadItemPart.serverUrl}"
    )
    InternalDownloadManager(mainActivity, downloadItemPart.destinationUri, internalProgressCallback)
            .download(downloadItemPart.serverUrl)
    downloadItemPart.downloadId = 1
    currentDownloadItemParts.add(downloadItemPart)
  }

  private val coroutineScope = CoroutineScope(Dispatchers.IO)

  /** Starts watching the downloads. */
  private fun startWatchingDownloads() {
    if (isDownloading) return // Already watching

    coroutineScope.launch(Dispatchers.IO) {
      Log.d(tag, "Starting watching downloads")
      isDownloading = true

      while (currentDownloadItemParts.isNotEmpty()) {
        val itemParts = currentDownloadItemParts.filter { !it.isMoving }
        for (downloadItemPart in itemParts) {
          handleInternalDownloadPart(downloadItemPart)
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

  /** Handles an internal download part. */
  private fun handleInternalDownloadPart(downloadItemPart: DownloadItemPart) {
    clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

    if (downloadItemPart.completed) {
      val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
      if (!downloadItemPart.isInternalStorage && !downloadItemPart.moved) {
        // After downloading, move the downloaded file to the final destination if it was
        // not already moved during a previous multipart download
        // After moving the file, checkDownloadItemFinished is called anyway
        downloadItem?.let { moveDownloadedFile(it, downloadItemPart) }
      } else {
        // Otherwise, just check if the full item is finished downloading
        downloadItem?.let { checkDownloadItemFinished(it) }
      }
      currentDownloadItemParts.remove(downloadItemPart)
    } else {
      // Check for stalled downloads
      val currentTimeMillis = System.currentTimeMillis()
      val lastUpdateTime = downloadItemPart.lastUpdateTime ?: currentTimeMillis
      val timeSinceLastUpdate = currentTimeMillis - lastUpdateTime
      val stallTimeoutMillis = 10 * 1000 // 10 seconds
      if (timeSinceLastUpdate > stallTimeoutMillis) {
        Log.w(
                tag,
                "Download stalled for ${downloadItemPart.filename}, removing from download queue."
        )
        downloadItemPart.failed = true
        clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)
        currentDownloadItemParts.remove(downloadItemPart)
      }
    }
  }

  /** Moves the downloaded file to its final destination. */
  private fun moveDownloadedFile(downloadItem: DownloadItem, downloadItemPart: DownloadItemPart) {
    val file = DocumentFileCompat.fromUri(mainActivity, downloadItemPart.destinationUri)
    Log.d(tag, "DOWNLOAD: DESTINATION URI ${downloadItemPart.destinationUri}")

    val fcb =
            object : FileCallback() {
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

              override fun onCompleted(result: Any) {
                Log.d(tag, "DOWNLOAD: FILE MOVE COMPLETED")
                val resultDocFile = result as DocumentFile
                Log.d(
                        tag,
                        "DOWNLOAD: COMPLETED FILE INFO (name=${resultDocFile.name}) ${resultDocFile.getAbsolutePath(mainActivity)}"
                )

                // Rename to fix appended .mp3 on m4b/m4a files
                //  REF: https://github.com/anggrayudi/SimpleStorage/issues/94
                val docNameLowerCase = resultDocFile.name?.lowercase(Locale.getDefault()) ?: ""
                if (docNameLowerCase.endsWith(".m4b.mp3") || docNameLowerCase.endsWith(".m4a.mp3")
                ) {
                  resultDocFile.renameTo(downloadItemPart.filename)
                }

                downloadItemPart.moved = true
                downloadItemPart.isMoving = false
                checkDownloadItemFinished(downloadItem)
                currentDownloadItemParts.remove(downloadItemPart)
              }
            }

    val localFolderFile =
            DocumentFileCompat.fromUri(mainActivity, Uri.parse(downloadItemPart.localFolderUrl))
    if (localFolderFile == null) {
      // Failed
      downloadItemPart.failed = true
      Log.e(tag, "Local Folder File from uri is null")
      checkDownloadItemFinished(downloadItem)
      currentDownloadItemParts.remove(downloadItemPart)
    } else {
      downloadItemPart.isMoving = true
      val mimetype = if (downloadItemPart.audioTrack != null) MimeType.AUDIO else MimeType.IMAGE
      val fileDescription =
              FileDescription(
                      downloadItemPart.filename,
                      downloadItemPart.finalDestinationSubfolder,
                      mimetype
              )
      file?.moveFileTo(mainActivity, localFolderFile, fileDescription, fcb)
    }
  }

  /** Checks if a download item is finished and processes it. */
  private fun checkDownloadItemFinished(downloadItem: DownloadItem) {
    if (downloadItem.isDownloadFinished) {
      Log.i(tag, "Download Item finished ${downloadItem.media.metadata.title}")

      coroutineScope.launch(Dispatchers.IO) {
        folderScanner.scanDownloadItem(downloadItem) { downloadItemScanResult ->
          Log.d(
                  tag,
                  "Item download complete ${downloadItem.itemTitle} | local library item id: ${downloadItemScanResult?.localLibraryItem?.id}"
          )

          val jsobj =
                  JSObject().apply {
                    put("libraryItemId", downloadItem.id)
                    put("localFolderId", downloadItem.localFolder.id)

                    downloadItemScanResult?.localLibraryItem?.let { localLibraryItem ->
                      put(
                              "localLibraryItem",
                              JSObject(jacksonMapper.writeValueAsString(localLibraryItem))
                      )
                    }
                    downloadItemScanResult?.localMediaProgress?.let { localMediaProgress ->
                      put(
                              "localMediaProgress",
                              JSObject(jacksonMapper.writeValueAsString(localMediaProgress))
                      )
                    }
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
