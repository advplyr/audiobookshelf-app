package com.audiobookshelf.app.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DownloadItemManager
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.getcapacitor.JSObject
import kotlinx.coroutines.*

class DownloadService : Service() {
  private val tag = "DownloadService"
  private val NOTIFICATION_ID = 1001
  private val CHANNEL_ID = "download_channel"

  private var downloadItemManager: DownloadItemManager? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var uiEventEmitter: DownloadItemManager.DownloadEventEmitter? = null

  private val binder = DownloadServiceBinder()

  inner class DownloadServiceBinder : Binder() {
    fun getService(): DownloadService = this@DownloadService
  }

  // Service's own event emitter for notifications
  private val serviceEventEmitter =
          object : DownloadItemManager.DownloadEventEmitter {
            override fun onDownloadItem(downloadItem: DownloadItem) {
              updateNotification("Starting download: ${downloadItem.media.metadata.title}")
              // Also notify UI
              uiEventEmitter?.onDownloadItem(downloadItem)
            }

            override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {
              val progress = downloadItemPart.progress
              updateNotification("Downloading: ${downloadItemPart.filename} ($progress%)")
              // Also notify UI
              uiEventEmitter?.onDownloadItemPartUpdate(downloadItemPart)
            }

            override fun onDownloadItemComplete(jsobj: JSObject) {
              updateNotification("Download completed")
              // Also notify UI
              uiEventEmitter?.onDownloadItemComplete(jsobj)

              // Check if all downloads are complete
              serviceScope.launch {
                delay(2000) // Wait a bit before checking
                downloadItemManager?.let { manager ->
                  if (manager.downloadItemQueue.isEmpty()) {
                    Log.d(tag, "All downloads complete, stopping service")
                    stopSelf()
                  }
                }
              }
            }
          }

  override fun onCreate() {
    super.onCreate()
    Log.d(tag, "DownloadService created")
    createNotificationChannel()
    acquireWakeLock()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(tag, "DownloadService started")

    val notification = createNotification("Preparing downloads...")
    startForeground(NOTIFICATION_ID, notification)

    return START_STICKY // Restart service if killed
  }

  override fun onBind(intent: Intent?): IBinder {
    return binder
  }

  override fun onDestroy() {
    Log.d(tag, "DownloadService destroyed")
    serviceScope.cancel()
    releaseWakeLock()
    super.onDestroy()
  }

  fun initializeDownloadManager(
          mainActivity: MainActivity,
          uiEmitter: DownloadItemManager.DownloadEventEmitter
  ) {
    if (downloadItemManager == null) {
      val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      val folderScanner = FolderScanner(mainActivity)
      uiEventEmitter = uiEmitter
      downloadItemManager =
              DownloadItemManager(
                      downloadManager,
                      folderScanner,
                      mainActivity,
                      serviceEventEmitter // Use service event emitter
              )
      Log.d(tag, "DownloadItemManager initialized")
    }
  }

  fun getDownloadItemManager(): DownloadItemManager? {
    return downloadItemManager
  }

  fun addDownloadItem(downloadItem: DownloadItem) {
    downloadItemManager?.addDownloadItem(downloadItem)
  }

  fun resumeDownloads() {
    serviceScope.launch {
      Log.d(tag, "Resuming downloads after service restart")
      downloadItemManager?.let { manager ->
        // First, clean up any invalid downloads with blank URLs
        val pausedDownloads = DeviceManager.dbManager.getDownloadItems()
        Log.d(tag, "Found ${pausedDownloads.size} downloads in database")

        // Clean up invalid downloads before processing
        manager.cleanupInvalidDownloads()

        // Check for paused downloads in database and resume them
        val validDownloads = DeviceManager.dbManager.getDownloadItems()
        for (downloadItem in validDownloads) {
          if (!downloadItem.isDownloadFinished) {
            Log.d(tag, "Resuming download: ${downloadItem.media.metadata.title}")
            manager.resumeDownloadItem(downloadItem)
          }
        }
      }
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
              NotificationChannel(
                              CHANNEL_ID,
                              "Download Service",
                              NotificationManager.IMPORTANCE_LOW
                      )
                      .apply {
                        description = "Handles background downloads"
                        setShowBadge(false)
                      }

      val notificationManager = getSystemService(NotificationManager::class.java)
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun createNotification(content: String): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audiobookshelf")
            .setContentText(content)
            .setSmallIcon(R.drawable.icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
  }

  private fun updateNotification(content: String) {
    val notification = createNotification(content)
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.notify(NOTIFICATION_ID, notification)
  }

  private fun acquireWakeLock() {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag::DownloadWakeLock")
    wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
    Log.d(tag, "Wake lock acquired")
  }

  private fun releaseWakeLock() {
    wakeLock?.let {
      if (it.isHeld) {
        it.release()
        Log.d(tag, "Wake lock released")
      }
    }
  }
}
