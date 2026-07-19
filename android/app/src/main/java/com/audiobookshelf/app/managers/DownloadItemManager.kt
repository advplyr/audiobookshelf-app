package com.audiobookshelf.app.managers

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call

/** Manages the process-owned queue for app-managed downloads. */
class DownloadItemManager(
        private val folderScanner: FolderScanner,
        private val context: Context,
        private var clientEventEmitter: DownloadEventEmitter
) {
  private val tag = "DownloadItemManager"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val activeCalls = ConcurrentHashMap<String, Call>()
  private val safFolderLocks = ConcurrentHashMap<String, Any>()
  private val reservations = mutableMapOf<String, Long>()
  private val lastPersistTime = mutableMapOf<String, Long>()
  private var watcherRunning = false
  private val jacksonMapper =
          jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  var downloadItemQueue: MutableList<DownloadItem> = mutableListOf()
    private set
  var currentDownloadItemParts: MutableList<DownloadItemPart> = mutableListOf()
    private set

  interface DownloadEventEmitter {
    fun onDownloadItem(downloadItem: DownloadItem)
    fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart)
    fun onDownloadItemComplete(jsobj: JSObject)
    fun onQueueChanged(hasWork: Boolean)
  }

  interface InternalProgressCallback {
    fun onProgress(totalBytesWritten: Long, progress: Long)
    fun onComplete(failed: Boolean)
  }

  init {
    DeviceManager.dbManager.clearLegacyDownloadQueueOnce()
    IncompleteDownloadCleanup.cleanupExpired(context)
  }

  @Synchronized
  fun setEventEmitter(eventEmitter: DownloadEventEmitter) {
    clientEventEmitter = eventEmitter
    downloadItemQueue.forEach(clientEventEmitter::onDownloadItem)
    notifyQueueChanged()
  }

  @Synchronized
  fun restoreQueue() {
    if (downloadItemQueue.isNotEmpty()) return
    DeviceManager.dbManager.getDownloadItems().forEach { item ->
      if (item.isDownloadFinished) {
        downloadItemQueue.add(item)
        checkDownloadItemFinished(item)
        return@forEach
      }
      item.downloadItemParts.forEach { part ->
        if (part.moved) return@forEach
        if (item.terminalFailureAt != null && part.failed) return@forEach
        part.downloadId = null
        part.isMoving = false
        part.failed = false
        part.completed = false
        part.waitingForSpace = false
        part.bytesDownloaded = File(part.destinationPath).takeIf(File::exists)?.length() ?: 0L
      }
      downloadItemQueue.add(item)
      if (item.terminalFailureAt != null) IncompleteDownloadCleanup.schedule(context, item)
      clientEventEmitter.onDownloadItem(item)
    }
    checkUpdateDownloadQueue()
    notifyQueueChanged()
  }

  @Synchronized
  fun addDownloadItem(downloadItem: DownloadItem) {
    if (downloadItemQueue.any { it.id == downloadItem.id }) return
    persist(downloadItem, force = true)
    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    checkUpdateDownloadQueue()
    notifyQueueChanged()
  }

  @Synchronized
  fun retryAll() {
    downloadItemQueue.forEach { item ->
      item.terminalFailureAt = null
      IncompleteDownloadCleanup.cancel(context, item.id)
      item.downloadItemParts.filter { it.failed }.forEach { part ->
        part.failed = false
        part.completed = false
        part.isMoving = false
        part.downloadId = null
        part.retryCount = 0
      }
      persist(item, force = true)
    }
    checkUpdateDownloadQueue()
    notifyQueueChanged()
  }

  @Synchronized
  fun cancelAll() {
    activeCalls.values.forEach(Call::cancel)
    activeCalls.clear()
    downloadItemQueue.forEach { item ->
      item.downloadItemParts.forEach { part -> File(part.destinationPath).delete() }
      DeviceManager.dbManager.removeDownloadItem(item.id)
    }
    currentDownloadItemParts.clear()
    reservations.clear()
    downloadItemQueue.clear()
    notifyQueueChanged()
  }

  @Synchronized
  fun hasWork(): Boolean = downloadItemQueue.isNotEmpty()

  @Synchronized
  private fun checkUpdateDownloadQueue() {
    downloadItemQueue.toList().forEach { item ->
      val slots = MAX_SIMULTANEOUS_DOWNLOADS - currentDownloadItemParts.size
      if (slots <= 0) return@forEach
      item.getNextDownloadItemParts(slots).forEach { part ->
        if (tryReserve(part)) startDownload(item, part)
        else {
          part.waitingForSpace = true
          part.lastUpdateTime = System.currentTimeMillis()
          persist(item)
          clientEventEmitter.onDownloadItemPartUpdate(part)
        }
      }
    }
    startWatchingDownloads()
  }

  private fun startDownload(item: DownloadItem, part: DownloadItemPart) {
    val stagingFile = File(part.destinationPath)
    stagingFile.parentFile?.mkdirs()
    part.downloadId = APP_MANAGED_DOWNLOAD_ID
    part.waitingForSpace = false
    part.lastUpdateTime = System.currentTimeMillis()
    currentDownloadItemParts.add(part)
    persist(item, force = true)
    activeCalls[part.id] =
            InternalDownloadManager(stagingFile, part.fileSize, object : InternalProgressCallback {
              override fun onProgress(totalBytesWritten: Long, progress: Long) {
                synchronized(this@DownloadItemManager) {
                  if (part !in currentDownloadItemParts) return
                  part.bytesDownloaded = totalBytesWritten
                  part.progress = progress
                  part.lastUpdateTime = System.currentTimeMillis()
                  persist(item)
                }
              }

              override fun onComplete(failed: Boolean) {
                synchronized(this@DownloadItemManager) {
                  if (part !in currentDownloadItemParts) return
                  part.failed = failed
                  part.completed = !failed
                  part.lastUpdateTime = System.currentTimeMillis()
                  activeCalls.remove(part.id)
                  persist(item, force = true)
                }
              }
            }, { hasAvailableSpace(part) }).download(serverUrl(item, part))
  }

  @Synchronized
  private fun startWatchingDownloads() {
    if (watcherRunning) return
    watcherRunning = true
    scope.launch {
      while (true) {
        val activeParts = synchronized(this@DownloadItemManager) { currentDownloadItemParts.toList() }
        activeParts.forEach(::handlePartUpdate)
        synchronized(this@DownloadItemManager) {
          checkUpdateDownloadQueue()
          if (downloadItemQueue.isEmpty()) {
            watcherRunning = false
            notifyQueueChanged()
            return@launch
          }
        }
        delay(WATCH_INTERVAL_MS)
      }
    }
  }

  private fun handlePartUpdate(part: DownloadItemPart) {
    clientEventEmitter.onDownloadItemPartUpdate(part)
    val item = synchronized(this) { downloadItemQueue.find { it.id == part.downloadItemId } } ?: run {
      removeActivePart(part)
      return
    }
    if (!part.completed && !part.failed) {
      val lastUpdate = part.lastUpdateTime ?: return
      if (System.currentTimeMillis() - lastUpdate > STALL_TIMEOUT_MS) {
        Log.w(tag, "Download stalled: ${part.filename}")
        activeCalls.remove(part.id)?.cancel()
        failOrRetry(item, part, "Download stalled")
      }
      return
    }
    if (part.failed) {
      failOrRetry(item, part, "Transfer failed")
      return
    }
    if (part.isInternalStorage) finalizeInternalFile(item, part) else moveDownloadedFile(item, part)
  }

  private fun failOrRetry(item: DownloadItem, part: DownloadItemPart, reason: String) {
    removeActivePart(part)
    part.retryCount += 1
    releaseReservation(part)
    if (part.retryCount > MAX_RETRIES) {
      Log.e(tag, "$reason after $MAX_RETRIES retries: ${part.filename}")
      part.failed = true
      part.completed = false
      part.downloadId = null
      item.terminalFailureAt = item.terminalFailureAt ?: System.currentTimeMillis()
      persist(item, force = true)
      IncompleteDownloadCleanup.schedule(context, item)
      notifyQueueChanged()
      return
    }
    part.failed = false
    part.completed = false
    part.downloadId = null
    part.isMoving = false
    persist(item, force = true)
    scope.launch {
      delay(RETRY_BASE_DELAY_MS * (1L shl (part.retryCount - 1)))
      synchronized(this@DownloadItemManager) { checkUpdateDownloadQueue() }
    }
  }

  private fun finalizeInternalFile(item: DownloadItem, part: DownloadItemPart) {
    if (part.moved || part.isMoving) return
    part.isMoving = true
    val stagingFile = File(part.destinationPath)
    val finalFile = File(part.finalDestinationPath)
    finalFile.parentFile?.mkdirs()
    val backup = File(finalFile.parentFile, ".${finalFile.name}.abs-backup")
    try {
      if (backup.exists() && !backup.delete()) throw IllegalStateException("Could not clear backup")
      if (finalFile.exists() && !finalFile.renameTo(backup)) throw IllegalStateException("Could not protect existing file")
      if (!stagingFile.renameTo(finalFile)) {
        if (backup.exists()) backup.renameTo(finalFile)
        throw IllegalStateException("Could not finalize internal staging file")
      }
      backup.delete()
      completePart(item, part)
    } catch (e: Exception) {
      part.isMoving = false
      part.failed = true
      failOrRetry(item, part, e.message ?: "Internal finalization failed")
    }
  }

  private fun moveDownloadedFile(item: DownloadItem, part: DownloadItemPart) {
    if (part.moved || part.isMoving) return
    val root = DocumentFile.fromTreeUri(context, Uri.parse(part.localFolderUrl))
            ?: return failFinalization(item, part, "Could not resolve SAF destination")
    part.isMoving = true
    persist(item, force = true)
    scope.launch {
      try {
        if (!hasAvailableSpace(part)) throw IllegalStateException("Insufficient storage for SAF copy")
        val folderKey = "${root.uri}/${part.finalDestinationSubfolder}"
        val folderLock = safFolderLocks.computeIfAbsent(folderKey) { Any() }
        val folder = synchronized(folderLock) {
          getOrCreateFolder(root, part.finalDestinationSubfolder)
        } ?: throw IllegalStateException("Could not create SAF destination folder")
        val temporaryName = ".${part.filename}.${part.id.hashCode()}.part"
        folder.findFile(temporaryName)?.delete()
        val temporary = folder.createFile(mimeTypeFor(part), temporaryName)
                ?: throw IllegalStateException("Could not create SAF temporary file")
        val staging = File(part.destinationPath)
        FileInputStream(staging).use { input ->
          context.contentResolver.openOutputStream(temporary.uri, "w")?.use { input.copyTo(it) }
                  ?: throw IllegalStateException("Could not open SAF output stream")
        }
        if (temporary.length() != staging.length()) throw IllegalStateException("SAF copy size mismatch")
        val existing = folder.findFile(part.filename)
        if (existing != null && !existing.delete()) throw IllegalStateException("Could not replace existing file")
        if (!temporary.renameTo(part.filename)) throw IllegalStateException("Could not finalize SAF temporary file")
        val destination = folder.findFile(part.filename)
                ?: throw IllegalStateException("Could not reopen finalized SAF file")
        if (destination.length() != staging.length()) throw IllegalStateException("SAF final size mismatch")
        if (!staging.delete()) Log.w(tag, "Could not remove staging file ${staging.name}")
        part.completedDestinationUri = destination.uri.toString()
        completePart(item, part)
      } catch (e: Exception) {
        failFinalization(item, part, "SAF copy failed: ${e.message}")
      }
    }
  }

  private fun failFinalization(item: DownloadItem, part: DownloadItemPart, message: String) {
    Log.e(tag, message)
    part.isMoving = false
    part.failed = true
    failOrRetry(item, part, message)
  }

  private fun completePart(item: DownloadItem, part: DownloadItemPart) {
    part.moved = true
    part.completed = true
    part.failed = false
    part.isMoving = false
    releaseReservation(part)
    removeActivePart(part)
    persist(item, force = true)
    checkDownloadItemFinished(item)
  }

  private fun checkDownloadItemFinished(item: DownloadItem) {
    if (!item.isDownloadFinished) return
    scope.launch {
      folderScanner.scanDownloadItem(item) { scanResult ->
        val event = JSObject().apply {
          put("libraryItemId", item.id)
          put("localFolderId", item.localFolder.id)
          scanResult?.localLibraryItem?.let { put("localLibraryItem", JSObject(jacksonMapper.writeValueAsString(it))) }
          scanResult?.localMediaProgress?.let { put("localMediaProgress", JSObject(jacksonMapper.writeValueAsString(it))) }
        }
        clientEventEmitter.onDownloadItemComplete(event)
        synchronized(this@DownloadItemManager) {
          downloadItemQueue.remove(item)
          DeviceManager.dbManager.removeDownloadItem(item.id)
          notifyQueueChanged()
        }
      }
    }
  }

  private fun tryReserve(part: DownloadItemPart): Boolean {
    if (part.fileSize <= 0L && currentDownloadItemParts.any { it.fileSize <= 0L }) return false
    val staging = File(part.destinationPath)
    staging.parentFile?.mkdirs()
    val expectedSize = if (part.fileSize > 0L) part.fileSize else UNKNOWN_PART_RESERVATION_BYTES
    val remaining = (expectedSize - (staging.takeIf(File::exists)?.length() ?: 0L)).coerceAtLeast(0L)
    val required = if (part.isInternalStorage) remaining else remaining + expectedSize
    val key = storageKey(staging)
    val fs = statFsFor(staging)
    val headroom = max(MIN_FREE_SPACE_BYTES, fs.totalBytes / 20L)
    val alreadyReserved = reservations.filterKeys { storageKey(File(it)) == key }.values.sum()
    if (fs.availableBytes - alreadyReserved < required + headroom) return false
    reservations[part.destinationPath] = required
    return true
  }

  private fun hasAvailableSpace(part: DownloadItemPart): Boolean {
    val staging = File(part.destinationPath)
    val fs = statFsFor(staging)
    return fs.availableBytes >= max(MIN_FREE_SPACE_BYTES, fs.totalBytes / 20L)
  }

  private fun statFsFor(staging: File): StatFs {
    var directory = staging.parentFile ?: context.filesDir
    directory.mkdirs()
    while (!directory.exists()) directory = directory.parentFile ?: context.filesDir
    return StatFs(directory.absolutePath)
  }

  private fun storageKey(file: File): String =
          if (file.absolutePath.startsWith(context.filesDir.absolutePath)) "internal" else "external"

  private fun releaseReservation(part: DownloadItemPart) { reservations.remove(part.destinationPath) }

  @Synchronized
  private fun removeActivePart(part: DownloadItemPart) {
    activeCalls.remove(part.id)
    currentDownloadItemParts.remove(part)
  }

  private fun persist(item: DownloadItem, force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (!force && now - (lastPersistTime[item.id] ?: 0L) < PERSIST_INTERVAL_MS) return
    lastPersistTime[item.id] = now
    DeviceManager.dbManager.saveDownloadItem(item)
  }

  private fun notifyQueueChanged() { clientEventEmitter.onQueueChanged(downloadItemQueue.isNotEmpty()) }

  fun destroy() {
    activeCalls.values.forEach(Call::cancel)
    activeCalls.clear()
    scope.cancel()
  }

  private fun getOrCreateFolder(root: DocumentFile, relativePath: String): DocumentFile? {
    var current = root
    relativePath.split('/').filter { it.isNotBlank() }.forEach { segment ->
      if (segment == "." || segment == "..") return null
      current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
    }
    return current
  }

  private fun mimeTypeFor(part: DownloadItemPart): String =
          part.audioTrack?.mimeType ?: when (part.ebookFile?.ebookFormat?.lowercase()) {
            "epub" -> "application/epub+zip"
            "pdf" -> "application/pdf"
            else -> "image/jpeg"
          }

  private fun serverUrl(item: DownloadItem, part: DownloadItemPart): String {
    val token = DeviceManager.deviceData.serverConnectionConfigs
            .find { it.id == item.serverConnectionConfigId }?.token ?: DeviceManager.token
    var url = "${item.serverAddress}${part.serverPath}?token=$token"
    if (part.serverPath.endsWith("/cover")) url += "&raw=1"
    return url
  }

  private companion object {
    const val APP_MANAGED_DOWNLOAD_ID = -1L
    const val MAX_SIMULTANEOUS_DOWNLOADS = 3
    const val WATCH_INTERVAL_MS = 1_000L
    const val STALL_TIMEOUT_MS = 60_000L
    const val RETRY_BASE_DELAY_MS = 5_000L
    const val MAX_RETRIES = 5
    const val PERSIST_INTERVAL_MS = 2_000L
    const val MIN_FREE_SPACE_BYTES = 100L * 1024L * 1024L
    const val UNKNOWN_PART_RESERVATION_BYTES = 100L * 1024L * 1024L
  }
}
