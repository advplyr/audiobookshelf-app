package com.tomesonic.app.player

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.media3.ui.PlayerNotificationManager

class PlayerNotificationListener(var playerNotificationService:PlayerNotificationService) : PlayerNotificationManager.NotificationListener {
  var tag = "PlayerNotificationListener"

  companion object {
    var isForegroundService = false
  }

  override fun onNotificationPosted(
    notificationId: Int,
    notification: Notification,
    onGoing: Boolean) {

    // TODO: Add WearableExtender for better Wear OS support
    // val wearableExtender = NotificationCompat.WearableExtender()
    //   .setHintShowBackgroundOnly(true)
    //   .setBackground(notification.getLargeIcon())

    // For now, use the original notification
    val enhancedNotification = notification

    if (onGoing && !isForegroundService) {
      // Start foreground service when media notification is posted
      Log.d(tag, "Notification Posted $notificationId - Start Foreground | $notification")
      PlayerNotificationService.isClosed = false

      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          playerNotificationService.startForeground(notificationId, enhancedNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
          playerNotificationService.startForeground(notificationId, enhancedNotification)
        }
        isForegroundService = true
        Log.d(tag, "Successfully started foreground service with media notification")
      } catch (e: Exception) {
        Log.e(tag, "Failed to start foreground service in notification listener: ${e.message}")
        // Don't set isForegroundService = true if we failed
      }
    } else if (onGoing && isForegroundService) {
      // Service is already in foreground, just update the notification
      Log.d(tag, "Notification posted $notificationId - Service already foreground, notification will be updated automatically")
      // The PlayerNotificationManager will automatically update the notification
      // We don't need to call startForeground again
    } else {
      Log.d(tag, "Notification posted $notificationId, not ongoing - onGoing=$onGoing | isForegroundService=$isForegroundService")
    }
  }

  override fun onNotificationCancelled(
    notificationId: Int,
    dismissedByUser: Boolean
  ) {
    if (dismissedByUser) {
      Log.d(tag, "onNotificationCancelled dismissed by user")
      playerNotificationService.stopSelf()
      isForegroundService = false
    } else {
      Log.d(tag, "onNotificationCancelled not dismissed by user")

      // MIGRATION-DEFERRED: CAST
      /*
      if (playerNotificationService.castPlayerManager.isSwitchingPlayer) {
        // When switching from cast player to exo player and vice versa the notification is cancelled and posted again
          // so we don't want to cancel the playback during this switch
        Log.d(tag, "PNS is switching player")
        playerNotificationService.castPlayerManager.isSwitchingPlayer = false
      }
      */
    }
  }
}
