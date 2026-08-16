package com.audiobookshelf.app.player.media3

import android.content.Context
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.WidgetPlaybackSnapshot

/**
 * Builds and pushes home-screen widget snapshots, holding the last one pushed so unchanged
 * ticks don't wake the widget updater.
 */
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
    val snapshot = session?.let {
      WidgetPlaybackSnapshot(
        title = it.displayTitle,
        author = it.displayAuthor,
        coverUri = it.getCoverUri(context),
        positionMs = positionMs,
        durationMs = it.totalDurationMs,
        isPlaying = isPlaying,
        isClosed = false
      )
    } ?: return

    if (snapshot.hasMeaningfulChangesFrom(lastSnapshot)) {
      lastSnapshot = snapshot
      updater.onPlayerChanged(snapshot)
    }
  }
}
