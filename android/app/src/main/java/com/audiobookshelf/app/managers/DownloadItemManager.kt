package com.audiobookshelf.app.managers

import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
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
import com.audiobookshelf.app.download.DownloadNotificationService
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Manages download items and their parts. */
class DownloadItemManager(
        var downloadManager: DownloadManager,
        private var folderScanner: FolderScanner,
        var mainActivity: MainActivity,
        private var clientEventEmitter: DownloadEventEmitter
) {
  companion object {
    private const val TAG = "DownloadItemManager"
    var isDownloading: Boolean = false
    // Storage buffer: minimum 100MB or 5% of file size, whichever is greater
    // This provides adequate buffer for small files while avoiding excessive waste for large files
    private const val MIN_STORAGE_BUFFER_BYTES = 100L * 1024 * 1024 // 100MB
    private const val STORAGE_BUFFER_PERCENTAGE = 0.05 // 5%
  }

  private val tag = TAG

  // Create a supervisor scope tied to the manager's lifecycle
  private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val maxSimultaneousDownloads = 5 // Increased for faster parallel downloads
  private var jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  @Volatile
  private var downloadService: DownloadNotificationService? = null
  @Volatile
  private var isBound = false
  private val serviceLock = Any()

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      synchronized(serviceLock) {
        val binder = service as? DownloadNotificationService.LocalBinder
        downloadService = binder?.getService()
        isBound = true
        Log.d(tag, "Download service connected")
      }
      // Call outside synchronized block to avoid potential deadlocks
      updateServiceNotification()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      synchronized(serviceLock) {
        downloadService = null
        isBound = false
        Log.d(tag, "Download service disconnected")
      }
    }
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

  init {
    // Restore incomplete downloads from previous session
    restoreIncompleteDownloads()
  }

  /**
   * Restores incomplete downloads from database on app restart.
   * This allows downloads to resume if the app was killed by the system.
   */
  private fun restoreIncompleteDownloads() {
    try {
      val savedDownloads = DeviceManager.dbManager.getDownloadItems()
      if (savedDownloads.isNotEmpty()) {
        Log.d(tag, "Restoring ${savedDownloads.size} incomplete downloads from previous session")
        synchronized(downloadItemQueue) {
          downloadItemQueue.addAll(savedDownloads)
        }
        // Start processing restored downloads
        if (savedDownloads.isNotEmpty()) {
          startDownloadService()
          checkUpdateDownloadQueue()
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Error restoring incomplete downloads", e)
    }
  }

  interface DownloadEventEmitter {
    fun onDownloadItem(downloadItem: DownloadItem)
    fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart)
    fun onDownloadItemComplete(jsobj: JSObject)
    fun onDownloadError(error: String, details: String)
  }

  interface InternalProgressCallback {
    fun onProgress(totalBytesWritten: Long, progress: Long)
    fun onComplete(failed: Boolean)
  }

  /**
   * Returns true if there are any active or pending downloads
   */
  fun hasActiveDownloads(): Boolean {
    synchronized(downloadItemQueue) {
      return downloadItemQueue.isNotEmpty() || currentDownloadItemParts.isNotEmpty()
    }
  }

  /** Adds a download item to the queue and starts processing the queue. */
  fun addDownloadItem(downloadItem: DownloadItem) {
    // Check if there's enough storage space before starting download
    val totalSize = downloadItem.downloadItemParts.sumOf { it.fileSize }
    val availableSpace = getAvailableStorageSpace()

    // Calculate buffer with progressive percentage:
    // - Files < 1GB: 5% buffer
    // - Files 1-5GB: 3% buffer  
    // - Files > 5GB: 2% buffer (to avoid excessive waste)
    val oneGB = 1024L * 1024 * 1024
    val fiveGB = 5L * 1024 * 1024 * 1024
    val bufferPercentage = when {
      totalSize < oneGB -> STORAGE_BUFFER_PERCENTAGE
      totalSize < fiveGB -> 0.03
      else -> 0.02
    }
    val percentageBuffer = (totalSize * bufferPercentage).toLong()
    val storageBuffer = maxOf(MIN_STORAGE_BUFFER_BYTES, percentageBuffer)
    val requiredSpace = totalSize + storageBuffer

    if (availableSpace < requiredSpace) {
      val requiredMB = requiredSpace / (1024 * 1024)
      val availableMB = availableSpace / (1024 * 1024)
      val bufferMB = storageBuffer / (1024 * 1024)
      val errorMessage = "Insufficient storage space. Required: ${requiredMB}MB (${bufferMB}MB buffer), Available: ${availableMB}MB"
      Log.e(tag, errorMessage)

      // Notify via event emitter for proper handling regardless of activity state
      val errorDetails = "Need ${requiredMB}MB but only ${availableMB}MB available."
      clientEventEmitter.onDownloadError("INSUFFICIENT_STORAGE", errorDetails)
      return
    }

    DeviceManager.dbManager.saveDownloadItem(downloadItem)
    Log.i(tag, "Add download item ${downloadItem.media.metadata.title}")

    synchronized(downloadItemQueue) {
      downloadItemQueue.add(downloadItem)
    }
    clientEventEmitter.onDownloadItem(downloadItem)

    // Start foreground service if not already started
    startDownloadService()

    checkUpdateDownloadQueue()
  }

  /** Gets available storage space in bytes */
  private fun getAvailableStorageSpace(): Long {
    return try {
      val downloadDir = File(mainActivity.filesDir, "downloads")
      if (!downloadDir.exists()) {
        downloadDir.mkdirs()
      }
      val stat = android.os.StatFs(downloadDir.absolutePath)
      stat.availableBytes
    } catch (e: Exception) {
      Log.e(tag, "Error getting available storage space", e)
      0L
    }
  }

  /** Starts the download foreground service */
  private fun startDownloadService() {
    synchronized(serviceLock) {
      if (isBound) {
        Log.d(tag, "Download service already running")
        return
      }
    }

    try {
      // Verify activity and context are valid before proceeding
      if (mainActivity.isFinishing || mainActivity.isDestroyed) {
        Log.w(tag, "Cannot start service: activity is finishing or destroyed")
        return
      }

      // Use application context to keep service alive independent of activity
      val context = mainActivity.applicationContext
      if (context == null) {
        Log.e(tag, "Cannot start service: application context is null")
        return
      }

      val intent = Intent(context, DownloadNotificationService::class.java)

      // Start as foreground service first
      ContextCompat.startForegroundService(context, intent)

      // Then bind to get service reference, using application context
      context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
      Log.d(tag, "Started download foreground service with application context")
    } catch (e: Exception) {
      Log.e(tag, "Failed to start download service", e)
    }
  }

  /** Stops the download foreground service */
  private fun stopDownloadService() {
    val service: DownloadNotificationService?
    synchronized(serviceLock) {
      if (!isBound) {
        Log.d(tag, "Download service not running")
        return
      }
      service = downloadService
    }

    try {
      service?.stopForegroundService()

      // Unbind from application context
      try {
        val context = mainActivity.applicationContext
        if (context != null) {
          context.unbindService(serviceConnection)
        } else {
          Log.w(tag, "Application context is null, cannot unbind service")
        }
      } catch (e: IllegalArgumentException) {
        // Service was never bound or already unbound - this is expected in some cases
        Log.d(tag, "Service connection not registered (already unbound or never bound)")
      } catch (e: Exception) {
        // Unexpected error during unbind - log with full details for debugging
        Log.e(tag, "Unexpected error unbinding service: ${e.javaClass.simpleName}: ${e.message}", e)
      }

      synchronized(serviceLock) {
        isBound = false
        downloadService = null
      }
      Log.d(tag, "Stopped download foreground service")
    } catch (e: Exception) {
      Log.e(tag, "Failed to stop download service", e)
    }
  }

  /** Updates the service notification with current download status */
  private fun updateServiceNotification() {
    val service: DownloadNotificationService?
    synchronized(serviceLock) {
      if (!isBound || downloadService == null) {
        Log.d(tag, "Service not bound, skipping notification update")
        return
      }
      service = downloadService
    }

    try {
      val currentFile = currentDownloadItemParts.firstOrNull()?.filename ?: ""
      service?.updateNotification(currentDownloadItemParts.size, currentFile)
    } catch (e: Exception) {
      Log.e(tag, "Failed to update service notification", e)
    }
  }

  /** Checks and updates the download queue. */
  private fun checkUpdateDownloadQueue() {
    // Create a snapshot to avoid ConcurrentModificationException
    val queueSnapshot = synchronized(downloadItemQueue) {
      downloadItemQueue.toList()
    }

    for (downloadItem in queueSnapshot) {
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
    updateServiceNotification()
  }

  /** Starts an internal download. */
  private fun startInternalDownload(downloadItemPart: DownloadItemPart) {
    val file = File(downloadItemPart.finalDestinationPath)
    file.parentFile?.mkdirs()

    val internalProgressCallback =
            object : InternalProgressCallback {
              override fun onProgress(totalBytesWritten: Long, progress: Long) {
                downloadItemPart.bytesDownloaded = totalBytesWritten
                downloadItemPart.progress = progress
              }

              override fun onComplete(failed: Boolean) {
                downloadItemPart.failed = failed
                downloadItemPart.completed = true
                
                // Notify user of download failure
                if (failed) {
                  val errorMsg = "Download failed: ${downloadItemPart.filename}"
                  Log.e(tag, errorMsg)
                  
                  // Check if this might be a token expiration (downloads taking >1 hour)
                  val downloadTime = System.currentTimeMillis() - (downloadItemPart.bytesDownloaded / 100000) // Rough estimate
                  val errorType = if (downloadTime > 3600000) { // >1 hour
                    "TOKEN_EXPIRED"
                  } else {
                    "DOWNLOAD_FAILED"
                  }
                  
                  val errorDetails = if (errorType == "TOKEN_EXPIRED") {
                    "Download session expired. Large downloads may require re-authentication."
                  } else {
                    "Failed to download: ${downloadItemPart.filename}"
                  }
                  
                  clientEventEmitter.onDownloadError(errorType, errorDetails)
                }
              }
            }

    Log.d(
            tag,
            "Start internal download to destination path ${downloadItemPart.finalDestinationPath} from ${downloadItemPart.serverUrl}"
    )

    // Use file-based constructor for resume support
    InternalDownloadManager(file, internalProgressCallback)
            .download(downloadItemPart.serverUrl)
    downloadItemPart.downloadId = 1
    synchronized(currentDownloadItemParts) {
      currentDownloadItemParts.add(downloadItemPart)
    }
    updateServiceNotification()
  }

  /** Starts an external download. */
  private fun startExternalDownload(downloadItemPart: DownloadItemPart) {
    val dlRequest = downloadItemPart.getDownloadRequest()
    val downloadId = downloadManager.enqueue(dlRequest)
    downloadItemPart.downloadId = downloadId
    Log.d(tag, "checkUpdateDownloadQueue: Starting download item part, downloadId=$downloadId")
    synchronized(currentDownloadItemParts) {
      currentDownloadItemParts.add(downloadItemPart)
    }
    updateServiceNotification()
  }

  /** Starts watching the downloads. */
  private fun startWatchingDownloads() {
    if (isDownloading) return // Already watching

    managerScope.launch {
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

        // Update notification periodically
        launch(Dispatchers.Main) {
          updateServiceNotification()
        }
      }

      Log.d(tag, "Finished watching downloads")
      isDownloading = false

      // Stop the foreground service when all downloads are complete
      if (downloadItemQueue.isEmpty()) {
        launch(Dispatchers.Main) {
          stopDownloadService()
        }
      }
    }
  }

  /** Handles an internal download part. */
  private fun handleInternalDownloadPart(downloadItemPart: DownloadItemPart) {
    clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

    if (downloadItemPart.completed) {
      val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
      downloadItem?.let { checkDownloadItemFinished(it) }
      synchronized(currentDownloadItemParts) {
        currentDownloadItemParts.remove(downloadItemPart)
      }
      updateServiceNotification()
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
      synchronized(currentDownloadItemParts) {
        currentDownloadItemParts.remove(downloadItemPart)
      }
      updateServiceNotification()
    } else if (downloadCheckStatus == DownloadCheckStatus.Successful) {
      moveDownloadedFile(downloadItem, downloadItemPart)
    } else if (downloadCheckStatus != DownloadCheckStatus.InProgress) {
      checkDownloadItemFinished(downloadItem)
      synchronized(currentDownloadItemParts) {
        currentDownloadItemParts.remove(downloadItemPart)
      }
      updateServiceNotification()
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
                
                // Cleanup both temporary and destination files
                file?.delete()
                try {
                  val tempFile = File(downloadItemPart.finalDestinationPath)
                  if (tempFile.exists()) {
                    tempFile.delete()
                    Log.d(tag, "Cleaned up failed download file: ${tempFile.name}")
                  }
                } catch (e: Exception) {
                  Log.w(tag, "Error cleaning up failed download", e)
                }
                
                // Notify user
                clientEventEmitter.onDownloadError(
                  "MOVE_FAILED",
                  "Failed to move ${downloadItemPart.filename}: $errorCode"
                )
                
                checkDownloadItemFinished(downloadItem)
                synchronized(currentDownloadItemParts) {
                  currentDownloadItemParts.remove(downloadItemPart)
                }
                updateServiceNotification()
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
                synchronized(currentDownloadItemParts) {
                  currentDownloadItemParts.remove(downloadItemPart)
                }
                updateServiceNotification()
              }
            }

    val localFolderFile =
            DocumentFileCompat.fromUri(mainActivity, Uri.parse(downloadItemPart.localFolderUrl))
    if (localFolderFile == null) {
      // Failed
      downloadItemPart.failed = true
      Log.e(tag, "Local Folder File from uri is null")
      checkDownloadItemFinished(downloadItem)
      synchronized(currentDownloadItemParts) {
        currentDownloadItemParts.remove(downloadItemPart)
      }
      updateServiceNotification()
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

      // Persist download completion to DB before scanning to prevent orphaned files on crash
      try {
        DeviceManager.dbManager.saveDownloadItem(downloadItem)
      } catch (e: Exception) {
        Log.e(tag, "Failed to save download item to database", e)
      }

      managerScope.launch {
        try {
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
            synchronized(downloadItemQueue) {
              downloadItemQueue.remove(downloadItem)
            }
            DeviceManager.dbManager.removeDownloadItem(downloadItem.id)
          }
        }
        } catch (e: Exception) {
          Log.e(tag, "Error during folder scan for ${downloadItem.itemTitle}", e)
          launch(Dispatchers.Main) {
            clientEventEmitter.onDownloadError(
              "SCAN_FAILED",
              "Failed to process ${downloadItem.itemTitle}: ${e.message}"
            )
            // Still remove from queue to prevent stuck downloads
            synchronized(downloadItemQueue) {
              downloadItemQueue.remove(downloadItem)
            }
          }
        }
      }
    }
  }

  /**
   * Cleanup method to properly release resources when the manager is destroyed.
   * Should be called when the plugin or activity is being destroyed.
   */
  fun cleanup() {
    Log.d(tag, "Cleaning up DownloadItemManager")

    // Cancel all coroutines to prevent leaks
    try {
      managerScope.cancel("DownloadItemManager cleanup")
    } catch (e: Exception) {
      Log.e(tag, "Error canceling coroutine scope", e)
    }

    // Stop the service if still running
    synchronized(serviceLock) {
      if (isBound) {
        try {
          stopDownloadService()
        } catch (e: Exception) {
          Log.e(tag, "Error stopping service during cleanup", e)
        }
      }
    }

    // Clear queues with proper synchronization
    synchronized(downloadItemQueue) {
      downloadItemQueue.clear()
    }
    synchronized(currentDownloadItemParts) {
      currentDownloadItemParts.clear()
    }
  }
}
