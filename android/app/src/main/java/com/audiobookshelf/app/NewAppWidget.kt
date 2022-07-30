package com.audiobookshelf.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE
import android.util.Log
import android.widget.RemoteViews
import androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent
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
class NewAppWidget : AppWidgetProvider() {
  val tag = "NewAppWidget"

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, null,false)
        }
    }

    override fun onEnabled(context: Context) {
      Log.w(tag, "onEnabled check context ${context.packageName}")

        // Enter relevant functionality for when the first widget is created
      DeviceManager.widgetUpdater = (object : WidgetEventEmitter {
        override fun onPlayerChanged(pns:PlayerNotificationService) {
          val isPlaying = pns.currentPlayer.isPlaying
          Log.i(tag, "onPlayerChanged | Is Playing? $isPlaying")

          val appWidgetManager = AppWidgetManager.getInstance(context)
          val componentName = ComponentName(context, NewAppWidget::class.java)
          val ids = appWidgetManager.getAppWidgetIds(componentName)

          val playbackSession = pns.getCurrentPlaybackSessionCopy()
          val cover = playbackSession?.getCoverUri()

          for (widgetId in ids) {
            updateAppWidget(context, appWidgetManager, widgetId, cover, isPlaying)
          }
        }
      })
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
    }
}

internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, coverUri:Uri?, isPlaying:Boolean) {

    val views = RemoteViews(context.packageName, R.layout.new_app_widget)

    val playPausePI = buildMediaButtonPendingIntent(context, ACTION_PLAY_PAUSE)
    views.setOnClickPendingIntent(R.id.playPauseIcon, playPausePI)

    val wholeWidgetClickI = Intent(context, MainActivity::class.java)
    wholeWidgetClickI.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
    val wholeWidgetClickPI = PendingIntent.getActivity(
      context,
      System.currentTimeMillis().toInt(),
      wholeWidgetClickI,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.appWidget, wholeWidgetClickPI)

    val imageUri = coverUri ?: Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)
    val awt: AppWidgetTarget = object : AppWidgetTarget(context.applicationContext, R.id.imageView, views, appWidgetId) {
      override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
        super.onResourceReady(resource, transition)
      }
    }

    val options = RequestOptions().override(300, 300).placeholder(R.drawable.icon).error(R.drawable.icon)
    Glide.with(context.applicationContext).asBitmap().load(imageUri).apply(options).into(awt)

    val playPauseResource = if (isPlaying) R.drawable.ic_media_pause_dark else R.drawable.ic_media_play_dark
    views.setImageViewResource(R.id.playPauseIcon, playPauseResource)

    // Instruct the widget manager to update the widget
    appWidgetManager.updateAppWidget(appWidgetId, views)
}
