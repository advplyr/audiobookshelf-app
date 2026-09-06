package com.audiobookshelf.app.services

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.models.DownloadItem
import com.getcapacitor.JSObject
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shared process owner used by the foreground service and the Capacitor bridge. */
object DownloadServiceHost {
  data class NotificationStrings(
          val preparing: String,
          val downloadingFile: String,
          val waitingForStorage: String,
          val downloads: String,
          val cancel: String
  )

  private const val tag = "DownloadServiceHost"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var manager: DownloadItemManager? = null
  private var bridgeEmitter: DownloadItemManager.DownloadEventEmitter = NoopEmitter
  private var service: DownloadService? = null
  @Volatile private var bridgeReady = false
  @Volatile private var restoreJob: Job? = null
  private val deferredCompletions = Collections.synchronizedList(mutableListOf<JSObject>())

  @Synchronized
  fun ensure(context: Context): DownloadItemManager {
    if (manager == null) {
      val appContext = context.applicationContext
      DbManager.initialize(appContext)
      val created = DownloadItemManager(FolderScanner(appContext), appContext, ForwardingEmitter)
      manager = created
      // Restoring deserializes the download db and probes shared storage. That is far too slow
      // for the main thread, which is where the Capacitor plugin load calls this from.
      restoreJob = scope.launch {
        created.restoreQueue()
        if (created.hasWork()) startService(appContext)
      }
    }
    return manager!!
  }

  /** Attaches the frontend after restored queue items have been emitted. */
  @Synchronized
  fun attachBridge(context: Context, emitter: DownloadItemManager.DownloadEventEmitter) {
    bridgeReady = false
    bridgeEmitter = emitter
    val queue = ensure(context)
    val appContext = context.applicationContext
    scope.launch {
      restoreJob?.join()
      queue.setEventEmitter(ForwardingEmitter)
      bridgeReady = true
      val completions = synchronized(deferredCompletions) {
        deferredCompletions.toList().also { deferredCompletions.clear() }
      }
      completions.forEach(bridgeEmitter::onDownloadItemComplete)
      if (queue.hasWork()) startService(appContext)
    }
  }

  @Synchronized
  fun detachBridge() {
    bridgeReady = false
    bridgeEmitter = NoopEmitter
  }

  @Synchronized
  fun enqueue(context: Context, item: DownloadItem) {
    ensure(context).addDownloadItem(item)
    startService(context)
  }

  @Synchronized
  fun cancelAll(context: Context) { ensure(context).cancelAll() }

  fun setNotificationStrings(
          context: Context,
          preparing: String,
          downloadingFile: String,
          waitingForStorage: String,
          downloads: String,
          cancel: String
  ) {
    context.getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREPARING, preparing)
            .putString(KEY_DOWNLOADING_FILE, downloadingFile)
            .putString(KEY_WAITING_FOR_STORAGE, waitingForStorage)
            .putString(KEY_DOWNLOADS, downloads)
            .putString(KEY_CANCEL, cancel)
            .apply()
  }

  fun notificationStrings(context: Context): NotificationStrings {
    val preferences = context.getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
    return NotificationStrings(
            preferences.getString(KEY_PREPARING, DEFAULT_PREPARING) ?: DEFAULT_PREPARING,
            preferences.getString(KEY_DOWNLOADING_FILE, DEFAULT_DOWNLOADING_FILE)
                    ?: DEFAULT_DOWNLOADING_FILE,
            preferences.getString(KEY_WAITING_FOR_STORAGE, DEFAULT_WAITING_FOR_STORAGE)
                    ?: DEFAULT_WAITING_FOR_STORAGE,
            preferences.getString(KEY_DOWNLOADS, DEFAULT_DOWNLOADS) ?: DEFAULT_DOWNLOADS,
            preferences.getString(KEY_CANCEL, DEFAULT_CANCEL) ?: DEFAULT_CANCEL)
  }

  @Synchronized
  fun attachService(downloadService: DownloadService) {
    service = downloadService
    val queue = ensure(downloadService)
    scope.launch {
      restoreJob?.join()
      downloadService.onQueueChanged(queue.hasWork())
    }
  }

  @Synchronized
  fun detachService(downloadService: DownloadService) {
    if (service === downloadService) service = null
  }

  private fun startService(context: Context) {
    // The queue can be restored while the app sits in the background, where starting a
    // foreground service is not permitted. The in-process watcher keeps the queue moving.
    try {
      ContextCompat.startForegroundService(context, DownloadService.intent(context))
    } catch (e: Exception) {
      Log.w(tag, "Could not start the download foreground service", e)
    }
  }

  private object ForwardingEmitter : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) { bridgeEmitter.onDownloadItem(downloadItem) }
    override fun onDownloadItemPartUpdate(downloadItemPart: com.audiobookshelf.app.models.DownloadItemPart) {
      if (bridgeReady) bridgeEmitter.onDownloadItemPartUpdate(downloadItemPart)
      service?.onPartUpdate(downloadItemPart)
    }
    override fun onDownloadItemComplete(jsobj: JSObject) {
      if (bridgeReady) bridgeEmitter.onDownloadItemComplete(jsobj) else deferredCompletions.add(jsobj)
    }
    override fun onQueueChanged(hasWork: Boolean) {
      bridgeEmitter.onQueueChanged(hasWork)
      service?.onQueueChanged(hasWork)
    }
  }

  private object NoopEmitter : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) = Unit
    override fun onDownloadItemPartUpdate(downloadItemPart: com.audiobookshelf.app.models.DownloadItemPart) = Unit
    override fun onDownloadItemComplete(jsobj: JSObject) = Unit
    override fun onQueueChanged(hasWork: Boolean) = Unit
  }

  private const val NOTIFICATION_PREFERENCES = "download_notifications"
  private const val KEY_PREPARING = "preparing"
  private const val KEY_DOWNLOADING_FILE = "downloading_file"
  private const val KEY_WAITING_FOR_STORAGE = "waiting_for_storage"
  private const val KEY_DOWNLOADS = "downloads"
  private const val KEY_CANCEL = "cancel"
  private const val DEFAULT_PREPARING = "Preparing downloads"
  private const val DEFAULT_DOWNLOADING_FILE = "Downloading {0}"
  private const val DEFAULT_WAITING_FOR_STORAGE = "Waiting for available storage"
  private const val DEFAULT_DOWNLOADS = "Downloads"
  private const val DEFAULT_CANCEL = "Cancel"
}
