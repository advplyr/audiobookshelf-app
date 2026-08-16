package com.audiobookshelf.app.player.media3

import android.content.Context
import androidx.media3.common.Player
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.player.toMedia3MediaItems

/**
 * Builds the player queue from a session and seeks it to the right place.
 *
 * Cast is the reason this is not a single method. The receiver cannot reach the device's local
 * files, so handing off a local book means rebuilding the whole queue with server URIs, and the
 * resume point has to come from the player's own track rather than the session's — the two can
 * disagree mid-handoff.
 */
class PlaybackQueueManager(
  private val context: Context,
  private val debug: (() -> String) -> Unit
) {
  /** Loads [session] at its own current time. Returns false when the session has no playable tracks. */
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

  /** Rebuilds the queue with server URIs so a cast receiver can reach a local book. */
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
