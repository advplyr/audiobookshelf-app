package com.audiobookshelf.app.player

import android.content.Context
import com.audiobookshelf.app.BuildConfig
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player

/**
 * Factory to obtain PlayerWrapper instances based on the Media3 feature flag.
 * When Media3 is disabled, returns ExoPlayer v2 wrappers (baseline).
 * When Media3 is enabled, returns Media3 ExoPlayer wrappers (migration target).
 */
object PlayerWrapperFactory {
  /**
   * Wraps an existing Player instance. Currently ignores the passed player when Media3 is enabled
   * and constructs a new Media3 ExoPlayer instead (this is a temporary migration strategy).
   */
  fun wrapExistingPlayer(context: Context, player: Player): PlayerWrapper {
    return if (useMedia3()) {
      createNewMedia3Wrapper(context)
    } else {
      ExoPlayerWrapper(player)
    }
  }

  fun createNewExoPlayerWrapper(context: Context): PlayerWrapper {
    val exo = ExoPlayer.Builder(context).build()
    return ExoPlayerWrapper(exo)
  }

  fun createNewMedia3Wrapper(context: Context): PlayerWrapper {
    return Media3Wrapper(context)
  }

  fun useMedia3(): Boolean {
    return BuildConfig.USE_MEDIA3
  }
}
