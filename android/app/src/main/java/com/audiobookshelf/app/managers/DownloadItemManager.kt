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
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.utils.DebugUtils
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manages download items and their parts. */
class DownloadItemManager(
        var downloadManager: DownloadManager,
        private var folderScanner: FolderScanner,
        var mainActivity: MainActivity,
        private var clientEventEmitter: DownloadEventEmitter
) {
  val tag = "DownloadItemManager"
  private var jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  /**
   * Tracks in-flight InternalDownloadManager instances keyed by DownloadItemPart.id.
   * Used to cancel active OkHttp calls when cancelAllDownloads() is invoked.
   */
  private val activeInternalDownloads: MutableMap<String, InternalDownloadManager> = mutableMapOf()

  /** Throttle progress-update UI events to at most once per 500ms per download part. */
  private val lastProgressEmitTime: MutableMap<String, Long> = mutableMapOf()
  private val progressEmitThrottleMs = 500L

  private fun getMaxSimultaneousDownloads(): Int {
    return DeviceManager.deviceData.deviceSettings?.maxSimultaneousDownloads ?: 1
  }

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
    @Volatile private var isDownloading: Boolean = false
    @Volatile private var isProcessingQueue: Boolean = false
    private val downloadingLock = Mutex()
    private val queueProcessingLock = Mutex()

    suspend fun setDownloading(downloading: Boolean) {
      downloadingLock.withLock { isDownloading = downloading }
    }

    suspend fun checkAndSetDownloading(): Boolean {
      return downloadingLock.withLock {
        if (isDownloading) {
          false // Already downloading
        } else {
          isDownloading = true
          true // Successfully set to downloading
        }
      }
    }

    suspend fun setProcessingQueue(processing: Boolean) {
      queueProcessingLock.withLock { isProcessingQueue = processing }
    }

    suspend fun checkAndSetProcessingQueue(): Boolean {
      return queueProcessingLock.withLock {
        if (isProcessingQueue) {
          false // Already processing
        } else {
          isProcessingQueue = true
          true // Successfully set to processing
        }
      }
    }
  }

  // Use a supervised scope instead of GlobalScope for better lifecycle management
  private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  /** Adds a download item to the queue and starts processing the queue. */
  fun addDownloadItem(downloadItem: DownloadItem) {
    // Skip files that were already fully downloaded in a previous attempt
    scanForExistingFiles(downloadItem)
    DeviceManager.dbManager.saveDownloadItem(downloadItem)
    Log.i(tag, "Add download item ${downloadItem.media.metadata.title}")

    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)

    // If all parts were already downloaded (pre-existing files), finish immediately
    if (downloadItem.isDownloadFinished) {
      Log.i(tag, "addDownloadItem: All parts already downloaded for ${downloadItem.itemTitle}, finishing immediately")
      checkDownloadItemFinished(downloadItem)
    } else {
      checkUpdateDownloadQueue()
    }
  }

  /** Checks and updates the download queue. */
  private fun checkUpdateDownloadQueue() {
    // Use coroutine to handle thread-safe queue processing
    downloadScope.launch {
      if (!checkAndSetProcessingQueue()) {
        AbsLogger.debug(
                tag,
                "checkUpdateDownloadQueue: Queue processing already in progress, skipping"
        )
        return@launch
      }

      try {
        val finishDebug = DebugUtils.logLongOperation(tag, "checkUpdateDownloadQueue", 50)
        DebugUtils.logMethodEntry(tag, "checkUpdateDownloadQueue")

        val startTime = System.currentTimeMillis()
        val threadName = Thread.currentThread().name
        AbsLogger.debug(
                tag,
                "checkUpdateDownloadQueue: Starting queue check [Thread: $threadName, Time: $startTime]"
        )
        AbsLogger.debug(
                tag,
                "checkUpdateDownloadQueue: Queue size=${downloadItemQueue.size}, Current downloads=${currentDownloadItemParts.size}"
        )

        // Check if server is connected before processing downloads
        if (!DeviceManager.isConnectedToServer || DeviceManager.serverAddress.isBlank()) {
          AbsLogger.debug(
                  tag,
                  "checkUpdateDownloadQueue: Server not connected or address blank, skipping downloads"
          )
          return@launch
        }

        for (downloadItem in downloadItemQueue) {
          val numPartsToGet = getMaxSimultaneousDownloads() - currentDownloadItemParts.size
          val nextDownloadItemParts = downloadItem.getNextDownloadItemParts(numPartsToGet)
          Log.d(
                  tag,
                  "checkUpdateDownloadQueue: numPartsToGet=$numPartsToGet, nextDownloadItemParts=${nextDownloadItemParts.size}"
          )
          AbsLogger.debug(
                  tag,
                  "checkUpdateDownloadQueue: Processing item ${downloadItem.id}, numPartsToGet=$numPartsToGet, nextDownloadItemParts=${nextDownloadItemParts.size}"
          )

          if (nextDownloadItemParts.isNotEmpty()) {
            processDownloadItemParts(nextDownloadItemParts)
          } else {
            AbsLogger.debug(
                    tag,
                    "checkUpdateDownloadQueue: No parts to download for item ${downloadItem.id}"
            )
          }

          if (currentDownloadItemParts.size >= getMaxSimultaneousDownloads()) {
            AbsLogger.debug(tag, "checkUpdateDownloadQueue: Max simultaneous downloads reached")
            break
          }
        }

        if (currentDownloadItemParts.isNotEmpty()) {
          AbsLogger.debug(tag, "checkUpdateDownloadQueue: Starting to watch downloads")
          startWatchingDownloads()
        } else {
          AbsLogger.debug(tag, "checkUpdateDownloadQueue: No active downloads to watch")
        }

        finishDebug()
        DebugUtils.logMethodExit(tag, "checkUpdateDownloadQueue")
      } finally {
        setProcessingQueue(false)
      }
    }
  }

  /** Processes the download item parts. */
  private fun processDownloadItemParts(nextDownloadItemParts: List<DownloadItemPart>) {
    AbsLogger.debug(
            tag,
            "processDownloadItemParts: Processing ${nextDownloadItemParts.size} download parts"
    )
    nextDownloadItemParts.forEach {
      AbsLogger.debug(
              tag,
              "processDownloadItemParts: Starting download for ${it.filename}, isInternalStorage=${it.isInternalStorage}"
      )
      if (it.isInternalStorage) {
        startInternalDownload(it)
      } else {
        startExternalDownload(it)
      }
    }
  }

  /** Starts an internal download. */
  private fun startInternalDownload(downloadItemPart: DownloadItemPart) {
    val serverUrl = downloadItemPart.serverUrl
    AbsLogger.debug(
            tag,
            "startInternalDownload: Starting internal download for ${downloadItemPart.filename}"
    )
    AbsLogger.debug(tag, "startInternalDownload: Server URL = $serverUrl")

    if (serverUrl.isBlank()) {
      Log.e(
              tag,
              "Failed to start internal download for ${downloadItemPart.filename} - server URL is blank"
      )
      AbsLogger.error(
              tag,
              "Failed to start internal download for ${downloadItemPart.filename} - server URL is blank"
      )
      downloadItemPart.failed = true
      return
    }

    // Check if file already exists with correct size
    val file = File(downloadItemPart.finalDestinationPath)
    if (file.exists()) {
      val expectedSize = downloadItemPart.fileSize
      val actualSize = file.length()

      if (actualSize == expectedSize) {
        // File already exists with correct size - mark as completed immediately
        downloadItemPart.completed = true
        downloadItemPart.moved = true
        downloadItemPart.progress = 100
        downloadItemPart.bytesDownloaded = expectedSize

        AbsLogger.debug(
                tag,
                "startInternalDownload: File already exists with correct size: ${file.absolutePath} (${actualSize} bytes)"
        )

        // Use the handler to ensure proper cleanup and queue processing
        handleInternalDownloadPart(downloadItemPart)
        return
      } else {
        // File exists but wrong size - delete it
        AbsLogger.debug(
                tag,
                "startInternalDownload: File exists with wrong size: ${file.absolutePath} - expected: ${expectedSize}, actual: ${actualSize}. Deleting."
        )
        file.delete()
      }
    }

    file.parentFile?.mkdirs()

    val fileOutputStream = FileOutputStream(downloadItemPart.finalDestinationPath)
    val internalProgressCallback =
            object : InternalProgressCallback {
              override fun onProgress(totalBytesWritten: Long, progress: Long) {
                // Use the new updateProgress method that tracks stalls
                downloadItemPart.updateProgress(progress, totalBytesWritten)
                // Throttle UI notifications to avoid log/event spam
                val now = System.currentTimeMillis()
                val lastEmit = lastProgressEmitTime[downloadItemPart.id] ?: 0L
                if (now - lastEmit >= progressEmitThrottleMs) {
                  lastProgressEmitTime[downloadItemPart.id] = now
                  clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)
                }
              }

              override fun onComplete(failed: Boolean) {
                downloadItemPart.failed = failed
                downloadItemPart.completed = true
                activeInternalDownloads.remove(downloadItemPart.id)
                lastProgressEmitTime.remove(downloadItemPart.id)
                AbsLogger.debug(
                        tag,
                        "startInternalDownload: Internal download completed for ${downloadItemPart.filename}, failed=$failed"
                )
                // Use the proper handler to ensure UI updates and cleanup
                handleInternalDownloadPart(downloadItemPart)
              }
            }

    Log.d(
            tag,
            "Start internal download to destination path ${downloadItemPart.finalDestinationPath} from $serverUrl"
    )
    AbsLogger.debug(
            tag,
            "startInternalDownload: Starting InternalDownloadManager for ${downloadItemPart.filename}"
    )
    InternalDownloadManager(fileOutputStream, internalProgressCallback).also { mgr ->
      activeInternalDownloads[downloadItemPart.id] = mgr
      mgr.download(serverUrl)
    }
    downloadItemPart.downloadId = 1
    currentDownloadItemParts.add(downloadItemPart)
  }

  /** Starts an external download. */
  private fun startExternalDownload(downloadItemPart: DownloadItemPart) {
    AbsLogger.debug(
            tag,
            "startExternalDownload: Starting external download for ${downloadItemPart.filename}"
    )

    val dlRequest = downloadItemPart.getDownloadRequest()
    if (dlRequest == null) {
      Log.e(tag, "Failed to create download request for ${downloadItemPart.filename} - URI is null")
      AbsLogger.error(
              tag,
              "Failed to create download request for ${downloadItemPart.filename} - URI is null"
      )
      downloadItemPart.failed = true
      return
    }

    val downloadId = downloadManager.enqueue(dlRequest)
    downloadItemPart.downloadId = downloadId
    Log.d(tag, "checkUpdateDownloadQueue: Starting download item part, downloadId=$downloadId")
    AbsLogger.debug(
            tag,
            "startExternalDownload: Enqueued external download for ${downloadItemPart.filename}, downloadId=$downloadId"
    )
    currentDownloadItemParts.add(downloadItemPart)
  }

  /** Starts watching the downloads. */
  private fun startWatchingDownloads() {
    downloadScope.launch {
      // Use thread-safe check to prevent multiple watchers
      if (!checkAndSetDownloading()) {
        Log.d(tag, "Download watching already in progress, skipping")
        return@launch
      }

      try {
        Log.d(tag, "Starting watching downloads")

        while (currentDownloadItemParts.isNotEmpty()) {
          val itemParts = currentDownloadItemParts.filter { !it.isMoving }
          for (downloadItemPart in itemParts) {
            if (downloadItemPart.isInternalStorage) {
              handleInternalDownloadPart(downloadItemPart)
            } else {
              handleExternalDownloadPart(downloadItemPart)
            }
          }

          delay(2000) // Increased delay to reduce polling frequency
        }

        Log.d(tag, "Finished watching downloads")
      } finally {
        // Always reset the downloading flag
        setDownloading(false)

        // Only check queue when all downloads are finished and there are items waiting
        if (downloadItemQueue.isNotEmpty()) {
          Log.d(
                  tag,
                  "Queue check after watching finished - ${downloadItemQueue.size} items remaining"
          )
          checkUpdateDownloadQueue()
        }
      }
    }
  }

  /** Handles a stalled download by attempting to restart it. */
  private fun handleStalledDownload(downloadItemPart: DownloadItemPart) {
    AbsLogger.debug(
            tag,
            "handleStalledDownload: Attempting to restart stalled download: ${downloadItemPart.filename}"
    )

    // Limit restart attempts to prevent infinite loops
    if (downloadItemPart.stallCount > 3) {
      AbsLogger.debug(
              tag,
              "handleStalledDownload: Too many stall attempts for ${downloadItemPart.filename}, marking as failed"
      )
      downloadItemPart.failed = true
      currentDownloadItemParts.remove(downloadItemPart)

      // Check if download item is finished
      val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
      downloadItem?.let { checkDownloadItemFinished(it) }
      return
    }

    // Cancel existing download if it's external
    if (!downloadItemPart.isInternalStorage && downloadItemPart.downloadId != null) {
      downloadManager.remove(downloadItemPart.downloadId!!)
      AbsLogger.debug(
              tag,
              "handleStalledDownload: Cancelled external download ${downloadItemPart.downloadId} for ${downloadItemPart.filename}"
      )
    }

    // Reset download state
    downloadItemPart.downloadId = null
    downloadItemPart.lastProgressUpdate = System.currentTimeMillis()
    downloadItemPart.stallCount++

    // Remove from current downloads to allow restart
    currentDownloadItemParts.remove(downloadItemPart)

    AbsLogger.debug(
            tag,
            "handleStalledDownload: Reset download state for ${downloadItemPart.filename}, will retry (attempt ${downloadItemPart.stallCount})"
    )

    // Trigger queue check to restart the download
    checkUpdateDownloadQueue()
  }

  /** Handles an internal download part. */
  private fun handleInternalDownloadPart(downloadItemPart: DownloadItemPart) {
    AbsLogger.debug(
            tag,
            "handleInternalDownloadPart: Updating UI for ${downloadItemPart.filename}, progress=${downloadItemPart.progress}%, completed=${downloadItemPart.completed}"
    )

    // isDownloadStalled() already checks !completed, !failed, lastProgressUpdate > 0, and a 30s threshold
    if (downloadItemPart.isDownloadStalled()) {
      AbsLogger.debug(
              tag,
              "handleInternalDownloadPart: Download appears stalled for ${downloadItemPart.filename}, attempting to restart"
      )
      handleStalledDownload(downloadItemPart)
      return
    }

    clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

    if (downloadItemPart.completed) {
      val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
      downloadItem?.let {
        // Save the download item state immediately after each file is completed
        // This ensures that completed files are persisted even if the app is interrupted
        DeviceManager.dbManager.saveDownloadItem(downloadItem)
        AbsLogger.debug(
                tag,
                "handleInternalDownloadPart: Saved download item state after completing ${downloadItemPart.filename}"
        )

        AbsLogger.debug(
                tag,
                "handleInternalDownloadPart: Checking if download item finished for ${downloadItem.media.metadata.title}"
        )
        checkDownloadItemFinished(it)
      }

      // Remove from current downloads immediately to prevent race conditions
      currentDownloadItemParts.remove(downloadItemPart)
      AbsLogger.debug(
              tag,
              "handleInternalDownloadPart: Removed completed download part ${downloadItemPart.filename} from current downloads"
      )

      // Check if we can start more downloads when one completes
      // Use a delay to prevent immediate race conditions
      downloadScope.launch {
        delay(100) // Small delay to allow cleanup
        if (downloadItemQueue.isNotEmpty()) {
          checkUpdateDownloadQueue()
        }
      }
    }
  }

  /** Handles an external download part. */
  private fun handleExternalDownloadPart(downloadItemPart: DownloadItemPart) {
    val downloadCheckStatus = checkDownloadItemPart(downloadItemPart)

    // Check for stalled downloads on external downloads too
    if (downloadCheckStatus == DownloadCheckStatus.InProgress &&
                    downloadItemPart.isDownloadStalled()
    ) {
      AbsLogger.debug(
              tag,
              "handleExternalDownloadPart: External download appears stalled for ${downloadItemPart.filename}, attempting to restart"
      )
      handleStalledDownload(downloadItemPart)
      return
    }

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
            // Use the new updateProgress method that tracks stalls
            downloadItemPart.updateProgress(percentProgress, bytesDownloadedSoFar)

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
    val destinationUri = downloadItemPart.destinationUri
    if (destinationUri == null) {
      Log.e(tag, "Cannot move file - destination URI is null for ${downloadItemPart.filename}")
      downloadItemPart.failed = true
      return
    }

    val file = DocumentFileCompat.fromUri(mainActivity, destinationUri)
    Log.d(tag, "DOWNLOAD: DESTINATION URI $destinationUri")

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

                // Save the download item state immediately after each file is completed
                // This ensures that completed files are persisted even if the app is interrupted
                DeviceManager.dbManager.saveDownloadItem(downloadItem)
                AbsLogger.debug(
                        tag,
                        "DOWNLOAD: Saved download item state after completing ${downloadItemPart.filename}"
                )

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

  /**
   * Scans for existing files and marks completed download item parts as finished. This prevents
   * re-downloading files that already exist on disk.
   */
  private fun scanForExistingFiles(downloadItem: DownloadItem) {
    AbsLogger.debug(
            tag,
            "scanForExistingFiles: Scanning for existing files for ${downloadItem.media.metadata.title}"
    )

    var hasCompletedFiles = false
    for (downloadItemPart in downloadItem.downloadItemParts) {
      if (!downloadItemPart.completed && !downloadItemPart.failed) {
        val finalFile = File(downloadItemPart.finalDestinationPath)

        if (finalFile.exists()) {
          val expectedSize = downloadItemPart.fileSize
          val actualSize = finalFile.length()

          if (actualSize == expectedSize) {
            // File exists with correct size - mark as completed
            downloadItemPart.completed = true
            downloadItemPart.moved = true
            downloadItemPart.progress = 100
            downloadItemPart.bytesDownloaded = expectedSize
            hasCompletedFiles = true

            AbsLogger.debug(
                    tag,
                    "scanForExistingFiles: Found existing file: ${finalFile.absolutePath} (${actualSize} bytes)"
            )
          } else {
            // File exists but wrong size - delete it
            AbsLogger.debug(
                    tag,
                    "scanForExistingFiles: File size mismatch: ${finalFile.absolutePath} - expected: ${expectedSize}, actual: ${actualSize}. Deleting."
            )
            finalFile.delete()
          }
        }
      }
    }

    if (hasCompletedFiles) {
      // Update the download item in the database with new completion status
      DeviceManager.dbManager.saveDownloadItem(downloadItem)
      AbsLogger.info(
              tag,
              "scanForExistingFiles: Updated completion status for ${downloadItem.media.metadata.title}"
      )
    }
  }

  /** Resumes a download item that was previously paused or failed. */
  fun resumeDownloadItem(downloadItem: DownloadItem) {
    Log.i(tag, "Resuming download item ${downloadItem.media.metadata.title}")
    AbsLogger.debug(tag, "Resuming download item ${downloadItem.media.metadata.title}")

    // Scan for existing completed files and mark them as completed
    scanForExistingFiles(downloadItem)

    // Check if item is already in queue
    val existingItem = downloadItemQueue.find { it.id == downloadItem.id }
    if (existingItem == null) {
      downloadItemQueue.add(downloadItem)
      clientEventEmitter.onDownloadItem(downloadItem)
      AbsLogger.debug(tag, "Added download item to queue: ${downloadItem.id}")
    } else {
      AbsLogger.debug(tag, "Download item already in queue: ${downloadItem.id}")
    }

    // Only check download queue if server is connected
    if (DeviceManager.isConnectedToServer && DeviceManager.serverAddress.isNotBlank()) {
      checkUpdateDownloadQueue()
    } else {
      AbsLogger.debug(
              tag,
              "Server not connected, downloads will start when connection is established"
      )
    }
  }

  /** Called when server connection is established to start queued downloads. */
  fun onServerConnected() {
    AbsLogger.debug(
            tag,
            "onServerConnected: Server connection established, checking queued downloads"
    )
    if (downloadItemQueue.isNotEmpty()) {
      AbsLogger.debug(
              tag,
              "onServerConnected: Found ${downloadItemQueue.size} queued downloads, starting them"
      )

      // Notify frontend about existing download items when reconnecting
      for (downloadItem in downloadItemQueue) {
        AbsLogger.debug(
                tag,
                "onServerConnected: Notifying frontend about queued download: ${downloadItem.media.metadata.title}"
        )
        clientEventEmitter.onDownloadItem(downloadItem)
      }

      checkUpdateDownloadQueue()
    }
  }

  /** Removes download items with invalid URLs that cannot be processed. */
  fun cleanupInvalidDownloads() {
    AbsLogger.debug(tag, "cleanupInvalidDownloads: Starting cleanup")

    val itemsToRemove = mutableListOf<DownloadItem>()

    for (downloadItem in downloadItemQueue) {
      val hasValidParts =
              downloadItem.downloadItemParts.any { part ->
                val serverUrl = part.serverUrl
                serverUrl.isNotBlank() &&
                        (serverUrl.startsWith("http://") || serverUrl.startsWith("https://"))
              }

      if (!hasValidParts) {
        AbsLogger.debug(
                tag,
                "cleanupInvalidDownloads: Removing item with invalid URLs: ${downloadItem.id}"
        )
        itemsToRemove.add(downloadItem)
      }
    }

    // Remove invalid items from queue and database
    for (item in itemsToRemove) {
      downloadItemQueue.remove(item)
      DeviceManager.dbManager.removeDownloadItem(item.id)
      AbsLogger.info(tag, "Removed invalid download item: ${item.media.metadata.title}")
    }

    if (itemsToRemove.isNotEmpty()) {
      AbsLogger.info(tag, "Cleaned up ${itemsToRemove.size} invalid download items")
    }
  }

  /** Stops all downloads and cleans up resources. */
  fun stopAllDownloads() {
    Log.d(tag, "Stopping all downloads")
    downloadScope.cancel()
    downloadScope.launch { setDownloading(false) }
    currentDownloadItemParts.clear()
  }

  /** Cancels all downloads and removes them from the queue. */
  fun cancelAllDownloads() {
    Log.d(tag, "Cancelling all downloads")
    AbsLogger.debug(tag, "cancelAllDownloads: Cancelling all ${downloadItemQueue.size} downloads")

    // Stop both download watching and queue processing immediately
    downloadScope.launch {
      setDownloading(false)
      setProcessingQueue(false)
    }

    // Cancel all active internal OkHttp calls immediately
    // Snapshot the map first to avoid ConcurrentModificationException: cancel() can trigger
    // onComplete on another thread which removes entries from activeInternalDownloads.
    val activeDownloadSnapshot = activeInternalDownloads.entries.toList()
    activeInternalDownloads.clear()
    lastProgressEmitTime.clear()
    for ((partId, mgr) in activeDownloadSnapshot) {
      mgr.cancel()
      AbsLogger.debug(tag, "cancelAllDownloads: Cancelled internal download manager for part $partId")
    }

    // Cancel all current download parts
    for (downloadItemPart in currentDownloadItemParts) {
      if (!downloadItemPart.isInternalStorage && downloadItemPart.downloadId != null) {
        // Cancel external downloads through DownloadManager
        downloadManager.remove(downloadItemPart.downloadId!!)
        AbsLogger.debug(
                tag,
                "cancelAllDownloads: Cancelled external download ${downloadItemPart.filename}"
        )
      }
    }

    // Clear all download items from queue and database
    val itemsToRemove = downloadItemQueue.toList()
    for (downloadItem in itemsToRemove) {
      DeviceManager.dbManager.removeDownloadItem(downloadItem.id)
      AbsLogger.debug(
              tag,
              "cancelAllDownloads: Removed download item ${downloadItem.media.metadata.title}"
      )
    }

    downloadItemQueue.clear()
    currentDownloadItemParts.clear()

    AbsLogger.info(tag, "Cancelled all downloads")
  }

  /** Retries the download queue manually. */
  fun retryDownloadQueue() {
    Log.d(tag, "Retrying download queue")
    AbsLogger.debug(
            tag,
            "retryDownloadQueue: Retrying download queue with ${downloadItemQueue.size} items"
    )

    if (downloadItemQueue.isNotEmpty()) {
      // Scan each queued item so already-completed files on disk are not re-downloaded
      for (downloadItem in downloadItemQueue) {
        scanForExistingFiles(downloadItem)
      }
      checkUpdateDownloadQueue()
      AbsLogger.debug(tag, "retryDownloadQueue: Download queue restart initiated")
    } else {
      AbsLogger.debug(tag, "retryDownloadQueue: No downloads in queue to retry")
    }
  }

  /**
   * Cancels the internal coroutine scope.
   * Must be called when the owning service is destroyed to avoid coroutine leaks.
   */
  fun cancel() {
    Log.d(tag, "cancel: Cancelling downloadScope")
    downloadScope.cancel()
  }
}
