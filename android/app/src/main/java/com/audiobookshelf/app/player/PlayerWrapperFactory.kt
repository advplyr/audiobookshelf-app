package com.audiobookshelf.app.player

import android.content.Context
import com.audiobookshelf.app.BuildConfig
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player

/**
 * Factory to obtain PlayerWrapper instances. For now we provide helpers to wrap existing
 * ExoPlayer/Player instances and to create a new ExoPlayer instance and wrap it.
 *
 * When the Media3 feature flag is enabled we will extend this factory to create Media3-backed
 * players. To keep this migration low-risk the current implementation does not attempt to
 * construct Media3 players (those artifacts are added conditionally in Gradle).
 */
object PlayerWrapperFactory {
  fun wrapExistingPlayer(player: Player): PlayerWrapper {
    // Keep behaviour identical for now: always wrap the provided player
    return ExoPlayerWrapper(player)
  }

  fun createNewExoPlayerWrapper(context: Context): PlayerWrapper {
    // Create an ExoPlayer instance and wrap it. Keep ownership semantics similar to current code.
    val exo = ExoPlayer.Builder(context).build()
    return ExoPlayerWrapper(exo)
  }

  fun useMedia3(): Boolean {
    return BuildConfig.USE_MEDIA3
  }
}
