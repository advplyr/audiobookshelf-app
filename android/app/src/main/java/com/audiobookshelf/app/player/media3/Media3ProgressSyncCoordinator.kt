package com.audiobookshelf.app.player.media3

import android.content.Context
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.media.UnifiedMediaProgressSyncer
import com.audiobookshelf.app.server.ApiHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns progress-sync state for the Media3 service: the syncer itself, the final-sync barrier,
 * and the rules for when a sync reaches the server.
 *
 * The syncer is built after the service's managers exist, so every entry point tolerates being
 * called before [attach] — early media-button and session callbacks can arrive first.
 */
class Media3ProgressSyncCoordinator(
  private val appContext: Context,
  private val apiHandler: ApiHandler,
  private val debug: (() -> String) -> Unit
) {
  private var syncer: UnifiedMediaProgressSyncer? = null
  private val finalSyncBarrier = FinalSyncBarrier()

  fun attach(syncer: UnifiedMediaProgressSyncer) {
    this.syncer = syncer
  }

  fun play(session: PlaybackSession) {
    syncer?.play(session)
  }

  /** [skipReason] non-null suppresses the sync, e.g. a close already draining the session. */
  fun pause(skipReason: String? = null) {
    if (skipReason != null) {
      debug { "Skipping pause sync because $skipReason" }
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
    val shouldSyncServer = when (reason) {
      "pause", "ended", "close" -> true
      else -> force || DeviceManager.checkConnectivity(appContext)
    }

    beforeSync(session)
    syncer.syncNow(reason, session, shouldSyncServer) { syncResult ->
      finalSyncBarrier.complete(syncResult, barrier)
      onSyncComplete?.invoke(syncResult)
    }
  }

  suspend fun awaitFinalSync(timeoutMs: Long) = finalSyncBarrier.await(timeoutMs)

  fun closeSessionOnServer(sessionId: String) {
    apiHandler.closePlaybackSession(sessionId, DeviceManager.serverConnectionConfig) { success ->
      debug { "Closed playback session $sessionId on server: $success" }
    }
  }

  /**
   * Blocking final sync for service teardown. onDestroy cannot suspend and the process may die
   * immediately after, so this waits off the main thread rather than posting a callback.
   */
  fun syncOnDestroy(session: PlaybackSession, timeoutSec: Long, beforeSync: () -> Unit) {
    val syncer = syncer ?: return
    beforeSync()
    val latch = CountDownLatch(1)
    syncer.syncNow(
      "stop",
      session.clone(),
      shouldSyncServer = true,
      callbackOnMainThread = false
    ) { latch.countDown() }
    latch.await(timeoutSec, TimeUnit.SECONDS)

    if (!session.isLocal && session.id.isNotEmpty()) {
      closeSessionOnServer(session.id)
    }
  }

  fun cleanup() {
    syncer?.cleanup()
  }
}
