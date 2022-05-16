package com.bookshelf.app.player

import android.app.Notification
import android.util.Log
import com.google.android.exoplayer2.ui.PlayerNotificationManager

class PlayerNotificationListener(var playerNotificationService:PlayerNotificationService) : PlayerNotificationManager.NotificationListener {
  var tag = "PlayerNotificationListener"

  override fun onNotificationPosted(
    notificationId: Int,
    notification: Notification,
    onGoing: Boolean) {

    // Start foreground service
    Log.d(tag, "Notification Posted $notificationId - Start Foreground | $notification")
    playerNotificationService.startForeground(notificationId, notification)
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
    }
  }
}
