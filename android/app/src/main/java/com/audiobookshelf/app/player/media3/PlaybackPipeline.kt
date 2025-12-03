package com.audiobookshelf.app.player.media3

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
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
  private val debug: (msg: () -> String) -> Unit = { }
) {
  var castCoordinator: Media3CastCoordinator? = null
    private set
  var localPlayer: AbsPlayerWrapper? = null
    private set
  var playerListener: androidx.media3.common.Player.Listener? = null
    private set

  fun initializeCast(): Media3CastCoordinator? {
    castCoordinator = Media3CastCoordinator(
      context = context,
      scope = scope,
      speechAudioAttributes = speechAudioAttributes,
      onSwitchToCast = onSwitchToCast,
      onSwitchToLocal = onSwitchToLocal,
      onPauseLocalForCasting = pauseLocalForCasting,
      debug = debug
    ).also { it.initialize() }
    return castCoordinator
  }

  fun initializeLocalPlayer(
    enableMp3IndexSeeking: Boolean,
    speechAttributes: AudioAttributes,
    jumpBackwardMs: Long,
    jumpForwardMs: Long,
    onPlayerReady: (AbsPlayerWrapper) -> Unit,
    buildListener: () -> androidx.media3.common.Player.Listener,
  ): AbsPlayerWrapper {
    val extractorsFactory = DefaultExtractorsFactory().apply {
      if (enableMp3IndexSeeking) setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
    }
    val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)

    val coreExoPlayer = ExoPlayer.Builder(context)
      .setMediaSourceFactory(mediaSourceFactory)
      .setAudioAttributes(speechAttributes, true)
      .setHandleAudioBecomingNoisy(true)
      .setSeekBackIncrementMs(jumpBackwardMs)
      .setSeekForwardIncrementMs(jumpForwardMs)
      .setDeviceVolumeControlEnabled(true)
      .build()

    playerListener = buildListener()
    localPlayer = AbsPlayerWrapper(coreExoPlayer, context).apply { addListener(playerListener!!) }
    onPlayerReady(localPlayer!!)
    debug { "Local player initialized via pipeline." }
    return localPlayer!!
  }
}
