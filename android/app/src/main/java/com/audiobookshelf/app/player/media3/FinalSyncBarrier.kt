package com.audiobookshelf.app.player.media3

import com.audiobookshelf.app.media.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Gate that lets the service wait for the latest in-flight "final" progress sync
 * (pause/ended/close) to complete before cleanup runs. Each critical sync gets its own
 * barrier so a later close can wait on its own server sync instead of inheriting an
 * earlier pause barrier.
 */
class FinalSyncBarrier {
    private var barrier: CompletableDeferred<SyncResult?>? = null

    @Synchronized
    fun armIfCritical(reason: String): CompletableDeferred<SyncResult?>? {
        if (reason!="pause" && reason!="ended" && reason!="close") return null
        return CompletableDeferred<SyncResult?>().also { barrier = it }
    }

    @Synchronized
    fun complete(result: SyncResult?, expected: CompletableDeferred<SyncResult?>?) {
        expected ?: return
        if (!expected.isCompleted) expected.complete(result)
        if (barrier===expected) barrier = null
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
