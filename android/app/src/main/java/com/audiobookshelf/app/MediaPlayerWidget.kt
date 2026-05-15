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
import androidx.annotation.OptIn
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.Media3PlaybackService
import com.audiobookshelf.app.player.PlaybackConstants
import com.audiobookshelf.app.player.WidgetPlaybackSnapshot
import com.audiobookshelf.app.player.toWidgetSnapshot
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
      val snapshot = it.toWidgetSnapshot(context, isPlaying = false, isClosed = true)
      for (widgetId in ids) {
        updateAppWidget(context, appWidgetManager, widgetId, snapshot)
      }
    }

    // Enter relevant functionality for when the first widget is created
    DeviceManager.initializeWidgetUpdater(context)
  }
}

internal fun updateAppWidget(
  context: Context,
  appWidgetManager: AppWidgetManager,
  appWidgetId: Int,
  snapshot: WidgetPlaybackSnapshot?
) {
  val tag = "MediaPlayerWidget"
  val views = RemoteViews(context.packageName, R.layout.media_player_widget)
  val title = snapshot?.title ?: "Unknown"
  val isPlaying = snapshot?.isPlaying ?: false
  val isAppClosed = snapshot?.isClosed ?: false
  Log.i(tag, "updateAppWidget $title isPlaying=$isPlaying isAppClosed=$isAppClosed")
  val wholeWidgetClickI = Intent(context, MainActivity::class.java)
  wholeWidgetClickI.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
  val wholeWidgetClickPI = PendingIntent.getActivity(
    context,
    System.currentTimeMillis().toInt(),
    wholeWidgetClickI,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )

  val playPausePI = if (BuildConfig.USE_MEDIA3) {
    buildMedia3WidgetPendingIntent(
      context,
      PlaybackConstants.WidgetActions.PLAY_PAUSE,
      appWidgetId
    )
  } else {
    MediaButtonReceiver.buildMediaButtonPendingIntent(
      context,
      PlaybackStateCompat.ACTION_PLAY_PAUSE
    )
  }
  views.setOnClickPendingIntent(R.id.widgetPlayPauseButton, playPausePI)

  val fastForwardPI = if (BuildConfig.USE_MEDIA3) {
    buildMedia3WidgetPendingIntent(
      context,
      PlaybackConstants.WidgetActions.FAST_FORWARD,
      appWidgetId + 1
    )
  } else {
    MediaButtonReceiver.buildMediaButtonPendingIntent(
      context,
      PlaybackStateCompat.ACTION_FAST_FORWARD
    )
  }
  views.setOnClickPendingIntent(R.id.widgetFastForwardButton, fastForwardPI)

  val rewindPI = if (BuildConfig.USE_MEDIA3) {
    buildMedia3WidgetPendingIntent(
      context,
      PlaybackConstants.WidgetActions.REWIND,
      appWidgetId + 2
    )
  } else {
    MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_REWIND)
  }
  views.setOnClickPendingIntent(R.id.widgetRewindButton, rewindPI)

  // Show/Hide button container
  views.setViewVisibility(R.id.widgetButtonContainer, if (isAppClosed) View.GONE else View.VISIBLE)

  views.setOnClickPendingIntent(R.id.widgetBackground, wholeWidgetClickPI)

  val imageUri = snapshot?.coverUri
    ?: Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
  val awt: AppWidgetTarget = object : AppWidgetTarget(context.applicationContext, R.id.widgetAlbumArt, views, appWidgetId) {
    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
      super.onResourceReady(resource, transition)
    }
  }

  val artist = snapshot?.author ?: "Unknown"
  views.setTextViewText(R.id.widgetArtistText, artist)

  views.setTextViewText(R.id.widgetMediaTitle, title)

  val options = RequestOptions().override(300, 300).placeholder(R.drawable.icon).error(R.drawable.icon)
  Glide.with(context.applicationContext).asBitmap().load(imageUri).apply(options).into(awt)

  Log.i(tag, "Update App Widget | Is Playing=$isPlaying | isAppClosed=$isAppClosed")

  val playPauseResource = if (isPlaying) androidx.mediarouter.R.drawable.ic_media_pause_dark else androidx.mediarouter.R.drawable.ic_media_play_dark
  views.setImageViewResource(R.id.widgetPlayPauseButton, playPauseResource)

  // Instruct the widget manager to update the widget
  appWidgetManager.updateAppWidget(appWidgetId, views)
}

@OptIn(UnstableApi::class)
private fun buildMedia3WidgetPendingIntent(
  context: Context,
  action: String,
  appWidgetId: Int
): PendingIntent {
  val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  val requestCode = action.hashCode() + appWidgetId
  val intent = Intent(context, Media3PlaybackService::class.java).apply {
    this.action = action
  }
  return PendingIntent.getService(context, requestCode, intent, flags)
}
