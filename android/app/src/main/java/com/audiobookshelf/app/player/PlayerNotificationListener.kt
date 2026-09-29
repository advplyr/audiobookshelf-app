package com.audiobookshelf.app.player

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
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

    // Keep foreground service alive when a playlist queue is active AND the player
    // intends to continue playing (playWhenReady is true).  When the player is
    // paused — whether by Bluetooth disconnect (ACTION_AUDIO_BECOMING_NOISY),
    // user pause, or any other reason — playWhenReady is false and we must NOT
    // re-assert foreground, because doing so can interfere with the pause on
    // some devices (Samsung One UI, Android 12+) or prevent the system from
    // properly demoting the service after an audio-route change.
    val hasPlaylistQueue = playerNotificationService.playlistQueue.isNotEmpty()
    val playerWantsToPlay = playerNotificationService.currentPlayer.playWhenReady
    val effectiveOnGoing = onGoing || (hasPlaylistQueue && playerWantsToPlay)

    if (effectiveOnGoing) {
      if (!isForegroundService) {
        // Start foreground service for the first time
        Log.d(tag, "Notification Posted $notificationId - Start Foreground | onGoing=$onGoing hasPlaylistQueue=$hasPlaylistQueue playWhenReady=$playerWantsToPlay")
        PlayerNotificationService.isClosed = false
      } else if (!onGoing && hasPlaylistQueue && playerWantsToPlay) {
        // Player not actively playing but intends to continue (e.g. between episodes):
        // re-assert foreground to prevent system from downgrading the service
        Log.d(tag, "Notification Posted $notificationId - Re-assert Foreground for playlist queue | onGoing=$onGoing hasPlaylistQueue=$hasPlaylistQueue playWhenReady=$playerWantsToPlay")
      } else {
        // Already in foreground and ongoing - just update the notification
        return
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        playerNotificationService.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      } else {
        playerNotificationService.startForeground(notificationId, notification)
      }
      isForegroundService = true
    } else {
      Log.d(tag, "Notification posted $notificationId, not starting foreground - onGoing=$onGoing | hasPlaylistQueue=$hasPlaylistQueue | playWhenReady=$playerWantsToPlay | isForegroundService=$isForegroundService")
    }
  }

  override fun onNotificationCancelled(
    notificationId: Int,
    dismissedByUser: Boolean
  ) {
    val hasPlaylistQueue = playerNotificationService.playlistQueue.isNotEmpty()

    if (dismissedByUser) {
      // Only keep the service alive if the player actually intends to continue
      // playing (e.g. between episodes).  If the user paused and then dismissed,
      // or if Bluetooth disconnected (playWhenReady == false), allow the stop.
      val playerWantsToPlay = playerNotificationService.currentPlayer.playWhenReady
      if (hasPlaylistQueue && playerWantsToPlay) {
        Log.d(tag, "onNotificationCancelled dismissed by user but playlist queue active and playWhenReady - keeping service alive")
        return
      }
      Log.d(tag, "onNotificationCancelled dismissed by user | hasPlaylistQueue=$hasPlaylistQueue | playWhenReady=$playerWantsToPlay")
      playerNotificationService.stopSelf()
    } else {
      Log.d(tag, "onNotificationCancelled not dismissed by user | hasPlaylistQueue=$hasPlaylistQueue")

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
