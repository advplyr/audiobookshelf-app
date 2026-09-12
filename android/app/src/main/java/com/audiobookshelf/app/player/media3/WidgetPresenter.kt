package com.audiobookshelf.app.player.media3

import android.content.Context
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.WidgetPlaybackSnapshot
import com.audiobookshelf.app.player.toWidgetSnapshot

/** Caches the last snapshot so unchanged progress ticks do not wake the widget updater. */
class WidgetPresenter(private val context: Context) {
  private var lastSnapshot: WidgetPlaybackSnapshot? = null

  fun notifyState(
    session: PlaybackSession?,
    positionMs: Long,
    isPlaying: Boolean,
    isPlaybackClosed: Boolean
  ) {
    val updater = DeviceManager.widgetUpdater ?: return
    if (isPlaybackClosed) {
      lastSnapshot = null
      updater.onPlayerClosed()
      return
    }
    val snapshot = session?.toWidgetSnapshot(
      context,
      isPlaying = isPlaying,
      isClosed = false,
      positionOverrideMs = positionMs
    ) ?: return

    if (snapshot.hasMeaningfulChangesFrom(lastSnapshot)) {
      lastSnapshot = snapshot
      updater.onPlayerChanged(snapshot)
    }
  }
}
