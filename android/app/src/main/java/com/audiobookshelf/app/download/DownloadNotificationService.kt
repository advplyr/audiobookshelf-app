package com.audiobookshelf.app.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.audiobookshelf.app.R

/**
 * Foreground service to keep downloads running in background.
 * Shows a persistent notification and holds a wake lock to prevent the device from sleeping.
 */
class DownloadNotificationService : Service() {
  private val tag = "DownloadNotifService"
  private val binder = LocalBinder()
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
  private var isForeground = false

  private var activeDownloads = 0
  private var currentFileName = ""

  companion object {
    const val NOTIFICATION_ID = 2001
    const val CHANNEL_ID = "audiobookshelf_downloads"
    const val CHANNEL_NAME = "Downloads"
    private const val WAKE_LOCK_TAG = "AudiobookshelfApp:DownloadWakeLock"
  }

  inner class LocalBinder : Binder() {
    fun getService(): DownloadNotificationService = this@DownloadNotificationService
  }

  override fun onCreate() {
    super.onCreate()
    Log.d(tag, "DownloadNotificationService onCreate")
    createNotificationChannel()
    acquireWakeLock()
    acquireWifiLock()
  }

  override fun onBind(intent: Intent?): IBinder {
    Log.d(tag, "DownloadNotificationService onBind")
    return binder
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(tag, "DownloadNotificationService onStartCommand")
    startForegroundService()
    // START_NOT_STICKY ensures service is not recreated if killed by the system
    // This prevents holding wake locks when no downloads are in progress
    return START_NOT_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    Log.d(tag, "DownloadNotificationService onTaskRemoved - app swiped away, keeping service alive")
    // Don't stop the service when app is removed from recents
    // The service will continue running until downloads complete
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    Log.d(tag, "DownloadNotificationService onDestroy")
    releaseWakeLock()
    releaseWifiLock()
    super.onDestroy()
  }

  /**
   * Acquires a partial wake lock to keep the CPU running while downloading.
   */
  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) {
      Log.d(tag, "Wake lock already held")
      return
    }

    try {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
        WAKE_LOCK_TAG
      ).apply {
        // No timeout - will be released when service stops
        setReferenceCounted(false)
        acquire()
        Log.d(tag, "Wake lock acquired")
      }
    } catch (e: Exception) {
      Log.e(tag, "Failed to acquire wake lock", e)
    }
  }

  /**
   * Acquires a WiFi wake lock to keep WiFi active while downloading.
   */
  private fun acquireWifiLock() {
    if (wifiLock?.isHeld == true) {
      Log.d(tag, "WiFi lock already held")
      return
    }

    try {
      val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
      wifiLock = wifiManager.createWifiLock(
        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "AudiobookshelfApp:DownloadWifiLock"
      ).apply {
        setReferenceCounted(false)
        // Acquire WiFi lock with a 10-minute timeout as a safeguard
        acquire(10 * 60 * 1000L) // 10 minutes in milliseconds
        Log.d(tag, "WiFi lock acquired (10 min timeout)")
      }
    } catch (e: Exception) {
      Log.e(tag, "Failed to acquire WiFi lock", e)
    }
  }

  /**
   * Releases the wake lock when downloads are complete.
   */
  private fun releaseWakeLock() {
    try {
      wakeLock?.let {
        if (it.isHeld) {
          it.release()
          Log.d(tag, "Wake lock released")
        }
      }
      wakeLock = null
    } catch (e: Exception) {
      Log.e(tag, "Failed to release wake lock", e)
    }
  }

  /**
   * Releases the WiFi wake lock when downloads are complete.
   */
  private fun releaseWifiLock() {
    try {
      wifiLock?.let {
        if (it.isHeld) {
          it.release()
          Log.d(tag, "WiFi lock released")
        }
      }
      wifiLock = null
    } catch (e: Exception) {
      Log.e(tag, "Failed to release WiFi lock", e)
    }
  }

  /**
   * Creates the notification channel for download notifications.
   */
  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        CHANNEL_NAME,
        NotificationManager.IMPORTANCE_DEFAULT
      ).apply {
        description = "Shows download progress"
        enableLights(false)
        enableVibration(false)
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDnd(true)
      }

      val notificationManager = getSystemService(NotificationManager::class.java)
      notificationManager.createNotificationChannel(channel)
      Log.d(tag, "Notification channel created")
    }
  }

  /**
   * Starts the service in foreground mode with a notification.
   */
  private fun startForegroundService() {
    if (isForeground) {
      Log.d(tag, "Service already in foreground")
      return
    }

    val notification = buildNotification(0, "")
    startForeground(NOTIFICATION_ID, notification)
    isForeground = true
    Log.d(tag, "Started foreground service")
  }

  /**
   * Builds the notification to display download progress.
   */
  private fun buildNotification(downloadCount: Int, fileName: String): Notification {
    val contentText = when {
      downloadCount == 0 -> "Preparing downloads..."
      fileName.isNotEmpty() -> "Downloading: $fileName ($downloadCount active)"
      else -> "Downloading $downloadCount file${if (downloadCount > 1) "s" else ""}"
    }

    // Intent to open the app when notification is tapped
    val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Audiobookshelf Downloads")
      .setContentText(contentText)
      .setSmallIcon(R.drawable.icon)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setContentIntent(pendingIntent)
      .setColor(Color.parseColor("#232323"))
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .build()
  }

  /**
   * Updates the notification with current download progress.
   */
  fun updateNotification(downloadCount: Int, fileName: String = "") {
    if (!isForeground) {
      Log.d(tag, "Not in foreground, skipping notification update")
      return
    }

    activeDownloads = downloadCount
    currentFileName = fileName

    val notification = buildNotification(downloadCount, fileName)
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.notify(NOTIFICATION_ID, notification)
  }

  /**
   * Stops the foreground service and removes the notification.
   */
  fun stopForegroundService() {
    if (!isForeground) {
      Log.d(tag, "Service not in foreground")
      return
    }

    Log.d(tag, "Stopping foreground service")
    isForeground = false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }

    stopSelf()
  }
}
