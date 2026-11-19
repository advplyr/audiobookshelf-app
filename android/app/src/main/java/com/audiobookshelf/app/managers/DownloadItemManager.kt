package com.audiobookshelf.app.managers

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.fullName
import com.audiobookshelf.app.data.DownloadUsingCellularSetting
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages the process-owned queue for app-managed downloads.
 *
 * Transfers resume from their staging file after any interruption (network loss, stall, app
 * restart) and keep retrying with backoff until they finish, the user cancels, or the server
 * reports an error that retrying cannot fix.
 */
class DownloadItemManager(
        private val folderScanner: FolderScanner,
        private val context: Context,
        private var clientEventEmitter: DownloadEventEmitter
) {
  private val tag = "DownloadItemManager"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val activeCalls = ConcurrentHashMap<String, InternalDownloadManager.DownloadHandle>()
  /** Attempt id per active part; callbacks from any other attempt are stale and ignored. */
  private val activeAttempts = ConcurrentHashMap<String, Long>()
  private val attemptStartBytes = ConcurrentHashMap<String, Long>()
  private var lastAttemptId = 0L
  private val safFolderLocks = ConcurrentHashMap<String, Any>()
  private val scanLocks = ConcurrentHashMap<String, Any>()
  private val reservations = mutableMapOf<String, Long>()
  private val lastPersistTime = mutableMapOf<String, Long>()
  private val finalizingItems = mutableSetOf<String>()
  private val refreshingServerIds = mutableSetOf<String>()
  // Only needed after a 401; it opens the keystore, so create it on first use
  private val apiHandler by lazy { ApiHandler(context) }
  private var watcherRunning = false
  private var paused = false
  private var lastNeedsCpu: Boolean? = null
  private var lastCellularApproval: Int? = null
  private val jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  var downloadItemQueue: MutableList<DownloadItem> = mutableListOf()
    private set
  var currentDownloadItemParts: MutableList<DownloadItemPart> = mutableListOf()
    private set

  // Declared after every field onNetworkChanged reads: its callback can fire on the
  // ConnectivityThread as soon as it registers, before the constructor finishes.
  private val networkMonitor = DownloadNetworkMonitor(context) { onNetworkChanged() }
  private var wasConnected = networkMonitor.isConnected

  interface DownloadEventEmitter {
    fun onDownloadItem(downloadItem: DownloadItem)
    fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart)
    fun onDownloadItemComplete(jsobj: JSObject)
    fun onQueueChanged(hasWork: Boolean)
    /** True while a transfer is running or due soon, so the device must stay awake. */
    fun onNeedsCpuChanged(needsCpu: Boolean) {}
    /** Number of Wi-Fi preferred downloads paused on a metered network, waiting for approval. */
    fun onCellularApprovalChanged(waitingItems: Int) {}
  }

  interface InternalProgressCallback {
    fun onSizeResolved(totalBytes: Long)
    fun onValidatorResolved(etag: String?, lastModified: String?)
    fun onProgress(totalBytesWritten: Long, progress: Long)
    fun onComplete(failed: Boolean)
    fun onPermanentFailure(reason: String)
    fun onAuthError()
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
          AbsLogger.error(tag, "Finalized file is missing; resetting ${part.filename}")
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
      item.downloadItemParts.filter { !it.moved }.forEach(::prepareForResume)
      if (item.terminalFailureAt != null) {
        item.downloadItemParts.filter { !it.moved }.forEach { it.failed = true }
      }
      downloadItemQueue.add(item)
      persist(item, force = true)
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
    if (downloadItem.networkMode == null) downloadItem.networkMode = defaultNetworkMode()
    persist(downloadItem, force = true)
    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    notifyQueueChanged()
  }

  /** Retries a failed or waiting item immediately, keeping any bytes already downloaded. */
  @Synchronized
  fun retryDownloadItem(downloadItemId: String, networkMode: String?): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    if (item.downloadItemParts.any { it in currentDownloadItemParts }) return false
    if (item.isDownloadFinished || item.id in finalizingItems) return false
    synchronized(IncompleteDownloadCleanup) {
      item.downloadItemParts.filter { !it.moved }.forEach(::prepareForResume)
      item.networkMode = networkMode ?: defaultNetworkMode()
      item.terminalFailureAt = null
      item.stagingCleanupAt = null
      IncompleteDownloadCleanup.cancel(context, item.id)
      persist(item, force = true)
    }
    clientEventEmitter.onDownloadItem(item)
    notifyQueueChanged()
    return true
  }

  /** Skips the retry backoff for an item's waiting parts. Returns false if nothing was backing off. */
  @Synchronized
  fun retryNow(downloadItemId: String): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    val now = System.currentTimeMillis()
    val backingOff =
            item.downloadItemParts.filter { !it.moved && !it.failed && it.downloadId == null && it.nextAttemptAt > now }
    if (backingOff.isEmpty()) return false
    AbsLogger.info(tag, "Retrying ${item.id} now, skipping backoff")
    backingOff.forEach { part ->
      part.nextAttemptAt = 0L
      clientEventEmitter.onDownloadItemPartUpdate(part)
    }
    persist(item, force = true)
    checkUpdateDownloadQueue()
    return true
  }

  @Synchronized
  fun resumeWork() {
    paused = false
    checkUpdateDownloadQueue()
    notifyQueueChanged()
  }

  /**
   * Stops transfers without discarding progress, e.g. when Android ends the foreground service.
   * Staging files are kept, so [resumeWork] continues from the bytes already on disk.
   */
  @Synchronized
  fun pause() {
    paused = true
    currentDownloadItemParts.toList().forEach { part ->
      if (part.isMoving) return@forEach
      activeCalls[part.id]?.cancel()
      removeActivePart(part)
      reservations.remove(part.destinationPath)
      part.downloadId = null
      part.lastUpdateTime = System.currentTimeMillis()
      downloadItemQueue.find { it.id == part.downloadItemId }?.let { persist(it, force = true) }
      clientEventEmitter.onDownloadItemPartUpdate(part)
    }
    AbsLogger.info(tag, "Downloads paused")
    updateNeedsCpu()
  }

  /** Cancels one download item and removes everything it wrote. */
  @Synchronized
  fun cancelDownloadItem(downloadItemId: String): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    if (item.id in finalizingItems) return false
    AbsLogger.info(tag, "Cancelling download ${item.id}")
    discardItem(item)
    notifyQueueChanged()
    updateNeedsCpu()
    updateCellularApproval()
    return true
  }

  @Synchronized
  fun cancelAll() {
    AbsLogger.info(tag, "Cancelling all downloads")
    downloadItemQueue.toList().forEach { item ->
      if (item.id !in finalizingItems) discardItem(item)
    }
    reservations.clear()
    notifyQueueChanged()
    updateNeedsCpu()
    updateCellularApproval()
  }

  private fun discardItem(item: DownloadItem) {
    item.downloadItemParts.forEach { part ->
      if (part in currentDownloadItemParts) {
        activeCalls[part.id]?.cancel()
        removeActivePart(part)
      }
      reservations.remove(part.destinationPath)
      File(part.destinationPath).delete()
      if (part.moved && !part.reusedExistingFile) deleteFinalizedFile(part)
    }
    downloadItemQueue.removeAll { it === item }
    lastPersistTime.remove(item.id)
    IncompleteDownloadCleanup.cancel(context, item.id)
    DeviceManager.dbManager.removeDownloadItem(item.id)
  }

  private fun deleteFinalizedFile(part: DownloadItemPart) {
    if (part.isInternalStorage) {
      File(part.finalDestinationPath).delete()
      return
    }
    part.completedDestinationUri?.let { uri ->
      try {
        DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete()
      } catch (e: Exception) {
        AbsLogger.error(tag, "Could not delete cancelled SAF file ${part.filename}: ${e.message}")
      }
    }
  }

  private fun isQueued(item: DownloadItem): Boolean = downloadItemQueue.any { it === item }

  @Synchronized
  fun hasWork(): Boolean =
          finalizingItems.isNotEmpty() ||
                  downloadItemQueue.any { item ->
                    item.downloadItemParts.any { part ->
                      (!part.moved && !part.failed) || part.isMoving
                    }
                  }

  /**
   * True while a transfer runs or is due within [CPU_HOLD_BEFORE_RETRY_MS]. Waiting on the
   * network or storage, or a long retry backoff, needs no CPU: the network callback wakes the
   * queue, and a long backoff simply retries on the device's next wake instead of holding a wake
   * lock for minutes against an unreachable server.
   */
  @Synchronized
  private fun needsCpu(): Boolean {
    if (paused) return false
    if (currentDownloadItemParts.isNotEmpty() || finalizingItems.isNotEmpty()) return true
    val now = System.currentTimeMillis()
    return downloadItemQueue.any { item ->
      item.downloadItemParts.any { part ->
        !part.moved &&
                !part.failed &&
                !part.waitingForNetwork &&
                !part.waitingForWifi &&
                !part.waitingForSpace &&
                part.nextAttemptAt - now <= CPU_HOLD_BEFORE_RETRY_MS
      }
    }
  }

  /** Re-sends the current state, e.g. to a freshly started service. */
  @Synchronized
  fun refreshNeedsCpu() {
    lastNeedsCpu = null
    lastCellularApproval = null
    updateNeedsCpu()
    updateCellularApproval()
  }

  @Synchronized
  private fun updateNeedsCpu() {
    val needsCpu = needsCpu()
    if (needsCpu == lastNeedsCpu) return
    lastNeedsCpu = needsCpu
    clientEventEmitter.onNeedsCpuChanged(needsCpu)
  }

  /**
   * Mode for a download the user just started without choosing one. The frontend only starts a
   * download on a metered network once the policy or the user allowed it.
   */
  private fun defaultNetworkMode(): String = DownloadNetworkPolicy.defaultMode(networkMonitor.isMetered)

  /** Lets a download use metered networks, from the notification (all paused) or the UI (one item). */
  @Synchronized
  fun allowCellular(downloadItemId: String?) {
    downloadItemQueue
            .filter {
              if (downloadItemId != null) it.id == downloadItemId
              else it.networkMode != DownloadItem.NETWORK_MODE_WIFI_ONLY && it.networkMode != DownloadItem.NETWORK_MODE_CELLULAR
            }
            .forEach { item ->
              AbsLogger.info(tag, "Allowing mobile data for ${item.id}")
              item.networkMode = DownloadItem.NETWORK_MODE_CELLULAR
              item.downloadItemParts.forEach { if (!it.moved && !it.failed) it.nextAttemptAt = 0L }
              persist(item, force = true)
            }
    checkUpdateDownloadQueue()
  }

  private fun cellularSetting(): DownloadUsingCellularSetting =
          try {
            DeviceManager.deviceData.deviceSettings?.downloadUsingCellular
          } catch (e: Exception) {
            null
          }
                  ?: DownloadUsingCellularSetting.ALWAYS

  private fun networkBlock(item: DownloadItem): DownloadNetworkPolicy.Block? =
          DownloadNetworkPolicy.block(
                  cellularSetting(), networkMonitor.isConnected, networkMonitor.isMetered, item.networkMode)

  private fun hasPendingParts(item: DownloadItem): Boolean =
          item.downloadItemParts.any { !it.moved && !it.failed && !it.completed }

  @Synchronized
  private fun updateCellularApproval() {
    val setting = cellularSetting()
    val waitingItems =
            downloadItemQueue.count {
              hasPendingParts(it) &&
                      DownloadNetworkPolicy.needsCellularApproval(
                              setting, networkMonitor.isConnected, networkMonitor.isMetered, it.networkMode)
            }
    if (waitingItems == lastCellularApproval) return
    lastCellularApproval = waitingItems
    clientEventEmitter.onCellularApprovalChanged(waitingItems)
  }

  /** Marks queued parts as waiting on the network so the UI and notification can say why. */
  private fun markNetworkWait(item: DownloadItem, block: DownloadNetworkPolicy.Block?) {
    item.downloadItemParts
            .filter { !it.moved && !it.failed && !it.completed && it !in currentDownloadItemParts }
            .forEach { part ->
              val waitingForNetwork = block == DownloadNetworkPolicy.Block.OFFLINE
              val waitingForWifi = block == DownloadNetworkPolicy.Block.WIFI_REQUIRED
              if (part.waitingForNetwork == waitingForNetwork && part.waitingForWifi == waitingForWifi)
                      return@forEach
              part.waitingForNetwork = waitingForNetwork
              part.waitingForWifi = waitingForWifi
              clientEventEmitter.onDownloadItemPartUpdate(part)
            }
  }

  /**
   * Stops transfers the current network may not carry, and retries everything immediately once a
   * usable network returns. Neither counts as a failed attempt.
   */
  @Synchronized
  private fun onNetworkChanged() {
    val now = System.currentTimeMillis()
    // Failures while offline were caused by the outage, so retry those now. Other changes
    // (metered flips, handovers) keep the backoff so a flaky link cannot hammer the server.
    val reconnected = networkMonitor.isConnected && !wasConnected
    wasConnected = networkMonitor.isConnected
    downloadItemQueue.toList().forEach { item ->
      val block = networkBlock(item)
      if (block == null) {
        if (reconnected) {
          item.downloadItemParts
                  .filter { !it.moved && !it.failed && it.nextAttemptAt > now }
                  .forEach {
                    it.nextAttemptAt = 0L
                    clientEventEmitter.onDownloadItemPartUpdate(it)
                  }
        }
        return@forEach
      }
      item.downloadItemParts.filter { it in currentDownloadItemParts && !it.completed && !it.isMoving }.forEach { part ->
        AbsLogger.info(tag, "Pausing ${part.filename}: ${if (block == DownloadNetworkPolicy.Block.OFFLINE) "offline" else "Wi-Fi required"}")
        activeCalls[part.id]?.cancel()
        removeActivePart(part)
        reservations.remove(part.destinationPath)
        part.downloadId = null
        part.lastUpdateTime = now
        persist(item, force = true)
      }
      markNetworkWait(item, block)
    }
    checkUpdateDownloadQueue()
  }

  /**
   * Fills up to MAX_SIMULTANEOUS_DOWNLOADS transfer slots in queue order. Only running transfers
   * hold a slot: an item blocked by the network policy is skipped before it takes one, so items
   * allowed on the current network are never stuck behind items waiting for Wi-Fi.
   */
  @Synchronized
  private fun checkUpdateDownloadQueue() {
    if (paused) return
    val now = System.currentTimeMillis()
    downloadItemQueue.toList().forEach { item ->
      // Label every item, including those that will not get a slot this pass.
      val block = networkBlock(item)
      markNetworkWait(item, block)
      var slots = MAX_SIMULTANEOUS_DOWNLOADS - currentDownloadItemParts.size
      if (slots <= 0) return@forEach
      item.downloadItemParts
              .filter { part ->
                part.completed &&
                        !part.moved &&
                        !part.failed &&
                        !part.isMoving &&
                        part !in currentDownloadItemParts &&
                        File(part.destinationPath).exists() &&
                        !hasActiveDestinationConflict(part)
              }
              .take(slots)
              .forEach { part ->
                currentDownloadItemParts.add(part)
                part.downloadId = APP_MANAGED_DOWNLOAD_ID
              }
      if (block != null) return@forEach
      slots = MAX_SIMULTANEOUS_DOWNLOADS - currentDownloadItemParts.size
      if (slots <= 0) return@forEach
      item.getNextDownloadItemParts(slots, now).forEach { part ->
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
    updateNeedsCpu()
    updateCellularApproval()
    if (hasWork()) startWatchingDownloads() else notifyQueueChanged()
  }

  private fun startDownload(item: DownloadItem, part: DownloadItemPart) {
    val stagingFile = File(part.destinationPath)
    stagingFile.parentFile?.mkdirs()
    val attemptId = ++lastAttemptId
    fun isCurrent() = activeAttempts[part.id] == attemptId
    part.downloadId = APP_MANAGED_DOWNLOAD_ID
    part.waitingForSpace = false
    part.waitingForNetwork = false
    part.waitingForWifi = false
    part.lastUpdateTime = System.currentTimeMillis()
    currentDownloadItemParts.add(part)
    activeAttempts[part.id] = attemptId
    attemptStartBytes[part.id] = stagingFile.takeIf(File::exists)?.length() ?: 0L
    persist(item, force = true)
    AbsLogger.info(tag, "Starting download for ${part.filename}")
    val activeConfig = DeviceManager.serverConnectionConfig
    val token =
            if (activeConfig?.id == item.serverConnectionConfigId) activeConfig.token
            else
                    DeviceManager.getServerConnectionConfig(item.serverConnectionConfigId)?.token
                            ?: DeviceManager.token
    // Callbacks run on OkHttp threads and wait for this method's lock, so the handle is registered
    // before any of them can observe the attempt.
    val handle =
            InternalDownloadManager(
                            stagingFile,
                            part.fileSize,
                            part.etag,
                            part.lastModified,
                            object : InternalProgressCallback {
                              override fun onSizeResolved(totalBytes: Long) {
                                synchronized(this@DownloadItemManager) {
                                  if (!isCurrent() || totalBytes < 0L) return
                                  if (part.fileSize == totalBytes) return
                                  AbsLogger.info(
                                          tag,
                                          "Using server size $totalBytes instead of metadata size ${part.fileSize} for ${part.filename}"
                                  )
                                  part.fileSize = totalBytes
                                  part.lastUpdateTime = System.currentTimeMillis()
                                  persist(item, force = true)
                                  clientEventEmitter.onDownloadItemPartUpdate(part)
                                }
                              }

                              override fun onValidatorResolved(etag: String?, lastModified: String?) {
                                synchronized(this@DownloadItemManager) {
                                  if (!isCurrent()) return
                                  if (part.etag == etag && part.lastModified == lastModified) return
                                  part.etag = etag
                                  part.lastModified = lastModified
                                  persist(item, force = true)
                                }
                              }

                              override fun onProgress(totalBytesWritten: Long, progress: Long) {
                                synchronized(this@DownloadItemManager) {
                                  if (!isCurrent()) return
                                  part.bytesDownloaded = totalBytesWritten
                                  part.progress = progress
                                  part.lastUpdateTime = System.currentTimeMillis()
                                  persist(item)
                                }
                              }

                              override fun onComplete(failed: Boolean) {
                                synchronized(this@DownloadItemManager) {
                                  if (!isCurrent()) return
                                  part.failed = failed
                                  part.completed = !failed
                                  part.lastUpdateTime = System.currentTimeMillis()
                                  activeCalls.remove(part.id)
                                  persist(item, force = true)
                                }
                              }

                              override fun onPermanentFailure(reason: String) {
                                synchronized(this@DownloadItemManager) {
                                  if (!isCurrent()) return
                                  markTerminalFailure(item, part, reason)
                                }
                              }

                              override fun onAuthError() {
                                synchronized(this@DownloadItemManager) {
                                  if (!isCurrent()) return
                                  handleAuthError(item, part)
                                }
                              }
                            },
                            { hasAvailableSpace(part) }
                    )
                    .download(serverUrl(item, part), token)
    if (isCurrent() && !part.completed && !part.failed) {
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
          if (paused || !hasWork()) {
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
        AbsLogger.error(tag, "Download stalled: ${part.filename}")
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

  /**
   * Schedules another attempt with exponential backoff. Transfer failures never become terminal;
   * the backoff resets whenever an attempt made progress, and failures while offline are not
   * counted. Finalization failures (moving the finished file into place) are capped, since they
   * usually mean the destination is unusable.
   */
  @Synchronized
  private fun failOrRetry(
          item: DownloadItem,
          part: DownloadItemPart,
          reason: String,
          isFinalizationFailure: Boolean = false
  ) {
    val startBytes = attemptStartBytes[part.id]
    val madeProgress = startBytes != null && part.bytesDownloaded > startBytes
    removeActivePart(part)
    reservations.remove(part.destinationPath)
    if (!isQueued(item)) return
    if (isFinalizationFailure) {
      part.finalizeFailureCount += 1
      if (part.finalizeFailureCount > MAX_FINALIZE_RETRIES) {
        markTerminalFailure(item, part, "$reason after $MAX_FINALIZE_RETRIES retries")
        return
      }
    }
    if (madeProgress) part.retryCount = 0
    if (networkMonitor.isConnected) part.retryCount += 1
    val delayMs = retryDelayMs(part.retryCount)
    part.nextAttemptAt = System.currentTimeMillis() + delayMs
    AbsLogger.info(tag, "$reason: ${part.filename}; retrying in ${delayMs / 1000}s (attempt ${part.retryCount})")
    part.failed = false
    part.completed = false
    part.downloadId = null
    part.isMoving = false
    persist(item, force = true)
    clientEventEmitter.onDownloadItemPartUpdate(part)
  }

  private fun retryDelayMs(retryCount: Int): Long {
    if (retryCount <= 0) return 0L
    val exponential = RETRY_BASE_DELAY_MS shl (retryCount - 1).coerceAtMost(16)
    val capped = exponential.coerceAtMost(RETRY_MAX_DELAY_MS)
    // Jitter keeps parallel parts from retrying in lockstep.
    return (capped * Random.nextDouble(0.8, 1.2)).toLong()
  }

  /** A 401 refreshes the token for this queued item's server without consuming transfer retries. */
  @Synchronized
  private fun handleAuthError(item: DownloadItem, part: DownloadItemPart) {
    removeActivePart(part)
    reservations.remove(part.destinationPath)
    part.downloadId = null
    part.isMoving = false
    part.failed = false
    part.completed = false
    part.authRetryCount += 1
    part.lastUpdateTime = System.currentTimeMillis()
    if (part.authRetryCount > MAX_AUTH_RETRIES) {
      markTerminalFailure(item, part, "Unauthorized after $MAX_AUTH_RETRIES token refresh attempts")
      return
    }

    AbsLogger.info(
            tag,
            "Refreshing token after 401 for ${part.filename} (attempt ${part.authRetryCount})"
    )
    persist(item, force = true)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    refreshTokenThenResume(item.serverConnectionConfigId)
  }

  private fun refreshTokenThenResume(serverConnectionConfigId: String) {
    if (!refreshingServerIds.add(serverConnectionConfigId)) return
    apiHandler.refreshAuthTokens(serverConnectionConfigId) { result ->
      synchronized(this@DownloadItemManager) {
        refreshingServerIds.remove(serverConnectionConfigId)
        when (result) {
          is ApiHandler.RefreshResult.Success -> {
            AbsLogger.info(
                    tag,
                    "Token refresh succeeded; resuming downloads for $serverConnectionConfigId"
            )
            checkUpdateDownloadQueue()
          }
          ApiHandler.RefreshResult.Rejected -> failParkedAuthParts(serverConnectionConfigId)
          ApiHandler.RefreshResult.Transient -> {
            AbsLogger.info(
                    tag,
                    "Token refresh could not be completed; retrying downloads for $serverConnectionConfigId later"
            )
            deferParkedAuthParts(serverConnectionConfigId)
            checkUpdateDownloadQueue()
          }
        }
      }
    }
  }

  private fun parkedAuthParts(serverConnectionConfigId: String): List<Pair<DownloadItem, DownloadItemPart>> =
          downloadItemQueue.filter { it.serverConnectionConfigId == serverConnectionConfigId }.flatMap { item ->
            item.downloadItemParts
                    .filter {
                      it.authRetryCount > 0 &&
                              !it.completed &&
                              !it.failed &&
                              it.downloadId == null &&
                              it !in currentDownloadItemParts
                    }
                    .map { item to it }
          }

  @Synchronized
  private fun failParkedAuthParts(serverConnectionConfigId: String) {
    parkedAuthParts(serverConnectionConfigId).forEach { (item, part) ->
      markTerminalFailure(item, part, "Unable to refresh download authorization")
    }
  }

  /** A refresh that failed for network reasons says nothing about the token, so back off and try again. */
  @Synchronized
  private fun deferParkedAuthParts(serverConnectionConfigId: String) {
    parkedAuthParts(serverConnectionConfigId).forEach { (item, part) ->
      part.authRetryCount = (part.authRetryCount - 1).coerceAtLeast(0)
      if (networkMonitor.isConnected) part.retryCount += 1
      part.nextAttemptAt = System.currentTimeMillis() + retryDelayMs(part.retryCount)
      persist(item, force = true)
    }
  }

  @Synchronized
  private fun markTerminalFailure(item: DownloadItem, part: DownloadItemPart, reason: String) {
    AbsLogger.error(tag, "$reason: ${part.filename}")
    removeActivePart(part)
    reservations.remove(part.destinationPath)
    if (!isQueued(item)) return
    part.failed = true
    part.completed = false
    part.downloadId = null
    part.isMoving = false
    item.terminalFailureAt = item.terminalFailureAt ?: System.currentTimeMillis()
    item.stagingCleanupAt = null
    persist(item, force = true)
    IncompleteDownloadCleanup.schedule(context, item)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    notifyQueueChanged()
  }

  /** Runs under the manager lock so a cancel cannot land between the rename and completePart. */
  @Synchronized
  private fun finalizeInternalFile(item: DownloadItem, part: DownloadItemPart) {
    if (part.moved || part.isMoving || !isQueued(item)) return
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
      AbsLogger.info(tag, "Move completed for ${part.filename} to ${finalFile.absolutePath}")
      completePart(item, part)
    } catch (e: Exception) {
      part.isMoving = false
      part.failed = true
      failOrRetry(item, part, e.message ?: "Internal finalization failed", isFinalizationFailure = true)
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
        if (synchronized(this@DownloadItemManager) { !isQueued(item) }) {
          temporary.delete()
          return@launch
        }
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
        if (!staging.delete()) AbsLogger.error(tag, "Could not remove staging file ${staging.name}")
        part.completedDestinationUri = destination.uri.toString()
        AbsLogger.info(tag, "Move completed for ${part.filename} to ${destination.uri}")
        synchronized(this@DownloadItemManager) {
          // Cancelled while the copy was finishing: remove what the copy produced.
          if (!isQueued(item)) {
            destination.delete()
            return@launch
          }
          completePart(item, part)
        }
      } catch (e: Exception) {
        failFinalization(item, part, "SAF copy failed: ${e.message}")
      }
    }
  }

  @Synchronized
  private fun failFinalization(item: DownloadItem, part: DownloadItemPart, message: String) {
    AbsLogger.error(tag, message)
    part.isMoving = false
    part.failed = true
    failOrRetry(item, part, message, isFinalizationFailure = true)
  }

  @Synchronized
  private fun completePart(item: DownloadItem, part: DownloadItemPart) {
    part.moved = true
    part.completed = true
    part.failed = false
    part.isMoving = false
    part.waitingForNetwork = false
    part.waitingForWifi = false
    reservations.remove(part.destinationPath)
    removeActivePart(part)
    if (!isQueued(item)) return
    persist(item, force = true)
    checkDownloadItemFinished(item)
  }

  @Synchronized
  private fun checkDownloadItemFinished(item: DownloadItem) {
    if (!item.isDownloadFinished || !finalizingItems.add(item.id)) return
    IncompleteDownloadCleanup.cancel(context, item.id)
    scope.launch {
      try {
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
              downloadItemQueue.remove(item)
              DeviceManager.dbManager.removeDownloadItem(item.id)
            }
          }
        }
      } catch (e: Exception) {
        // The files are already in place, so leave the item queued for restoreQueue to rescan.
        AbsLogger.error(tag, "Could not finalize download item ${item.id}: ${e.message}")
      } finally {
        synchronized(this@DownloadItemManager) {
          finalizingItems.remove(item.id)
          notifyQueueChanged()
          updateNeedsCpu()
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
    activeAttempts.remove(part.id)
    attemptStartBytes.remove(part.id)
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
    networkMonitor.destroy()
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
    AbsLogger.info(tag, "Reusing existing cover ${part.filename}")
    File(part.destinationPath).delete()
    completePart(item, part)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    return true
  }

  private fun hasActiveDestinationConflict(part: DownloadItemPart): Boolean =
          currentDownloadItemParts.any { activePart ->
            activePart !== part &&
                    activePart.localFolderId == part.localFolderId &&
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
        if (file?.isFile == true && (part.fileSize <= 0L || file.length() == part.fileSize))
                return true
      } catch (e: Exception) {
        AbsLogger.error(tag, "Could not validate SAF file ${part.filename}: ${e.message}")
      }
    }
    return findSharedStorageFile(part) != null
  }

  /**
   * Prepares an unmoved part to continue after a restart or user retry. The staging file is kept so
   * the next request resumes from its length; InternalDownloadManager restarts from zero if the
   * server says the file changed or the range is invalid.
   */
  private fun prepareForResume(part: DownloadItemPart) {
    val stagingFile = File(part.destinationPath)
    val stagedBytes = stagingFile.takeIf(File::isFile)?.length() ?: 0L
    // A completed part whose staging file is intact only needs finalizing.
    part.completed = part.completed && stagedBytes > 0L && (part.fileSize <= 0L || stagedBytes == part.fileSize)
    part.bytesDownloaded = stagedBytes
    part.progress = if (part.fileSize > 0L) ((stagedBytes * 100L) / part.fileSize).coerceAtMost(100L) else 0L
    part.failed = false
    part.isMoving = false
    part.downloadId = null
    part.retryCount = 0
    part.authRetryCount = 0
    part.finalizeFailureCount = 0
    part.nextAttemptAt = 0L
    part.waitingForSpace = false
    part.waitingForNetwork = false
    part.waitingForWifi = false
    part.reusedExistingFile = false
    if (stagedBytes > 0L) AbsLogger.info(tag, "Resuming ${part.filename} from byte $stagedBytes")
  }

  private fun findDocumentByFilename(folder: DocumentFile, part: DownloadItemPart): DocumentFile? {
    folder.findFile(part.filename)?.let {
      return it
    }
    val expectedBaseName = part.filename.substringBeforeLast('.')
    return folder.listFiles().firstOrNull { document ->
      document.name == part.filename ||
              document.fullName == part.filename ||
              (part.audioTrack != null &&
                      document.isFile &&
                      ((document.name ?: "").substringBeforeLast('.') == expectedBaseName ||
                              document.fullName.substringBeforeLast('.') == expectedBaseName))
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
    const val RETRY_BASE_DELAY_MS = 2_000L
    const val RETRY_MAX_DELAY_MS = 5L * 60L * 1000L
    const val CPU_HOLD_BEFORE_RETRY_MS = 30_000L
    const val MAX_FINALIZE_RETRIES = 5
    const val MAX_AUTH_RETRIES = 2
    const val PERSIST_INTERVAL_MS = 2_000L
    const val MIN_FREE_SPACE_BYTES = 100L * 1024L * 1024L
    const val UNKNOWN_PART_RESERVATION_BYTES = 100L * 1024L * 1024L
  }
}
