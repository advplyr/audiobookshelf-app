package com.audiobookshelf.app.managers

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.fullName
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

/** Manages the process-owned queue for app-managed downloads. */
class DownloadItemManager(
        private val folderScanner: FolderScanner,
        private val context: Context,
        private var clientEventEmitter: DownloadEventEmitter
) {
  private val tag = "DownloadItemManager"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val activeCalls = ConcurrentHashMap<String, InternalDownloadManager.DownloadHandle>()
  private val safFolderLocks = ConcurrentHashMap<String, Any>()
  private val scanLocks = ConcurrentHashMap<String, Any>()
  private val reservations = mutableMapOf<String, Long>()
  private val lastPersistTime = mutableMapOf<String, Long>()
  private val finalizingItems = mutableSetOf<String>()
  private var watcherRunning = false
  private val jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

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
      item.downloadItemParts.filter { it.moved }.forEach { part ->
        if (!finalizedFileExists(part)) {
          Log.w(tag, "Finalized file is missing; resetting ${part.filename}")
          part.moved = false
          part.completed = false
          part.completedDestinationUri = null
          part.downloadId = null
          part.reusedExistingFile = false
        }
      }
      if (item.isDownloadFinished) {
        downloadItemQueue.add(item)
        checkDownloadItemFinished(item)
        return@forEach
      }
      var resetFailed = false
      item.downloadItemParts.forEach { part ->
        if (part.moved) return@forEach
        if (!resetPartForFreshDownload(part)) resetFailed = true
      }
      if (resetFailed) {
        item.terminalFailureAt = item.terminalFailureAt ?: System.currentTimeMillis()
        item.stagingCleanupAt = null
      }
      if (item.terminalFailureAt != null) {
        item.downloadItemParts.filter { !it.moved }.forEach { it.failed = true }
      }
      downloadItemQueue.add(item)
      if (item.terminalFailureAt != null) IncompleteDownloadCleanup.schedule(context, item)
      clientEventEmitter.onDownloadItem(item)
    }
    notifyQueueChanged()
  }

  @Synchronized
  fun addDownloadItem(downloadItem: DownloadItem) {
    val existingItem = downloadItemQueue.find { it.id == downloadItem.id }
    if (existingItem != null) {
      return
    }
    persist(downloadItem, force = true)
    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    notifyQueueChanged()
  }

  @Synchronized
  fun retryDownloadItem(downloadItemId: String): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    if (item.downloadItemParts.any { it in currentDownloadItemParts }) return false
    if (item.isDownloadFinished) return false
    synchronized(IncompleteDownloadCleanup) {
      var resetFailed = false
      item.downloadItemParts.filter { !it.moved }.forEach { part ->
        if (!resetPartForFreshDownload(part)) resetFailed = true
      }
      if (resetFailed) {
        item.downloadItemParts.filter { !it.moved }.forEach { it.failed = true }
        persist(item, force = true)
        return false
      }
      item.terminalFailureAt = null
      item.stagingCleanupAt = null
      IncompleteDownloadCleanup.cancel(context, item.id)
      persist(item, force = true)
    }
    clientEventEmitter.onDownloadItem(item)
    notifyQueueChanged()
    return true
  }

  @Synchronized
  fun resumeWork() {
    checkUpdateDownloadQueue()
    notifyQueueChanged()
  }

  @Synchronized
  fun cancelAll() {
    activeCalls.values.forEach(InternalDownloadManager.DownloadHandle::cancel)
    activeCalls.clear()
    downloadItemQueue.forEach { item ->
      item.downloadItemParts.forEach { part ->
        File(part.destinationPath).delete()
        if (part.moved && !part.reusedExistingFile && part.isInternalStorage) {
          File(part.finalDestinationPath).delete()
        } else if (part.moved && !part.reusedExistingFile) {
          part.completedDestinationUri?.let { uri ->
            try {
              DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete()
            } catch (e: Exception) {
              Log.w(tag, "Could not delete cancelled SAF file ${part.filename}", e)
            }
          }
        }
      }
      IncompleteDownloadCleanup.cancel(context, item.id)
      DeviceManager.dbManager.removeDownloadItem(item.id)
    }
    currentDownloadItemParts.clear()
    reservations.clear()
    downloadItemQueue.clear()
    notifyQueueChanged()
  }

  @Synchronized
  fun hasWork(): Boolean =
          finalizingItems.isNotEmpty() || downloadItemQueue.any { item ->
            item.downloadItemParts.any { part ->
              (!part.moved && !part.failed) || part.isMoving
            }
          }

  @Synchronized
  private fun checkUpdateDownloadQueue() {
    downloadItemQueue.toList().forEach { item ->
      var slots = MAX_SIMULTANEOUS_DOWNLOADS - currentDownloadItemParts.size
      if (slots <= 0) return@forEach
      item.downloadItemParts
              .filter { part ->
                part.completed && !part.moved && !part.failed && !part.isMoving &&
                        part !in currentDownloadItemParts && File(part.destinationPath).exists() &&
                        !hasActiveDestinationConflict(part)
              }
              .take(slots)
              .forEach { part ->
                currentDownloadItemParts.add(part)
                part.downloadId = APP_MANAGED_DOWNLOAD_ID
              }
      slots = MAX_SIMULTANEOUS_DOWNLOADS - currentDownloadItemParts.size
      if (slots <= 0) return@forEach
      item.getNextDownloadItemParts(slots).forEach { part ->
        val existingFile = findSharedStorageFile(part)
        if (existingFile != null) {
          part.bytesDownloaded = existingFile.length()
          part.progress = 100L
          part.completedDestinationUri = existingFile.uri.toString()
          part.reusedExistingFile = true
          File(part.destinationPath).delete()
          completePart(item, part)
          clientEventEmitter.onDownloadItemPartUpdate(part)
          return@forEach
        }
        if (completeFromExistingInternalCover(item, part)) return@forEach
        if (hasActiveDestinationConflict(part)) {
          leaveQueued(item, part)
        } else if (part.fileSize <= 0L && currentDownloadItemParts.any { it.fileSize <= 0L }) {
          leaveQueued(item, part)
        } else if (tryReserve(part)) startDownload(item, part)
        else {
          part.waitingForSpace = true
          part.lastUpdateTime = System.currentTimeMillis()
          persist(item)
          clientEventEmitter.onDownloadItemPartUpdate(part)
        }
      }
    }
    if (hasWork()) startWatchingDownloads() else notifyQueueChanged()
  }

  private fun startDownload(item: DownloadItem, part: DownloadItemPart) {
    val stagingFile = File(part.destinationPath)
    stagingFile.parentFile?.mkdirs()
    part.downloadId = APP_MANAGED_DOWNLOAD_ID
    part.waitingForSpace = false
    part.lastUpdateTime = System.currentTimeMillis()
    currentDownloadItemParts.add(part)
    persist(item, force = true)
    val activeConfig = DeviceManager.serverConnectionConfig
    val token =
            if (activeConfig?.id == item.serverConnectionConfigId) activeConfig.token
            else
                    DeviceManager.getServerConnectionConfig(item.serverConnectionConfigId)?.token
                            ?: DeviceManager.token
    val handle =
            InternalDownloadManager(
                            stagingFile,
                            part.fileSize,
                            object : InternalProgressCallback {
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
                            },
                            { hasAvailableSpace(part) }
                    ).download(serverUrl(item, part), token)
    if (part in currentDownloadItemParts && !part.completed && !part.failed) {
      activeCalls[part.id] = handle
    }
  }

  @Synchronized
  private fun startWatchingDownloads() {
    if (watcherRunning) return
    watcherRunning = true
    scope.launch {
      while (true) {
        val activeParts =
                synchronized(this@DownloadItemManager) { currentDownloadItemParts.toList() }
        activeParts.forEach(::handlePartUpdate)
        synchronized(this@DownloadItemManager) {
          checkUpdateDownloadQueue()
          if (!hasWork()) {
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
    val item =
            synchronized(this) { downloadItemQueue.find { it.id == part.downloadItemId } }
                    ?: run {
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

  @Synchronized
  private fun failOrRetry(item: DownloadItem, part: DownloadItemPart, reason: String) {
    removeActivePart(part)
    part.retryCount += 1
    reservations.remove(part.destinationPath)
    if (part.retryCount > MAX_RETRIES) {
      Log.e(tag, "$reason after $MAX_RETRIES retries: ${part.filename}")
      part.failed = true
      part.completed = false
      part.downloadId = null
      item.terminalFailureAt = item.terminalFailureAt ?: System.currentTimeMillis()
      item.stagingCleanupAt = null
      persist(item, force = true)
      IncompleteDownloadCleanup.schedule(context, item)
      clientEventEmitter.onDownloadItemPartUpdate(part)
      notifyQueueChanged()
      return
    }
    part.failed = false
    part.completed = false
    part.downloadId = null
    part.isMoving = false
    persist(item, force = true)
    clientEventEmitter.onDownloadItemPartUpdate(part)
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
      if (finalFile.exists() && !finalFile.renameTo(backup))
              throw IllegalStateException("Could not protect existing file")
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
    val root =
            DocumentFile.fromTreeUri(context, Uri.parse(part.localFolderUrl))
                    ?: return failFinalization(item, part, "Could not resolve SAF destination")
    part.isMoving = true
    persist(item, force = true)
    scope.launch {
      try {
        if (!hasAvailableSpace(part))
                throw IllegalStateException("Insufficient storage for SAF copy")
        val folderKey = "${root.uri}/${part.finalDestinationSubfolder}"
        val folderLock = safFolderLocks.computeIfAbsent(folderKey) { Any() }
        val folder =
                synchronized(folderLock) { getOrCreateFolder(root, part.finalDestinationSubfolder) }
                        ?: throw IllegalStateException("Could not create SAF destination folder")
        val temporaryName = ".${part.filename}.${part.id.hashCode()}.part"
        folder.findFile(temporaryName)?.delete()
        val temporary =
                folder.createFile(mimeTypeFor(part), temporaryName)
                        ?: throw IllegalStateException("Could not create SAF temporary file")
        val staging = File(part.destinationPath)
        FileInputStream(staging).use { input ->
          context.contentResolver.openOutputStream(temporary.uri, "w")?.use { input.copyTo(it) }
                  ?: throw IllegalStateException("Could not open SAF output stream")
        }
        if (temporary.length() != staging.length())
                throw IllegalStateException("SAF copy size mismatch")
        val existing = findDocumentByFilename(folder, part)
        if (existing != null && !existing.delete())
                throw IllegalStateException("Could not replace existing file")
        if (!temporary.renameTo(part.filename))
                throw IllegalStateException("Could not finalize SAF temporary file")
        val destination =
                findDocumentByFilename(folder, part)
                        ?: throw IllegalStateException("Could not reopen finalized SAF file")
        if (destination.length() != staging.length())
                throw IllegalStateException("SAF final size mismatch")
        if (!staging.delete()) Log.w(tag, "Could not remove staging file ${staging.name}")
        part.completedDestinationUri = destination.uri.toString()
        completePart(item, part)
      } catch (e: Exception) {
        failFinalization(item, part, "SAF copy failed: ${e.message}")
      }
    }
  }

  @Synchronized
  private fun failFinalization(item: DownloadItem, part: DownloadItemPart, message: String) {
    Log.e(tag, message)
    part.isMoving = false
    part.failed = true
    failOrRetry(item, part, message)
  }

  @Synchronized
  private fun completePart(item: DownloadItem, part: DownloadItemPart) {
    part.moved = true
    part.completed = true
    part.failed = false
    part.isMoving = false
    reservations.remove(part.destinationPath)
    removeActivePart(part)
    persist(item, force = true)
    checkDownloadItemFinished(item)
  }

  @Synchronized
  private fun checkDownloadItemFinished(item: DownloadItem) {
    if (!item.isDownloadFinished || !finalizingItems.add(item.id)) return
    IncompleteDownloadCleanup.cancel(context, item.id)
    scope.launch {
      val scanLock = scanLocks.computeIfAbsent(scanDestinationKey(item)) { Any() }
      synchronized(scanLock) {
        folderScanner.scanDownloadItem(item) { scanResult ->
          val event =
                  JSObject().apply {
                    put("libraryItemId", item.id)
                    put("localFolderId", item.localFolder.id)
                    scanResult?.localLibraryItem?.let {
                      put("localLibraryItem", JSObject(jacksonMapper.writeValueAsString(it)))
                    }
                    scanResult?.localMediaProgress?.let {
                      put("localMediaProgress", JSObject(jacksonMapper.writeValueAsString(it)))
                    }
                  }
          clientEventEmitter.onDownloadItemComplete(event)
          synchronized(this@DownloadItemManager) {
            finalizingItems.remove(item.id)
            downloadItemQueue.remove(item)
            DeviceManager.dbManager.removeDownloadItem(item.id)
            notifyQueueChanged()
          }
        }
      }
    }
  }

  private fun tryReserve(part: DownloadItemPart): Boolean {
    val staging = File(part.destinationPath)
    staging.parentFile?.mkdirs()
    val expectedSize = if (part.fileSize > 0L) part.fileSize else UNKNOWN_PART_RESERVATION_BYTES
    val remaining =
            (expectedSize - (staging.takeIf(File::exists)?.length() ?: 0L)).coerceAtLeast(0L)
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
          if (file.absolutePath.startsWith(context.filesDir.absolutePath)) "internal"
          else "external"

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

  private fun notifyQueueChanged() {
    clientEventEmitter.onQueueChanged(hasWork())
  }

  fun destroy() {
    activeCalls.values.forEach(InternalDownloadManager.DownloadHandle::cancel)
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

  private fun findSharedStorageFile(part: DownloadItemPart): DocumentFile? {
    if (part.isInternalStorage) return null
    val root = DocumentFile.fromTreeUri(context, Uri.parse(part.localFolderUrl)) ?: return null
    var folder = root
    part.finalDestinationSubfolder.split('/').filter { it.isNotBlank() }.forEach { segment ->
      if (segment == "." || segment == "..") return null
      folder = folder.findFile(segment) ?: return null
    }
    val file = findDocumentByFilename(folder, part) ?: return null
    if (!file.isFile) return null
    if (part.fileSize > 0L && file.length() != part.fileSize) return null
    if (part.fileSize <= 0L && file.length() <= 0L) return null
    return file
  }

  private fun completeFromExistingInternalCover(
          item: DownloadItem,
          part: DownloadItemPart
  ): Boolean {
    if (!part.isInternalStorage || !part.serverPath.endsWith("/cover")) return false
    val file = File(part.finalDestinationPath)
    if (!file.isFile || file.length() <= 0L) return false
    if (part.fileSize > 0L && file.length() != part.fileSize) return false
    part.bytesDownloaded = file.length()
    part.progress = 100L
    part.reusedExistingFile = true
    File(part.destinationPath).delete()
    completePart(item, part)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    return true
  }

  private fun hasActiveDestinationConflict(part: DownloadItemPart): Boolean =
          currentDownloadItemParts.any { activePart ->
            activePart !== part && activePart.localFolderId == part.localFolderId &&
                    activePart.finalDestinationPath == part.finalDestinationPath
          }

  private fun leaveQueued(item: DownloadItem, part: DownloadItemPart) {
    if (!part.waitingForSpace) return
    part.waitingForSpace = false
    part.downloadId = null
    persist(item)
    clientEventEmitter.onDownloadItemPartUpdate(part)
  }

  private fun scanDestinationKey(item: DownloadItem): String =
          "${item.localFolder.id}:${item.itemFolderPath}"

  private fun finalizedFileExists(part: DownloadItemPart): Boolean {
    if (part.isInternalStorage) {
      val file = File(part.finalDestinationPath)
      return file.isFile &&
              if (part.fileSize > 0L) file.length() == part.fileSize else file.length() > 0L
    }
    part.completedDestinationUri?.let { uri ->
      try {
        val file = DocumentFile.fromSingleUri(context, Uri.parse(uri))
        if (file?.isFile == true &&
                        (part.fileSize <= 0L || file.length() == part.fileSize)) return true
      } catch (e: Exception) {
        Log.w(tag, "Could not validate SAF file ${part.filename}", e)
      }
    }
    return findSharedStorageFile(part) != null
  }

  /** Resets an unmoved part when recovery crosses a service-session boundary. */
  private fun resetPartForFreshDownload(part: DownloadItemPart): Boolean {
    val stagingFile = File(part.destinationPath)
    if (stagingFile.exists() && !stagingFile.delete()) {
      Log.e(tag, "Could not delete staging file ${part.filename}")
      part.failed = true
      return false
    }
    part.completed = false
    part.bytesDownloaded = 0L
    part.progress = 0L
    part.failed = false
    part.isMoving = false
    part.downloadId = null
    part.retryCount = 0
    part.waitingForSpace = false
    part.reusedExistingFile = false
    return true
  }

  private fun findDocumentByFilename(folder: DocumentFile, part: DownloadItemPart): DocumentFile? {
    folder.findFile(part.filename)?.let { return it }
    val expectedBaseName = part.filename.substringBeforeLast('.')
    return folder.listFiles().firstOrNull { document ->
      document.name == part.filename ||
              document.fullName == part.filename ||
              (part.audioTrack != null && document.isFile &&
                      (document.name ?: "").substringBeforeLast('.') == expectedBaseName) ||
              (part.audioTrack != null && document.isFile &&
                      document.fullName.substringBeforeLast('.') == expectedBaseName)
    }
  }

  private fun mimeTypeFor(part: DownloadItemPart): String =
          part.audioTrack?.mimeType
                  ?: when (part.ebookFile?.ebookFormat?.lowercase()) {
                    "epub" -> "application/epub+zip"
                    "pdf" -> "application/pdf"
                    else -> "image/jpeg"
                  }

  private fun serverUrl(item: DownloadItem, part: DownloadItemPart): String {
    val rawCover = if (part.serverPath.endsWith("/cover")) "?raw=1" else ""
    return "${item.serverAddress}${part.serverPath}$rawCover"
  }

  private companion object {
    const val APP_MANAGED_DOWNLOAD_ID = -1L
    const val MAX_SIMULTANEOUS_DOWNLOADS = 3
    const val WATCH_INTERVAL_MS = 1_000L
    const val STALL_TIMEOUT_MS = 60_000L
    const val MAX_RETRIES = 5
    const val PERSIST_INTERVAL_MS = 2_000L
    const val MIN_FREE_SPACE_BYTES = 100L * 1024L * 1024L
    const val UNKNOWN_PART_RESERVATION_BYTES = 100L * 1024L * 1024L
  }
}
