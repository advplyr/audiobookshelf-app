package com.audiobookshelf.app.player.media3

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlaybackConstants
import com.audiobookshelf.app.player.wrapper.AbsPlayerWrapper
import kotlinx.coroutines.CoroutineScope

@UnstableApi
class PlaybackPipeline(
  private val context: Context,
  private val scope: CoroutineScope,
  private val speechAudioAttributes: AudioAttributes,
  private val onSwitchToCast: (AbsPlayerWrapper) -> Unit,
  private val onSwitchToLocal: () -> Unit,
  private val pauseLocalForCasting: () -> Unit,
  private val log: (msg: () -> String) -> Unit = { }
) {
  var castCoordinator: Media3CastCoordinator? = null
    private set
  var localPlayer: AbsPlayerWrapper? = null
    private set
  var playerListener: androidx.media3.common.Player.Listener? = null
    private set

  companion object {
    // Buffer settings (in milliseconds)
    private const val BUFFER_MIN_MS = 20_000
    private const val BUFFER_MAX_MS = 45_000
    private const val BUFFER_PLAYBACK_MS = 5_000
    private const val BUFFER_REBUFFER_MS = 20_000
  }

  fun initializeCast(): Media3CastCoordinator? {
    castCoordinator = Media3CastCoordinator(
      context = context,
      scope = scope,
      speechAudioAttributes = speechAudioAttributes,
      onSwitchToCast = onSwitchToCast,
      onSwitchToLocal = onSwitchToLocal,
      pauseLocalForCasting = pauseLocalForCasting,
      debug = log
    ).also { it.initialize() }
    return castCoordinator
  }

  fun initializeLocalPlayer(
    enableMp3IndexSeeking: Boolean,
    speechAttributes: AudioAttributes,
    seekBackIncrementMs: Long,
    seekForwardIncrementMs: Long,
    onPlayerReady: (AbsPlayerWrapper) -> Unit,
    buildListener: () -> androidx.media3.common.Player.Listener,
  ): AbsPlayerWrapper {
    val extractorsFactory = DefaultExtractorsFactory().apply {
      if (enableMp3IndexSeeking) setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
    }

    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
      .setUserAgent(PlaybackConstants.MEDIA3_NOTIFICATION_CHANNEL_ID)
      .setDefaultRequestProperties(
        hashMapOf("Authorization" to "Bearer ${DeviceManager.token}")
      )
    val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

    val customLoadControl = DefaultLoadControl.Builder()
      .setBufferDurationsMs(
        BUFFER_MIN_MS,
        BUFFER_MAX_MS,
        BUFFER_PLAYBACK_MS,
        BUFFER_REBUFFER_MS
      )
      .build()

    val coreExoPlayer = ExoPlayer.Builder(context)
      .setMediaSourceFactory(mediaSourceFactory)
      .setLoadControl(customLoadControl)
      .setAudioAttributes(speechAttributes, true)
      .setHandleAudioBecomingNoisy(true)
      .setSeekBackIncrementMs(seekBackIncrementMs)
      .setSeekForwardIncrementMs(seekForwardIncrementMs)
      .setDeviceVolumeControlEnabled(true)
      .build()

    val listener = buildListener()
    playerListener = listener

    val player = AbsPlayerWrapper(coreExoPlayer, context).apply { addListener(listener) }
    localPlayer = player
    onPlayerReady(player)
    log { "Local player initialized via pipeline." }
    return player
  }
}
