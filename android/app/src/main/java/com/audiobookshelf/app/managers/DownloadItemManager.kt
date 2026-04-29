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
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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

    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    checkUpdateDownloadQueue()
  }

  /**
   * Re-hydrate the in-memory queue from persisted state and resume any in-flight downloads.
   * Idempotent: safe to call multiple times. Bails out if no server is connected yet, since any
   * internal-storage part needs a fresh signed URL to re-enqueue.
   */
  fun restoreQueue() {
    if (!DeviceManager.isConnectedToServer) {
      Log.i(tag, "restoreQueue skipped: no server connection")
      return
    }

    val persistedItems = DeviceManager.dbManager.getDownloadItems()
    if (persistedItems.isEmpty()) return

    Log.i(tag, "Restoring ${persistedItems.size} persisted download item(s)")
    for (downloadItem in persistedItems) {
      try {
        restorePersistedItem(downloadItem)
      } catch (e: Exception) {
        Log.e(tag, "Dropping unrestorable persisted download ${downloadItem.id}", e)
        DeviceManager.dbManager.removeDownloadItem(downloadItem.id)
      }
    }
    checkUpdateDownloadQueue()
  }

  private fun restorePersistedItem(downloadItem: DownloadItem) {
    if (downloadItemQueue.any { it.id == downloadItem.id }) return

    // Records written by older builds may be missing string fields needed for restoration.
    if (downloadItem.downloadItemParts.any {
              it.finalDestinationPath.isEmpty() || it.destinationPath.isEmpty()
            }
    ) {
      throw IllegalStateException("Persisted item ${downloadItem.id} has parts with missing paths")
    }

    // Self-heal: records persisted by app versions before per-part progress was saved
    // (i.e. anyone upgrading from a prior release) report every part as fresh. For
    // internal-storage parts the file on disk is the ground truth - if it's there at the
    // expected size, mark it completed regardless of what the record claims.
    downloadItem.downloadItemParts.forEach { part ->
      if (!part.completed && part.isInternalStorage) {
        val file = File(part.finalDestinationPath)
        if (file.exists() &&
                        file.length() > 0 &&
                        (part.fileSize <= 0 || file.length() == part.fileSize)
        ) {
          part.completed = true
          part.failed = false
          part.bytesDownloaded = file.length()
          part.progress = 100
        }
      }
    }

    downloadItem.downloadItemParts.forEach { part ->
      when (DownloadRestoreLogic.classifyPart(
                      isInternalStorage = part.isInternalStorage,
                      downloadId = part.downloadId,
                      completed = part.completed,
                      moved = part.moved,
                      failed = part.failed
              )
      ) {
        DownloadRestoreLogic.PartRestoreAction.AlreadyDone -> {
          /* keep flagged done */
        }
        DownloadRestoreLogic.PartRestoreAction.ResetAndReenqueue -> {
          part.downloadId = null
          part.bytesDownloaded = 0
          part.progress = 0
          part.completed = false
          part.failed = false
          part.isMoving = false
          File(part.finalDestinationPath).takeIf { it.exists() }?.delete()
        }
        DownloadRestoreLogic.PartRestoreAction.ResumeExternal -> {
          part.isMoving = false // stale flag if app died mid-move; watcher will retry
          currentDownloadItemParts.add(part)
        }
      }
    }

    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)

    // Self-heal may have made the whole item done; run the normal completion path to scan
    // and clean up the persisted record.
    if (downloadItem.isDownloadFinished) checkDownloadItemFinished(downloadItem)
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

  /** Starts an internal download. */
  private fun startInternalDownload(downloadItemPart: DownloadItemPart) {
    val file = File(downloadItemPart.finalDestinationPath)
    file.parentFile?.mkdirs()

    val fileOutputStream = FileOutputStream(downloadItemPart.finalDestinationPath)
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

    Log.d(
            tag,
            "Start internal download to destination path ${downloadItemPart.finalDestinationPath} from ${downloadItemPart.serverUrl}"
    )
    InternalDownloadManager(fileOutputStream, internalProgressCallback)
            .download(downloadItemPart.serverUrl)
    downloadItemPart.downloadId = 1
    currentDownloadItemParts.add(downloadItemPart)
    persistParentOf(downloadItemPart)
  }

  /** Starts an external download. */
  private fun startExternalDownload(downloadItemPart: DownloadItemPart) {
    val dlRequest = downloadItemPart.getDownloadRequest()
    val downloadId = downloadManager.enqueue(dlRequest)
    downloadItemPart.downloadId = downloadId
    Log.d(tag, "checkUpdateDownloadQueue: Starting download item part, downloadId=$downloadId")
    currentDownloadItemParts.add(downloadItemPart)
    persistParentOf(downloadItemPart)
  }

  /**
   * Persist the parent DownloadItem so transient flags on its parts (downloadId, completed, moved,
   * failed) survive process death and can be re-read by [restoreQueue].
   */
  private fun persistParentOf(downloadItemPart: DownloadItemPart) {
    downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }?.let {
      DeviceManager.dbManager.saveDownloadItem(it)
    }
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

  /** Handles an internal download part. */
  private fun handleInternalDownloadPart(downloadItemPart: DownloadItemPart) {
    clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

    if (downloadItemPart.completed) {
      persistParentOf(downloadItemPart)
      val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
      downloadItem?.let { checkDownloadItemFinished(it) }
      currentDownloadItemParts.remove(downloadItemPart)
    }
  }

  /** Handles an external download part. */
  private fun handleExternalDownloadPart(downloadItemPart: DownloadItemPart) {
    val downloadCheckStatus = checkDownloadItemPart(downloadItemPart)
    clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

    // Will move to final destination, remove current item parts, and check if download item is
    // finished
    handleDownloadItemPartCheck(downloadCheckStatus, downloadItemPart)
  }

  /** Checks the status of a download item part. */
  private fun checkDownloadItemPart(downloadItemPart: DownloadItemPart): DownloadCheckStatus {
    val downloadId = downloadItemPart.downloadId ?: return DownloadCheckStatus.Failed

    val query = DownloadManager.Query().setFilterById(downloadId)
    downloadManager.query(query).use {
      if (it.moveToFirst()) {
        val bytesColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val statusColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val bytesDownloadedColumnIndex =
                it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)

        val totalBytes = if (bytesColumnIndex >= 0) it.getInt(bytesColumnIndex) else 0
        val downloadStatus = if (statusColumnIndex >= 0) it.getInt(statusColumnIndex) else 0
        val bytesDownloadedSoFar =
                if (bytesDownloadedColumnIndex >= 0) it.getLong(bytesDownloadedColumnIndex) else 0
        Log.d(
                tag,
                "checkDownloads Download ${downloadItemPart.filename} bytes $totalBytes | bytes dled $bytesDownloadedSoFar | downloadStatus $downloadStatus"
        )

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
            val percentProgress =
                    if (totalBytes > 0) ((bytesDownloadedSoFar * 100L) / totalBytes) else 0
            Log.d(
                    tag,
                    "checkDownloads Download ${downloadItemPart.filename} Progress = $percentProgress%"
            )
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

  /** Handles the result of a download item part check. */
  private fun handleDownloadItemPartCheck(
          downloadCheckStatus: DownloadCheckStatus,
          downloadItemPart: DownloadItemPart
  ) {
    val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
    if (downloadItem == null) {
      Log.e(
              tag,
              "Download item part finished but download item not found ${downloadItemPart.filename}"
      )
      currentDownloadItemParts.remove(downloadItemPart)
    } else if (downloadCheckStatus == DownloadCheckStatus.Successful) {
      moveDownloadedFile(downloadItem, downloadItemPart)
    } else if (downloadCheckStatus != DownloadCheckStatus.InProgress) {
      checkDownloadItemFinished(downloadItem)
      currentDownloadItemParts.remove(downloadItemPart)
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
                persistParentOf(downloadItemPart)
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
                persistParentOf(downloadItemPart)
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
