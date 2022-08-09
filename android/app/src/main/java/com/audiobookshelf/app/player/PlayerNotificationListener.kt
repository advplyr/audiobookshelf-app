package com.audiobookshelf.app.player

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
    PlayerNotificationService.isClosed = false
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

      // When stop button is pressed on the notification I guess it isn't considered "dismissedByUser" so we need to close playback ourselves
      if (!PlayerNotificationService.isClosed && !PlayerNotificationService.isSwitchingPlayer) {
        Log.d(tag, "PNS is not closed - closing it now")
        playerNotificationService.closePlayback()
      } else if (PlayerNotificationService.isSwitchingPlayer) {
        // When switching from cast player to exo player and vice versa the notification is cancelled and posted again
          // so we don't want to cancel the playback during this switch
        Log.d(tag, "PNS is switching player")
        PlayerNotificationService.isSwitchingPlayer = false
      }
    }
  }
}
