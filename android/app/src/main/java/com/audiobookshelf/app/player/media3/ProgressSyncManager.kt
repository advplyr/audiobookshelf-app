package com.audiobookshelf.app.player.media3

import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.server.ApiHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ProgressSyncManager(
  private val apiHandler: ApiHandler,
  private val serviceScope: CoroutineScope,
  private val getCurrentSession: () -> PlaybackSession?,
  private val updateCurrentPosition: () -> Unit,
  private val getListenedSinceLastSaveSec: () -> Long,
  private val setListenedSinceLastSaveSec: (Long) -> Unit,
  private val getProgressSyncInFlight: () -> Boolean,
  private val setProgressSyncInFlight: (Boolean) -> Unit,
  private val hasNetworkConnectivity: () -> Boolean,
  private val saveSyncEvent: (PlaybackSession, SyncResult) -> Unit,
  private val saveIntervalSeconds: Long,
  private val debugLog: (msg: () -> String) -> Unit,
) {
  fun maybeSyncProgress(
    reason: String,
    force: Boolean,
    onComplete: ((SyncResult?) -> Unit)? = null
  ) {
    val session = getCurrentSession() ?: return
    if (getProgressSyncInFlight() && !force) return

    val hasNetwork = hasNetworkConnectivity()
    val shouldAttemptServer = hasNetwork && (!session.isLocal)

    updateCurrentPosition()

    if (!force && getListenedSinceLastSaveSec() < saveIntervalSeconds) return

    val timeListened =
      if (force && getListenedSinceLastSaveSec() <= 0L) 1L else getListenedSinceLastSaveSec()
    val durationSec = session.getTotalDuration()
    val currentTimeSec = session.currentTime

    if (!shouldAttemptServer) {
      val result = SyncResult(false, null, null)
      saveSyncEvent(session, result)
      setListenedSinceLastSaveSec(0)
      onComplete?.let { it(result) }
      return
    }

    setProgressSyncInFlight(true)
    val syncData = MediaProgressSyncData(timeListened, durationSec, currentTimeSec)
    session.syncData(syncData)

    serviceScope.launch {
      var attempt = 1
      val maxAttempts = 3
      var backoffMs = 500L
      var finalSuccess = false
      var errMsg: String? = null

      while (attempt <= maxAttempts) {
        val deferred = CompletableDeferred<Pair<Boolean, String?>>()
        apiHandler.sendProgressSync(session.id, syncData) { success, errorMsg ->
          deferred.complete(success to errorMsg)
        }
        val (success, error) = deferred.await()
        if (success) {
          finalSuccess = true
          errMsg = null
          break
        } else {
          errMsg = error
          debugLog { "Progress sync failed (attempt $attempt/$maxAttempts): ${error ?: "unknown"}" }
          if (attempt < maxAttempts) {
            kotlinx.coroutines.delay(backoffMs)
            backoffMs *= 2
          }
          attempt += 1
        }
      }

      setProgressSyncInFlight(false)
      val result = SyncResult(true, finalSuccess, errMsg)
      saveSyncEvent(session, result)
      if (finalSuccess) {
        setListenedSinceLastSaveSec(0)
      }
      onComplete?.let { it(result) }
    }
  }
}
