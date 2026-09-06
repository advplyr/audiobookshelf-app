package com.audiobookshelf.app.player.media3

import android.content.Context
import com.audiobookshelf.app.data.PlayItemRequestPayload
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.isNewerThanLocalProgress
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.server.ApiHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A long pause may mean progress moved elsewhere (another device,
 * the web player), so past a threshold the server is re-checked before the auto-rewind is
 * applied, and a server session that has since expired is replaced outright.
 */
class PlaybackLifecycleHandler(
  private val appContext: Context,
  private val scope: CoroutineScope,
  private val apiHandler: ApiHandler,
  private val mediaManager: MediaManager,
  private val autoRewindDisabled: () -> Boolean,
  private val isAndroidAutoConnected: () -> Boolean,
  private val playItemRequestPayload: (forceTranscode: Boolean) -> PlayItemRequestPayload,
  private val currentPlaybackSpeed: () -> Float?,
  private val seekToSessionPosition: (PlaybackSession) -> Unit,
  private val seekBackwardWithinSession: (Long, PlaybackSession) -> Unit,
  private val prepareAndPlaySession: (PlaybackSession, Float?) -> Unit,
  private val startNewSessionFromServer: (PlaybackSession) -> Unit,
  private val resumeProgressSync: (PlaybackSession) -> Unit,
  private val closePlayback: () -> Unit
) {
  companion object {
    private const val PAUSE_LEN_BEFORE_RECHECK_MS = 30_000L
  }

  fun handlePlaybackEnded(session: PlaybackSession) {
    if (!session.isPodcastEpisode) {
      closePlayback()
      return
    }

    if (!isAndroidAutoConnected()) return
    val libraryItem = session.libraryItem ?: return
    val currentSpeed = currentPlaybackSpeed()
    // Captured before the async hops below: building the payload reads player.deviceInfo, which
    // is main-thread-only, and loadServerUserMediaProgress calls back on a network thread
    val payload = playItemRequestPayload(session.isHLS)

    mediaManager.loadServerUserMediaProgress {
      val podcast = libraryItem.media as? Podcast ?: return@loadServerUserMediaProgress
      val nextEpisode = podcast.getNextUnfinishedEpisode(libraryItem.id, mediaManager)
        ?: return@loadServerUserMediaProgress

      mediaManager.play(libraryItem, nextEpisode, payload) { nextSession ->
        if (nextSession != null) {
          onMain { prepareAndPlaySession(nextSession, currentSpeed) }
        }
      }
    }
  }

  fun handlePlaybackResumed(session: PlaybackSession?, pauseDurationMs: Long) {
    session ?: return
    val seekBackTimeMs =
      if (autoRewindDisabled()) 0L else calcPauseSeekBackTime(pauseDurationMs)

    // A server recheck adds latency and cannot succeed offline, so reserve it for long pauses.
    if (pauseDurationMs < PAUSE_LEN_BEFORE_RECHECK_MS ||
      !DeviceManager.checkConnectivity(appContext)
    ) {
      if (seekBackTimeMs > 0) {
        seekBackwardWithinSession(seekBackTimeMs, session)
      }
      return
    }

    if (session.isLocal) {
      recheckLocalProgress(session, seekBackTimeMs)
    } else {
      recheckServerSession(session, seekBackTimeMs)
    }
  }

  private fun recheckLocalProgress(session: PlaybackSession, seekBackTimeMs: Long) {
    val serverConfig = DeviceManager.getServerConnectionConfig(session.serverConnectionConfigId)
      ?: return
    apiHandler.getMediaProgress(
      session.libraryItemId ?: return,
      session.episodeId,
      serverConfig
    ) { mediaProgress ->
      val localLastUpdate =
        DeviceManager.dbManager.getLocalMediaProgress(session.localMediaProgressId)?.lastUpdate ?: 0L
      if (mediaProgress != null &&
        mediaProgress.isNewerThanLocalProgress(localLastUpdate) &&
        mediaProgress.currentTime != session.currentTime
      ) {
        onMain {
          session.currentTime = mediaProgress.currentTime
          seekToSessionPosition(session)
          if (seekBackTimeMs > 0) {
            seekBackwardWithinSession(seekBackTimeMs, session)
          }
          resumeProgressSync(session)
        }
      } else if (seekBackTimeMs > 0) {
        onMain { seekBackwardWithinSession(seekBackTimeMs, session) }
      }
    }
  }

  private fun recheckServerSession(session: PlaybackSession, seekBackTimeMs: Long) {
    apiHandler.getPlaybackSession(session.id) {
      if (it == null) {
        onMain { startNewSessionFromServer(session) }
      } else if (seekBackTimeMs > 0) {
        onMain { seekBackwardWithinSession(seekBackTimeMs, session) }
      }
    }
  }

  private fun calcPauseSeekBackTime(pauseDuration: Long): Long {
    return when {
      pauseDuration < 10_000 -> 0L
      pauseDuration < 60_000 -> 3_000L
      pauseDuration < 300_000 -> 10_000L
      pauseDuration < 1_800_000 -> 20_000L
      else -> 29_500L
    }
  }

  // ApiHandler and mediaManager call back on OkHttp threads; anything touching the player
  // has to hop to main first.
  private fun onMain(action: () -> Unit) {
    scope.launch(Dispatchers.Main) { action() }
  }
}
