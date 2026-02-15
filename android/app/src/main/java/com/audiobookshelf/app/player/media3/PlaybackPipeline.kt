package com.audiobookshelf.app.player.media3

import android.content.Context
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlaybackConstants
import com.audiobookshelf.app.player.wrapper.AbsPlayerWrapper

/**
 * Creates a unified player with automatic cast support via [CastPlayer.Builder].
 *
 * Wraps ExoPlayer to enable seamless local/remote switching without manual state management.
 * Configures audiobook-optimized buffering, retry policy, and authentication.
 */
@UnstableApi
class PlaybackPipeline(
  private val context: Context,
  private val log: (msg: () -> String) -> Unit = { }
) {
  companion object {
      private const val TAG = "PlaybackPipeline"

    // Buffer settings (in milliseconds)
    private const val BUFFER_MIN_MS = 20_000
    private const val BUFFER_MAX_MS = 45_000
    private const val BUFFER_PLAYBACK_MS = 5_000
    private const val BUFFER_REBUFFER_MS = 20_000

    // Error retry settings
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val RETRY_BASE_DELAY_MS = 1_000L
  }

    /**
     * Initializes player with [CastPlayer] wrapping [ExoPlayer] for automatic cast switching.
     *
     * CastPlayer handles local/remote transitions internally, eliminating manual state transfers.
     */
    fun initializePlayer(
    enableMp3IndexSeeking: Boolean,
    speechAttributes: AudioAttributes,
    seekBackIncrementMs: Long,
    seekForwardIncrementMs: Long,
    onPlayerReady: (AbsPlayerWrapper) -> Unit,
    buildListener: () -> androidx.media3.common.Player.Listener,
  ): AbsPlayerWrapper {
    val extractorsFactory = DefaultExtractorsFactory().apply {
      setConstantBitrateSeekingEnabled(true)
      if (enableMp3IndexSeeking) setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
    }

    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
      .setUserAgent(PlaybackConstants.MEDIA3_NOTIFICATION_CHANNEL_ID)
      .setDefaultRequestProperties(
        hashMapOf("Authorization" to "Bearer ${DeviceManager.token}")
      )
    val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    val loadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(MAX_RETRY_ATTEMPTS) {
      override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val errorCount = loadErrorInfo.errorCount
        if (errorCount > MAX_RETRY_ATTEMPTS) return C.TIME_UNSET
        return (RETRY_BASE_DELAY_MS * (1 shl (errorCount - 1))).coerceAtMost(4 * RETRY_BASE_DELAY_MS)
      }
    }

    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
      .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

    val customLoadControl = DefaultLoadControl.Builder()
      .setBufferDurationsMs(
        BUFFER_MIN_MS,
        BUFFER_MAX_MS,
        BUFFER_PLAYBACK_MS,
        BUFFER_REBUFFER_MS
      )
      .build()

        val exoPlayer = ExoPlayer.Builder(context)
      .setMediaSourceFactory(mediaSourceFactory)
      .setLoadControl(customLoadControl)
      .setAudioAttributes(speechAttributes, true)
      .setHandleAudioBecomingNoisy(true)
      .setSeekBackIncrementMs(seekBackIncrementMs)
      .setSeekForwardIncrementMs(seekForwardIncrementMs)
      .setDeviceVolumeControlEnabled(true)
      .build()

        val playerWithCast = try {
            CastPlayer.Builder(context)
                .setLocalPlayer(exoPlayer)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create CastPlayer, using local-only ExoPlayer", e)
            exoPlayer
        }

    val listener = buildListener()
        val wrapper = AbsPlayerWrapper(playerWithCast, context).apply { addListener(listener) }
        onPlayerReady(wrapper)
        log { "Player initialized with cast support via CastPlayer.Builder." }
        return wrapper
  }
}
