package com.audiobookshelf.app.player

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import com.google.android.exoplayer2.ui.PlayerNotificationManager

class PlayerNotificationListener(var playerNotificationService:PlayerNotificationService) : PlayerNotificationManager.NotificationListener {
  companion object {
    private const val TAG = "PlayerNotificationListener"
    var isForegroundService = false
  }

  private val tag = TAG

  override fun onNotificationPosted(
    notificationId: Int,
    notification: Notification,
    onGoing: Boolean) {

    if (onGoing && !isForegroundService) {
      // Start foreground service
      Log.d(tag, "Notification Posted $notificationId - Start Foreground | $notification")
      PlayerNotificationService.isClosed = false

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        playerNotificationService.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      } else {
        playerNotificationService.startForeground(notificationId, notification)
      }
      isForegroundService = true
    } else {
      Log.d(tag, "Notification posted $notificationId, not starting foreground - onGoing=$onGoing | isForegroundService=$isForegroundService")
    }
  }

  override fun onNotificationCancelled(
    notificationId: Int,
    dismissedByUser: Boolean
  ) {
    if (dismissedByUser) {
      Log.d(tag, "onNotificationCancelled dismissed by user")
      playerNotificationService.stopSelf()
    } else {
      Log.d(tag, "onNotificationCancelled not dismissed by user")

      if (PlayerNotificationService.isSwitchingPlayer) {
        // When switching from cast player to exo player and vice versa the notification is cancelled and posted again
          // so we don't want to cancel the playback during this switch
        Log.d(tag, "PNS is switching player")
        PlayerNotificationService.isSwitchingPlayer = false
      }
    }
    isForegroundService = false
  }
}
