package com.audiobookshelf.wear.playback

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.audiobookshelf.wear.R
import com.audiobookshelf.wear.WearApp
import com.google.common.collect.ImmutableList

/**
 * The media notification, marked as an Ongoing Activity so Wear OS shows a "now playing" icon on
 * the watch face and in recents that opens the player.
 */
@OptIn(UnstableApi::class)
class PlaybackNotificationProvider(
  private val context: Context,
  private val openPlayer: PendingIntent
) : MediaNotification.Provider {

  override fun createNotification(
    mediaSession: MediaSession,
    mediaButtonPreferences: ImmutableList<CommandButton>,
    actionFactory: MediaNotification.ActionFactory,
    onNotificationChangedCallback: MediaNotification.Provider.Callback
  ): MediaNotification {
    val player = mediaSession.player
    val metadata = player.mediaMetadata
    val title = metadata.title?.toString().orEmpty()
    val playing = player.playWhenReady

    val builder = NotificationCompat.Builder(context, WearApp.PLAYBACK_CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentText(metadata.artist)
      .setContentIntent(openPlayer)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(playing)
      .setSilent(true)
      .addAction(
        actionFactory.createMediaAction(
          mediaSession,
          IconCompat.createWithResource(context, android.R.drawable.ic_media_rew),
          "Back",
          Player.COMMAND_SEEK_BACK
        )
      )
      .addAction(
        actionFactory.createMediaAction(
          mediaSession,
          IconCompat.createWithResource(context, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
          if (playing) "Pause" else "Play",
          Player.COMMAND_PLAY_PAUSE
        )
      )
      .addAction(
        actionFactory.createMediaAction(
          mediaSession,
          IconCompat.createWithResource(context, android.R.drawable.ic_media_ff),
          "Forward",
          Player.COMMAND_SEEK_FORWARD
        )
      )
      .setStyle(MediaStyleNotificationHelper.MediaStyle(mediaSession).setShowActionsInCompactView(1))

    OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
      .setStaticIcon(R.drawable.ic_notification)
      .setTouchIntent(openPlayer)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      .setStatus(Status.Builder().addTemplate(title).build())
      .build()
      .apply(context)

    return MediaNotification(NOTIFICATION_ID, builder.build())
  }

  override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean = false

  companion object {
    const val NOTIFICATION_ID = 1001
  }
}
