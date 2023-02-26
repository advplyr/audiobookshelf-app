package com.audiobookshelf.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.media.session.MediaButtonReceiver
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.WidgetEventEmitter
import com.audiobookshelf.app.player.PlayerNotificationService
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.AppWidgetTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Implementation of App Widget functionality.
 */
class MediaPlayerWidget : AppWidgetProvider() {
  val tag = "MediaPlayerWidget"
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
      // There may be multiple widgets active, so update all of them
      for (appWidgetId in appWidgetIds) {
        updateAppWidget(context, appWidgetManager, appWidgetId, null, false, PlayerNotificationService.isClosed)
      }
    }

  override fun onEnabled(context: Context) {
    Log.i(tag, "onEnabled check context ${context.packageName}")

    // Enter relevant functionality for when the first widget is created
    DeviceManager.widgetUpdater = (object : WidgetEventEmitter {
      override fun onPlayerChanged(pns: PlayerNotificationService) {
        val isPlaying = pns.currentPlayer.isPlaying

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, MediaPlayerWidget::class.java)
        val ids = appWidgetManager.getAppWidgetIds(componentName)

        val playbackSession = pns.getCurrentPlaybackSessionCopy()

        for (widgetId in ids) {
          updateAppWidget(context, appWidgetManager, widgetId, playbackSession, isPlaying, PlayerNotificationService.isClosed)
        }
      }
    })
  }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
    }
}

internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, playbackSession: PlaybackSession?, isPlaying:Boolean, isAppClosed:Boolean) {
  val tag = "MediaPlayerWidget"
  val views = RemoteViews(context.packageName, R.layout.media_player_widget)

  val wholeWidgetClickI = Intent(context, MainActivity::class.java)
  wholeWidgetClickI.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
  val wholeWidgetClickPI = PendingIntent.getActivity(
    context,
    System.currentTimeMillis().toInt(),
    wholeWidgetClickI,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )

  // todo: show grayed out icons?
  val viewVisibility = if (isAppClosed) View.INVISIBLE else View.VISIBLE
  views.setViewVisibility(R.id.widgetPlayPauseButton, viewVisibility)
  views.setViewVisibility(R.id.widgetFastForwardButton, viewVisibility)
  views.setViewVisibility(R.id.widgetRewindButton, viewVisibility)

  val playPausePI = MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE)
  views.setOnClickPendingIntent(R.id.widgetPlayPauseButton, playPausePI)

  val fastForwardPI = MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_FAST_FORWARD)
  views.setOnClickPendingIntent(R.id.widgetFastForwardButton, fastForwardPI)

  val rewindPI = MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_REWIND)
  views.setOnClickPendingIntent(R.id.widgetRewindButton, rewindPI)


  views.setOnClickPendingIntent(R.id.widgetBackground, wholeWidgetClickPI)

  val imageUri = playbackSession?.getCoverUri() ?: Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)
  val awt: AppWidgetTarget = object : AppWidgetTarget(context.applicationContext, R.id.widgetAlbumArt, views, appWidgetId) {
    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
      super.onResourceReady(resource, transition)
    }
  }

  val artist = playbackSession?.displayAuthor ?: "Unknown"
  views.setTextViewText(R.id.widgetArtistText, artist)

  val title = playbackSession?.displayTitle ?: "Unknown"
  views.setTextViewText(R.id.widgetMediaTitle, title)

  val options = RequestOptions().override(300, 300).placeholder(R.drawable.icon).error(R.drawable.icon)
  Glide.with(context.applicationContext).asBitmap().load(imageUri).apply(options).into(awt)

  Log.i(tag, "Update App Widget | Is Playing=$isPlaying | isAppClosed=$isAppClosed")

  val playPauseResource = if (isPlaying) R.drawable.ic_media_pause_dark else R.drawable.ic_media_play_dark
  views.setImageViewResource(R.id.widgetPlayPauseButton, playPauseResource)

  // Instruct the widget manager to update the widget
  appWidgetManager.updateAppWidget(appWidgetId, views)
}
