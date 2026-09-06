package com.audiobookshelf.app.player.media3

import android.util.Log
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.MediaEventManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A direct-play stream can fail for reasons the server can work around (unsupported codec,
 * a container the device rejects), so the first failure re-requests the item with
 * forceTranscode. Only one retry per session, tracked by id: without that guard a transcode
 * that also fails would request another, looping.
 */
class PlaybackErrorHandler(
  private val scope: CoroutineScope,
  private val requestTranscodeSession: suspend (libraryItemId: String, episodeId: String?) -> PlaybackSession?,
  private val playFallbackSession: (PlaybackSession) -> Unit,
  private val failPlayback: (String) -> Unit
) {
  companion object {
    private const val TAG = "PlaybackErrorHandler"
    private const val FAILURE_MESSAGE = "Unable to play this item"
  }

  private var attemptedSessionId: String? = null

  fun resetFallbackAttempt() {
    attemptedSessionId = null
  }

  fun handleError(session: PlaybackSession?) {
    session ?: return
    if (!session.isDirectPlay || session.isLocal) return
    if (attemptedSessionId == session.id) return

    attemptedSessionId = session.id
    scope.launch {
      try {
        val fallbackSession = requestTranscodeSession(
          session.libraryItemId ?: return@launch,
          session.episodeId
        )
        if (fallbackSession == null) {
          Log.w(TAG, "transcode fallback failed for session=${session.id}")
          failPlayback(FAILURE_MESSAGE)
          return@launch
        }
        playFallbackSession(fallbackSession)
      } catch (e: Exception) {
        Log.e(TAG, "Exception during transcode fallback", e)
        failPlayback(FAILURE_MESSAGE)
      }
    }
  }

  fun handleFatalError(message: String) {
    failPlayback(message)
  }
}
