package com.audiobookshelf.app.managers

import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.audiobookshelf.app.MainActivity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call

/** Owns the Android download queue and writes all bytes to app-owned staging files. */
class DownloadItemManager(
        private val folderScanner: FolderScanner,
        private val mainActivity: MainActivity,
        private val clientEventEmitter: DownloadEventEmitter
) {
  private val tag = "DownloadItemManager"
  private val maxSimultaneousDownloads = 3
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val activeCalls = ConcurrentHashMap<String, Call>()
  private var watcherRunning = false
  private val jacksonMapper =
          jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  var downloadItemQueue: MutableList<DownloadItem> = mutableListOf()
  var currentDownloadItemParts: MutableList<DownloadItemPart> = mutableListOf()

  interface DownloadEventEmitter {
    fun onDownloadItem(downloadItem: DownloadItem)
    fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart)
    fun onDownloadItemComplete(jsobj: JSObject)
  }

  interface InternalProgressCallback {
    fun onProgress(totalBytesWritten: Long, progress: Long)
    fun onComplete(failed: Boolean)
  }

  init {
    DeviceManager.dbManager.clearLegacyDownloadQueueOnce()
  }

  @Synchronized
  fun addDownloadItem(downloadItem: DownloadItem) {
    DeviceManager.dbManager.saveDownloadItem(downloadItem)
    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    checkUpdateDownloadQueue()
  }

  @Synchronized
  private fun checkUpdateDownloadQueue() {
    for (downloadItem in downloadItemQueue.toList()) {
      val availableSlots = maxSimultaneousDownloads - currentDownloadItemParts.size
      if (availableSlots <= 0) break
      downloadItem.getNextDownloadItemParts(availableSlots).forEach(::startDownload)
    }
    if (currentDownloadItemParts.isNotEmpty()) startWatchingDownloads()
  }

  private fun startDownload(part: DownloadItemPart) {
    val stagingFile = File(part.destinationPath)
    stagingFile.parentFile?.mkdirs()
    part.downloadId = APP_MANAGED_DOWNLOAD_ID
    part.lastUpdateTime = System.currentTimeMillis()
    currentDownloadItemParts.add(part)
    val callback =
            object : InternalProgressCallback {
              override fun onProgress(totalBytesWritten: Long, progress: Long) {
                synchronized(this@DownloadItemManager) {
                  part.bytesDownloaded = totalBytesWritten
                  part.progress = progress
                  part.lastUpdateTime = System.currentTimeMillis()
                }
              }

              override fun onComplete(failed: Boolean) {
                synchronized(this@DownloadItemManager) {
                  part.failed = failed
                  part.completed = true
                  part.lastUpdateTime = System.currentTimeMillis()
                  activeCalls.remove(part.id)
                }
              }
            }
    activeCalls[part.id] = InternalDownloadManager(stagingFile, part.fileSize, callback).download(part.serverUrl)
  }

  @Synchronized
  private fun startWatchingDownloads() {
    if (watcherRunning) return
    watcherRunning = true
    scope.launch {
      while (true) {
        val activeParts = synchronized(this@DownloadItemManager) { currentDownloadItemParts.toList() }
        if (activeParts.isEmpty()) break
        activeParts.forEach(::handlePartUpdate)
        delay(WATCH_INTERVAL_MS)
        synchronized(this@DownloadItemManager) { checkUpdateDownloadQueue() }
      }
      synchronized(this@DownloadItemManager) { watcherRunning = false }
    }
  }

  private fun handlePartUpdate(part: DownloadItemPart) {
    clientEventEmitter.onDownloadItemPartUpdate(part)
    if (!part.completed) {
      val lastUpdate = part.lastUpdateTime ?: return
      if (System.currentTimeMillis() - lastUpdate > STALL_TIMEOUT_MS) {
        Log.e(tag, "Download stalled: ${part.filename}")
        activeCalls.remove(part.id)?.cancel()
        synchronized(this) {
          part.failed = true
          part.completed = true
        }
      }
      return
    }

    val item = synchronized(this) { downloadItemQueue.find { it.id == part.downloadItemId } }
    if (item == null) {
      removeActivePart(part)
      return
    }
    if (part.failed) {
      removeActivePart(part)
      return
    }
    if (part.isInternalStorage) finalizeInternalFile(item, part) else moveDownloadedFile(item, part)
  }

  private fun finalizeInternalFile(item: DownloadItem, part: DownloadItemPart) {
    if (part.moved || part.isMoving) return
    part.isMoving = true
    val stagingFile = File(part.destinationPath)
    val finalFile = File(part.finalDestinationPath)
    finalFile.parentFile?.mkdirs()
    if (finalFile.exists() && !finalFile.delete()) {
      failFinalization(item, part, "Could not replace existing internal file")
      return
    }
    if (!stagingFile.renameTo(finalFile)) {
      failFinalization(item, part, "Could not finalize internal staging file")
      return
    }
    part.moved = true
    part.isMoving = false
    removeActivePart(part)
    checkDownloadItemFinished(item)
  }

  private fun moveDownloadedFile(item: DownloadItem, part: DownloadItemPart) {
    if (part.moved || part.isMoving) return
    val destinationRoot = DocumentFile.fromTreeUri(mainActivity, Uri.parse(part.localFolderUrl))
    if (destinationRoot == null) {
      failFinalization(item, part, "Could not resolve SAF destination")
      return
    }
    part.isMoving = true
    scope.launch {
      try {
        val destinationFolder = getOrCreateFolder(destinationRoot, part.finalDestinationSubfolder)
                ?: throw IllegalStateException("Could not create SAF destination folder")
        destinationFolder.findFile(part.filename)?.let { existing ->
          if (!existing.delete()) throw IllegalStateException("Could not replace ${part.filename}")
        }
        val destinationFile = destinationFolder.createFile(mimeTypeFor(part), part.filename)
                ?: throw IllegalStateException("Could not create ${part.filename}")
        val stagingFile = File(part.destinationPath)
        FileInputStream(stagingFile).use { input ->
          mainActivity.contentResolver.openOutputStream(destinationFile.uri, "w")?.use { output ->
            input.copyTo(output)
          } ?: throw IllegalStateException("Could not open SAF output stream")
        }
        if (destinationFile.length() != stagingFile.length()) {
          destinationFile.delete()
          throw IllegalStateException("SAF copy size mismatch for ${part.filename}")
        }
        stagingFile.delete()
        part.completedDestinationUri = destinationFile.uri.toString()
        part.moved = true
        part.isMoving = false
        removeActivePart(part)
        checkDownloadItemFinished(item)
      } catch (e: Exception) {
        failFinalization(item, part, "SAF copy failed: ${e.message}")
      }
    }
  }

  private fun getOrCreateFolder(root: DocumentFile, relativePath: String): DocumentFile? {
    var current = root
    relativePath.split('/').filter { it.isNotBlank() }.forEach { segment ->
      if (segment == "." || segment == "..") return null
      current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
    }
    return current
  }

  private fun mimeTypeFor(part: DownloadItemPart): String {
    return part.audioTrack?.mimeType
            ?: when (part.ebookFile?.ebookFormat?.lowercase()) {
              "epub" -> "application/epub+zip"
              "pdf" -> "application/pdf"
              else -> "image/jpeg"
            }
  }

  private fun failFinalization(item: DownloadItem, part: DownloadItemPart, message: String) {
    Log.e(tag, message)
    part.failed = true
    part.isMoving = false
    part.completed = true
    removeActivePart(part)
  }

  @Synchronized
  private fun removeActivePart(part: DownloadItemPart) {
    activeCalls.remove(part.id)
    currentDownloadItemParts.remove(part)
  }

  private fun checkDownloadItemFinished(downloadItem: DownloadItem) {
    if (!downloadItem.isDownloadFinished) return
    scope.launch {
      folderScanner.scanDownloadItem(downloadItem) { scanResult ->
        val event =
                JSObject().apply {
                  put("libraryItemId", downloadItem.id)
                  put("localFolderId", downloadItem.localFolder.id)
                  scanResult?.localLibraryItem?.let {
                    put("localLibraryItem", JSObject(jacksonMapper.writeValueAsString(it)))
                  }
                  scanResult?.localMediaProgress?.let {
                    put("localMediaProgress", JSObject(jacksonMapper.writeValueAsString(it)))
                  }
                }
        clientEventEmitter.onDownloadItemComplete(event)
        synchronized(this@DownloadItemManager) {
          downloadItemQueue.remove(downloadItem)
          DeviceManager.dbManager.removeDownloadItem(downloadItem.id)
        }
      }
    }
  }

  fun destroy() {
    activeCalls.values.forEach(Call::cancel)
    activeCalls.clear()
    scope.cancel()
  }

  private companion object {
    const val APP_MANAGED_DOWNLOAD_ID = -1L
    const val WATCH_INTERVAL_MS = 500L
    const val STALL_TIMEOUT_MS = 60_000L
  }
}
