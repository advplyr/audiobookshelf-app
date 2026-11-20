package com.audiobookshelf.app.player.media3

import com.audiobookshelf.app.media.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Each final sync needs its own barrier so cleanup never waits on an earlier pause sync. */
class FinalSyncBarrier {
  private var barrier: CompletableDeferred<SyncResult?>? = null

  @Synchronized
  fun armIfCritical(reason: String): CompletableDeferred<SyncResult?>? {
    if (reason !in SyncReason.CRITICAL) return null
    return CompletableDeferred<SyncResult?>().also { barrier = it }
  }

  @Synchronized
  fun complete(result: SyncResult?, expected: CompletableDeferred<SyncResult?>?) {
    expected ?: return
    if (!expected.isCompleted) expected.complete(result)
    if (barrier === expected) barrier = null
  }

  suspend fun await(timeoutMs: Long) {
    val current = synchronized(this) { barrier } ?: return
    if (current.isCompleted) return
    try {
      withTimeout(timeoutMs) { current.await() }
    } catch (_: Exception) {
    }
  }
}
