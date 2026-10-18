package com.audiobookshelf.app.player

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.android.exoplayer2.ui.PlayerNotificationManager

class PlayerNotificationListener(var playerNotificationService:PlayerNotificationService) : PlayerNotificationManager.NotificationListener {
  var tag = "PlayerNotificationListener"

  companion object {
    var isForegroundService = false
  }

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
    } else if (!onGoing && isForegroundService) {
      // Player paused — demote from foreground but keep service alive so state is preserved
      Log.d(tag, "Notification Posted $notificationId - Stopping foreground (player paused), keeping service alive | $notification")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        playerNotificationService.stopForeground(Service.STOP_FOREGROUND_DETACH)
      } else {
        @Suppress("DEPRECATION")
        playerNotificationService.stopForeground(false)
      }
      isForegroundService = false
      // Re-post as a regular notification so it remains visible in the shade
      NotificationManagerCompat.from(playerNotificationService).notify(notificationId, notification)
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

      // Ensure we exit foreground mode cleanly so Android doesn't kill the service
      // for holding a foreground type without a notification
      if (isForegroundService) {
        Log.d(tag, "onNotificationCancelled: exiting foreground mode to keep service alive")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          playerNotificationService.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
          @Suppress("DEPRECATION")
          playerNotificationService.stopForeground(true)
        }
      }
    }
    isForegroundService = false
  }
}
