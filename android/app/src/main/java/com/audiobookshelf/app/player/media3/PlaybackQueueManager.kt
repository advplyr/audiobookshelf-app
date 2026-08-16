package com.audiobookshelf.app.player.media3

import android.content.Context
import androidx.media3.common.Player
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.player.toMedia3MediaItems

/** Builds the player queue from a session and seeks it to the right place. */
class PlaybackQueueManager(
  private val context: Context,
  private val debug: (() -> String) -> Unit
) {
  /** Returns false when the session has no playable tracks. */
  fun loadSession(
    player: Player,
    session: PlaybackSession,
    playWhenReady: Boolean,
    playbackSpeed: Float,
    isCastActive: Boolean
  ): Boolean {
    val mediaItems = session.toMedia3MediaItems(context, preferServerUrisForCast = isCastActive)
    if (mediaItems.isEmpty()) return false

    val target = PlaybackPositionModel(session, player)
      .seekTargetForSessionTime(mediaItems.lastIndex)

    player.setMediaItems(mediaItems, target.trackIndex, target.positionInTrackMs)
    player.setPlaybackSpeed(playbackSpeed)
    player.prepare()
    player.playWhenReady = playWhenReady
    return true
  }

  /** Rebuilds the queue with server URIs the cast receiver can reach. */
  fun reloadForCast(
    player: Player,
    session: PlaybackSession,
    bookAbsolutePositionMs: Long,
    wasPlaying: Boolean
  ) {
    val mediaItems = session.toMedia3MediaItems(context, preferServerUrisForCast = true)
    if (mediaItems.isEmpty()) return

    // Derive the track from the recovered position: the player's own index has been reset too.
    session.currentTime = bookAbsolutePositionMs / 1000.0
    val target = PlaybackPositionModel(session, player)
      .seekTargetForSessionTime(mediaItems.lastIndex)

    player.setMediaItems(mediaItems, target.trackIndex, target.positionInTrackMs)
    player.prepare()
    player.playWhenReady = wasPlaying

    debug {
      "Reloaded queue with cast-friendly URIs at track=${target.trackIndex}, position=${target.positionInTrackMs}ms"
    }
  }
}
