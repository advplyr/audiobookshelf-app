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
import com.audiobookshelf.app.managers.DbManager
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
    Log.d(tag, "onUpdate $appWidgetIds")
  }

  override fun onEnabled(context: Context) {
    Log.i(tag, "onEnabled check context ${context.packageName}")

    DbManager.initialize(context)

    DeviceManager.deviceData.lastPlaybackSession?.let {
      val appWidgetManager = AppWidgetManager.getInstance(context)
      val componentName = ComponentName(context, MediaPlayerWidget::class.java)
      val ids = appWidgetManager.getAppWidgetIds(componentName)
      Log.d(tag, "Setting initial widget state with last playback session ${it.displayTitle}")
      for (widgetId in ids) {
        updateAppWidget(context, appWidgetManager, widgetId, it, false, true)
      }
    }

    // Enter relevant functionality for when the first widget is created
    DeviceManager.initializeWidgetUpdater(context)
  }
}

internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, playbackSession: PlaybackSession?, isPlaying:Boolean, isAppClosed:Boolean) {
  val tag = "MediaPlayerWidget"
  val views = RemoteViews(context.packageName, R.layout.media_player_widget)
  Log.i(tag, "updateAppWidget ${playbackSession?.displayTitle ?: "No Title"} isPlaying=$isPlaying isAppClosed=$isAppClosed")
  val wholeWidgetClickI = Intent(context, MainActivity::class.java)
  wholeWidgetClickI.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
  val wholeWidgetClickPI = PendingIntent.getActivity(
    context,
    System.currentTimeMillis().toInt(),
    wholeWidgetClickI,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )

  val playPausePI = MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE)
  views.setOnClickPendingIntent(R.id.widgetPlayPauseButton, playPausePI)

  val fastForwardPI = MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_FAST_FORWARD)
  views.setOnClickPendingIntent(R.id.widgetFastForwardButton, fastForwardPI)

  val rewindPI = MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_REWIND)
  views.setOnClickPendingIntent(R.id.widgetRewindButton, rewindPI)

  // Show/Hide button container
  views.setViewVisibility(R.id.widgetButtonContainer, if (isAppClosed) View.GONE else View.VISIBLE)

  views.setOnClickPendingIntent(R.id.widgetBackground, wholeWidgetClickPI)

  val imageUri = playbackSession?.getCoverUri(context) ?: Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
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

  val playPauseResource = if (isPlaying) androidx.mediarouter.R.drawable.ic_media_pause_dark else androidx.mediarouter.R.drawable.ic_media_play_dark
  views.setImageViewResource(R.id.widgetPlayPauseButton, playPauseResource)

  // Instruct the widget manager to update the widget
  appWidgetManager.updateAppWidget(appWidgetId, views)
}
