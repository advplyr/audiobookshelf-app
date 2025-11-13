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
  fun wrapExistingPlayer(context: Context, player: Player): PlayerWrapper {
    // If Media3 feature is enabled, prefer the Media3Wrapper. The Media3Wrapper
    // will attempt to construct a Media3 ExoPlayer via reflection if Media3
    // dependencies are present. Otherwise fall back to the existing Exo wrapper.
    return if (useMedia3()) {
      createNewMedia3Wrapper(context)
    } else {
      ExoPlayerWrapper(player)
    }
  }

  fun createNewExoPlayerWrapper(context: Context): PlayerWrapper {
    // Create an ExoPlayer instance and wrap it. Keep ownership semantics similar to current code.
    val exo = ExoPlayer.Builder(context).build()
    return ExoPlayerWrapper(exo)
  }

  fun createNewMedia3Wrapper(context: Context): PlayerWrapper {
    // Construct a Media3-backed wrapper. The wrapper itself will attempt to
    // construct a Media3 ExoPlayer instance via reflection if Media3 is present
    // on the classpath. This keeps builds safe when Media3 dependencies are not
    // configured in Gradle.
    return Media3Wrapper(context)
  }

  fun useMedia3(): Boolean {
    return BuildConfig.USE_MEDIA3
  }
}
