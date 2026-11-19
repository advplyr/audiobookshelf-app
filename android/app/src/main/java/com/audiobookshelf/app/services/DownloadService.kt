package com.audiobookshelf.app.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.audiobookshelf.app.R
import com.audiobookshelf.app.models.DownloadItemPart
import com.audiobookshelf.app.plugins.AbsLogger

/** Android-owned foreground lifecycle for transfers that must outlive the WebView and Activity. */
class DownloadService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null
  /** Set before teardown so a late setNeedsCpu from a worker thread cannot re-acquire the locks. */
  private var destroyed = false

  override fun onCreate() {
    super.onCreate()
    createChannels()
    startForegroundWithType(DownloadServiceHost.notificationStrings(this).preparing)
    // Assume a transfer is about to start; the queue releases the locks if it is only waiting.
    setNeedsCpu(true)
    DownloadServiceHost.attachService(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CANCEL -> DownloadServiceHost.cancelAll(this)
      ACTION_ALLOW_CELLULAR -> DownloadServiceHost.allowCellular(this, null)
      else -> {
        startForegroundWithType(DownloadServiceHost.notificationStrings(this).preparing)
        DownloadServiceHost.startWork(this)
      }
    }
    return START_STICKY
  }

  /** Android 15+ limits dataSync services to 6 hours a day; keep progress and resume on next app open. */
  override fun onTimeout(startId: Int, fgsType: Int) {
    AbsLogger.info(TAG, "Foreground service time limit reached; pausing downloads")
    DownloadServiceHost.pauseForServiceTimeout()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    DownloadServiceHost.detachService(this)
    synchronized(this) {
      destroyed = true
      releaseLocks()
    }
    notificationManager().cancel(APPROVAL_NOTIFICATION_ID)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  fun onPartUpdate(part: DownloadItemPart) {
    val strings = DownloadServiceHost.notificationStrings(this)
    val text =
            when {
              part.waitingForSpace -> strings.waitingForStorage
              part.waitingForWifi -> strings.waitingForWifi
              part.waitingForNetwork -> strings.waitingForNetwork
              part.status == "retrying" -> strings.retrying.replace("{0}", part.filename)
              else -> strings.downloadingFile.replace("{0}", part.filename)
            }
    val progress = part.progress.coerceIn(0L, 100L).toInt()
    val notification = notification(text, progress, part.fileSize > 0L)
    notificationManager().notify(NOTIFICATION_ID, notification)
  }

  fun onQueueChanged(hasWork: Boolean) {
    if (!hasWork) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  /**
   * Holds a partial wake lock and Wi-Fi lock only while a transfer runs or is due, so a screen-off
   * device keeps the connection alive without draining the battery while merely waiting.
   */
  @SuppressLint("WakelockTimeout")
  @Synchronized
  fun setNeedsCpu(needsCpu: Boolean) {
    if (destroyed) return
    if (!needsCpu) {
      releaseLocks()
      return
    }
    if (wakeLock?.isHeld != true) {
      wakeLock =
              (getSystemService(POWER_SERVICE) as PowerManager)
                      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiobookshelf:downloads")
                      .apply {
                        setReferenceCounted(false)
                        acquire()
                      }
    }
    if (wifiLock?.isHeld != true) {
      wifiLock =
              (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                      .createWifiLock(wifiLockMode(), "audiobookshelf:downloads")
                      .apply {
                        setReferenceCounted(false)
                        acquire()
                      }
    }
  }

  @Suppress("DEPRECATION")
  private fun wifiLockMode(): Int = WifiManager.WIFI_MODE_FULL_HIGH_PERF

  private fun releaseLocks() {
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
    wifiLock?.takeIf { it.isHeld }?.release()
    wifiLock = null
  }

  /** Asks whether Wi-Fi preferred downloads paused on a metered network may continue on mobile data. */
  fun onCellularApprovalChanged(waitingItems: Int) {
    if (waitingItems <= 0) {
      notificationManager().cancel(APPROVAL_NOTIFICATION_ID)
      return
    }
    val strings = DownloadServiceHost.notificationStrings(this)
    val allowIntent =
            PendingIntent.getService(
                    this,
                    2,
                    Intent(this, DownloadService::class.java).setAction(ACTION_ALLOW_CELLULAR),
                    pendingIntentFlags())
    val notification =
            NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.icon)
                    .setContentTitle(strings.cellularApprovalTitle)
                    .setContentText(strings.cellularApprovalText.replace("{0}", waitingItems.toString()))
                    .setContentIntent(openAppIntent())
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .addAction(0, strings.useMobileData, allowIntent)
                    .build()
    notificationManager().notify(APPROVAL_NOTIFICATION_ID, notification)
  }

  private fun openAppIntent(): PendingIntent? =
          packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 3, it, pendingIntentFlags())
          }

  private fun startForegroundWithType(text: String) {
    val notification = notification(text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun notification(text: String, progress: Int = 0, determinate: Boolean = false): Notification {
    val cancelIntent = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), pendingIntentFlags())
    return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(DownloadServiceHost.notificationStrings(this).downloads)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, !determinate)
            .addAction(0, DownloadServiceHost.notificationStrings(this).cancel, cancelIntent)
            .build()
  }

  private fun createChannels() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val strings = DownloadServiceHost.notificationStrings(this)
    notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings.downloads, NotificationManager.IMPORTANCE_LOW))
    notificationManager().createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, strings.cellularApprovalTitle, NotificationManager.IMPORTANCE_DEFAULT))
  }

  private fun notificationManager(): NotificationManager =
          getSystemService(NOTIFICATION_SERVICE) as NotificationManager

  private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

  companion object {
    private const val TAG = "DownloadService"
    private const val CHANNEL_ID = "downloads"
    private const val ALERT_CHANNEL_ID = "download_alerts"
    private const val NOTIFICATION_ID = 11
    private const val APPROVAL_NOTIFICATION_ID = 12
    private const val ACTION_CANCEL = "com.audiobookshelf.app.download.CANCEL"
    private const val ACTION_ALLOW_CELLULAR = "com.audiobookshelf.app.download.ALLOW_CELLULAR"
    fun intent(context: Context) = Intent(context, DownloadService::class.java)
  }
}
