package com.audiobookshelf.app.player

/**
 * Framework-neutral playback events used by the app to react to player changes
 * without depending on ExoPlayer v2 or Media3 listener types.
 */
interface PlayerEvents {
  /** Playback state changed (e.g., IDLE, BUFFERING, READY, ENDED). */
  fun onPlaybackStateChanged(state: Int) {}

  /** Whether playback is actively playing audio. */
  fun onIsPlayingChanged(isPlaying: Boolean) {}

  /** A recoverable/non-fatal playback error occurred. */
  fun onPlayerError(message: String, errorCode: Int? = null) {}

  /** Position discontinuity occurred (e.g., seek). */
  fun onPositionDiscontinuity(isSeek: Boolean) {}
}
