package com.audiobookshelf.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.audiobookshelf.app.R
import com.audiobookshelf.app.models.DownloadItemPart

/** Android-owned foreground lifecycle for transfers that must outlive the WebView and Activity. */
class DownloadService : Service() {
  override fun onCreate() {
    super.onCreate()
    createChannel()
    startForeground(NOTIFICATION_ID, notification("Preparing downloads"))
    DownloadServiceHost.attachService(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CANCEL -> DownloadServiceHost.cancelAll(this)
      ACTION_RETRY -> DownloadServiceHost.retryAll(this)
      else -> DownloadServiceHost.ensure(this)
    }
    return START_STICKY
  }

  override fun onDestroy() {
    DownloadServiceHost.detachService(this)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  fun onPartUpdate(part: DownloadItemPart) {
    val text = if (part.waitingForSpace) "Waiting for available storage" else "Downloading ${part.filename}"
    val progress = part.progress.coerceIn(0L, 100L).toInt()
    val notification = notification(text, progress, part.fileSize > 0L)
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
  }

  fun onQueueChanged(hasWork: Boolean) {
    if (!hasWork) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private fun notification(text: String, progress: Int = 0, determinate: Boolean = false): Notification {
    val cancelIntent = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), pendingIntentFlags())
    val retryIntent = PendingIntent.getService(
            this, 2, Intent(this, DownloadService::class.java).setAction(ACTION_RETRY), pendingIntentFlags())
    return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle("Audiobookshelf downloads")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, !determinate)
            .addAction(0, "Cancel", cancelIntent)
            .addAction(0, "Retry", retryIntent)
            .build()
  }

  private fun createChannel() {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW))
  }

  private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

  companion object {
    private const val CHANNEL_ID = "downloads"
    private const val NOTIFICATION_ID = 4102
    private const val ACTION_CANCEL = "com.audiobookshelf.app.download.CANCEL"
    private const val ACTION_RETRY = "com.audiobookshelf.app.download.RETRY"
    fun intent(context: Context) = Intent(context, DownloadService::class.java)
  }
}
