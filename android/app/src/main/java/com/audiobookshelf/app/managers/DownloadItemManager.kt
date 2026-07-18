package com.audiobookshelf.app.managers

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
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Manages download items and their parts. */
class DownloadItemManager(
        private var folderScanner: FolderScanner,
        var mainActivity: MainActivity,
        private var clientEventEmitter: DownloadEventEmitter
) {
  val tag = "DownloadItemManager"
  private val maxSimultaneousDownloads = 3
  private var jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

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

      if (nextDownloadItemParts.isNotEmpty()) {
        processDownloadItemParts(nextDownloadItemParts)
      }

      if (currentDownloadItemParts.size >= maxSimultaneousDownloads) {
        break
      }
    }

    if (currentDownloadItemParts.isNotEmpty()) startWatchingDownloads()
  }

  /** Processes the download item parts. */
  private fun processDownloadItemParts(nextDownloadItemParts: List<DownloadItemPart>) {
    nextDownloadItemParts.forEach {
      if (it.isInternalStorage) {
        startInternalDownload(it)
      } else {
        startExternalDownload(it)
      }
    }
  }

  /** Starts an internal download, writing directly to its final destination. */
  private fun startInternalDownload(downloadItemPart: DownloadItemPart) {
    startFileDownload(downloadItemPart, downloadItemPart.finalDestinationPath)
  }

  /**
   * Starts an external (custom SAF folder) download to a cache path, later moved into place by
   * moveDownloadedFile(). Avoids the system DownloadManager: on API 29+ its destination must be
   * app-owned or the top-level Downloads dir, which a custom folder is neither.
   * See https://developer.android.com/privacy-and-security/risks/unsafe-download-manager
   */
  private fun startExternalDownload(downloadItemPart: DownloadItemPart) {
    startFileDownload(downloadItemPart, downloadItemPart.destinationPath)
  }

  /** Downloads a download item part's contents to [destinationPath] via OkHttp/FileOutputStream. */
  private fun startFileDownload(downloadItemPart: DownloadItemPart, destinationPath: String) {
    val file = File(destinationPath)
    file.parentFile?.mkdirs()

    val fileOutputStream = FileOutputStream(destinationPath)
    val internalProgressCallback =
            object : InternalProgressCallback {
              override fun onProgress(totalBytesWritten: Long, progress: Long) {
                downloadItemPart.bytesDownloaded = totalBytesWritten
                downloadItemPart.progress = progress
              }

              override fun onComplete(failed: Boolean) {
                downloadItemPart.failed = failed
                downloadItemPart.completed = true
              }
            }

    Log.d(tag, "Start download to destination path $destinationPath from ${downloadItemPart.serverUrl}")
    InternalDownloadManager(fileOutputStream, internalProgressCallback)
            .download(downloadItemPart.serverUrl)
    downloadItemPart.downloadId = 1
    currentDownloadItemParts.add(downloadItemPart)
  }

  /** Starts watching the downloads. */
  private fun startWatchingDownloads() {
    if (isDownloading) return // Already watching

    GlobalScope.launch(Dispatchers.IO) {
      Log.d(tag, "Starting watching downloads")
      isDownloading = true

      while (currentDownloadItemParts.isNotEmpty()) {
        val itemParts = currentDownloadItemParts.filter { !it.isMoving }
        for (downloadItemPart in itemParts) {
          if (downloadItemPart.isInternalStorage) {
            handleInternalDownloadPart(downloadItemPart)
          } else {
            handleExternalDownloadPart(downloadItemPart)
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

  /** Finds the parent download item for [downloadItemPart], if it's still queued. */
  private fun findDownloadItem(downloadItemPart: DownloadItemPart): DownloadItem? =
          downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }

  /** Finishes [downloadItem] if all its parts are done, and drops [downloadItemPart] from the active list. */
  private fun finishDownloadItemPart(downloadItem: DownloadItem?, downloadItemPart: DownloadItemPart) {
    downloadItem?.let { checkDownloadItemFinished(it) }
    currentDownloadItemParts.remove(downloadItemPart)
  }

  /** Handles an internal download part. */
  private fun handleInternalDownloadPart(downloadItemPart: DownloadItemPart) {
    clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

    if (downloadItemPart.completed) {
      finishDownloadItemPart(findDownloadItem(downloadItemPart), downloadItemPart)
    }
  }

  /** Handles an external download part: moves it into its SAF folder once downloaded. */
  private fun handleExternalDownloadPart(downloadItemPart: DownloadItemPart) {
    clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

    if (!downloadItemPart.completed) return

    val downloadItem = findDownloadItem(downloadItemPart)
    when {
      downloadItem == null -> {
        Log.e(tag, "Download item part finished but download item not found ${downloadItemPart.filename}")
        currentDownloadItemParts.remove(downloadItemPart)
      }
      downloadItemPart.failed -> finishDownloadItemPart(downloadItem, downloadItemPart)
      else -> moveDownloadedFile(downloadItem, downloadItemPart)
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

      GlobalScope.launch(Dispatchers.IO) {
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
