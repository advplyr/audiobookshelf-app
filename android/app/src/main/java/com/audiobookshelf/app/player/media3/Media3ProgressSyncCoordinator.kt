package com.audiobookshelf.app.player.media3

import android.content.Context
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.server.ApiHandler

private const val TAG = "M3ProgressSyncCoordinator"

/**
 * The syncer is built after the service's managers exist, so every entry point tolerates being
 * called before [attach] — early media-button and session callbacks can arrive first.
 */
class Media3ProgressSyncCoordinator(
  private val appContext: Context,
  private val apiHandler: ApiHandler
) {
  private var syncer: Media3ProgressSyncer? = null
  private val finalSyncBarrier = FinalSyncBarrier()

  fun attach(syncer: Media3ProgressSyncer) {
    this.syncer = syncer
  }

  fun play(session: PlaybackSession) {
    syncer?.start(session)
  }

  /** [skipReason] non-null suppresses the sync, e.g. a close already draining the session. */
  fun pause(skipReason: String? = null) {
    if (skipReason != null) {
      debugLog(TAG) { "Skipping pause sync because $skipReason" }
      return
    }
    syncer?.pause {}
  }

  fun reset() {
    syncer?.reset()
  }

  fun sync(
    session: PlaybackSession?,
    reason: String,
    force: Boolean,
    beforeSync: (PlaybackSession) -> Unit,
    onSyncComplete: ((SyncResult?) -> Unit)?
  ) {
    val syncer = syncer
    if (syncer == null || session == null) {
      onSyncComplete?.invoke(null)
      return
    }

    val barrier = finalSyncBarrier.armIfCritical(reason)
    val shouldSyncServer = reason in SyncReason.CRITICAL ||
      force ||
      DeviceManager.checkConnectivity(appContext)

    beforeSync(session)
    syncer.syncNow(reason, session, shouldSyncServer) { syncResult ->
      finalSyncBarrier.complete(syncResult, barrier)
      onSyncComplete?.invoke(syncResult)
    }
  }

  suspend fun awaitFinalSync(timeoutMs: Long) = finalSyncBarrier.await(timeoutMs)

  fun closeSessionOnServer(sessionId: String) {
    apiHandler.closePlaybackSession(sessionId, DeviceManager.serverConnectionConfig) { success ->
      debugLog(TAG) { "Closed playback session $sessionId on server: $success" }
    }
  }

  /**
   * Final sync for service teardown. syncNow persists the refreshed position to the local row
   * synchronously before it attempts the network, so the progress is already durable when this
   * returns and onDestroy must not block the main thread waiting for the server round-trip.
   */
  fun syncOnDestroy(session: PlaybackSession, beforeSync: () -> Unit) {
    val syncer = syncer ?: return
    beforeSync()
    syncer.syncNow(
      SyncReason.STOP,
      session.clone(),
      shouldSyncServer = true,
      callbackOnMainThread = false
    ) {}

    if (!session.isLocal && session.id.isNotEmpty()) {
      closeSessionOnServer(session.id)
    }
  }

  fun cleanup() {
    syncer?.cleanup()
  }
}
