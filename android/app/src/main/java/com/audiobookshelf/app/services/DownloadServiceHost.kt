package com.audiobookshelf.app.services

import android.content.Context
import androidx.core.content.ContextCompat
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.models.DownloadItem
import com.getcapacitor.JSObject
import java.util.Collections

/** Shared process owner used by the foreground service and the Capacitor bridge. */
object DownloadServiceHost {
  private var manager: DownloadItemManager? = null
  private var bridgeEmitter: DownloadItemManager.DownloadEventEmitter = NoopEmitter
  private var service: DownloadService? = null
  @Volatile private var bridgeReady = false
  private val deferredCompletions = Collections.synchronizedList(mutableListOf<JSObject>())

  @Synchronized
  fun ensure(context: Context): DownloadItemManager {
    if (manager == null) {
      val appContext = context.applicationContext
      DbManager.initialize(appContext)
      manager = DownloadItemManager(FolderScanner(appContext), appContext, ForwardingEmitter)
      manager!!.restoreQueue()
    }
    return manager!!
  }

  /** Attaches the frontend after restored queue items have been emitted. */
  @Synchronized
  fun attachBridge(context: Context, emitter: DownloadItemManager.DownloadEventEmitter) {
    bridgeReady = false
    bridgeEmitter = emitter
    val queue = ensure(context)
    queue.setEventEmitter(ForwardingEmitter)
    bridgeReady = true
    val completions = synchronized(deferredCompletions) {
      deferredCompletions.toList().also { deferredCompletions.clear() }
    }
    completions.forEach(bridgeEmitter::onDownloadItemComplete)
    if (queue.hasWork()) startService(context)
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
  fun retryAll(context: Context) {
    startService(context)
    ensure(context).retryAll()
  }

  @Synchronized
  fun cancelAll(context: Context) { ensure(context).cancelAll() }

  @Synchronized
  fun attachService(downloadService: DownloadService) {
    service = downloadService
    service?.onQueueChanged(ensure(downloadService).hasWork())
  }

  @Synchronized
  fun detachService(downloadService: DownloadService) {
    if (service === downloadService) service = null
  }

  private fun startService(context: Context) {
    ContextCompat.startForegroundService(context, DownloadService.intent(context))
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
}
