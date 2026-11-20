package com.audiobookshelf.app.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sends progress syncs one at a time, in the order they were enqueued.
 *
 * Periodic syncs and the pause/stop sync used to be separate async requests, so a slow periodic
 * sync (or one retried after a token refresh) could reach the server after the pause sync and
 * overwrite the pause position with an older one.
 *
 * [send] should report failures through its result rather than throw.
 *
 * While a request is in flight, a new payload is merged into the last pending one when [merge]
 * allows it, so a backlog collapses into a single request carrying the newest position.
 */
class ProgressSyncQueue<T, R>(
        private val scope: CoroutineScope,
        private val merge: (older: T, newer: T) -> T?,
        private val send: suspend (T) -> R,
        private val onCallbackError: (Throwable) -> Unit = {}
) {
  private class Entry<T, R>(val payload: T, val callbacks: List<(R) -> Unit>)

  private val lock = Any()
  private val pending = ArrayDeque<Entry<T, R>>()
  private val idleActions = mutableListOf<() -> Unit>()
  private var draining = false

  fun enqueue(payload: T, onDone: (R) -> Unit) {
    synchronized(lock) {
      val last = pending.lastOrNull()
      val merged = last?.let { merge(it.payload, payload) }
      if (last != null && merged != null) {
        pending.removeLast()
        pending.addLast(Entry(merged, last.callbacks + onDone))
      } else {
        pending.addLast(Entry(payload, listOf(onDone)))
      }
      if (draining) return
      draining = true
    }
    scope.launch { drain() }
  }

  /** Runs [action] once everything enqueued so far has been sent, right away if the queue is idle. */
  fun whenIdle(action: () -> Unit) {
    synchronized(lock) {
      if (draining) {
        idleActions.add(action)
        return
      }
    }
    runCallback { action() }
  }

  private suspend fun drain() {
    while (true) {
      var actions: List<() -> Unit> = emptyList()
      val entry =
              synchronized(lock) {
                pending.removeFirstOrNull().also {
                  if (it == null) {
                    draining = false
                    actions = idleActions.toList()
                    idleActions.clear()
                  }
                }
              }
      if (entry == null) {
        actions.forEach { runCallback(it) }
        return
      }

      val result =
              try {
                send(entry.payload)
              } catch (e: Throwable) {
                // Let the next enqueue start a new drain instead of stalling the queue forever
                synchronized(lock) { draining = false }
                throw e
              }
      entry.callbacks.forEach { callback -> runCallback { callback(result) } }
    }
  }

  // A failing callback must not stop the queue or skip the callbacks after it
  private fun runCallback(callback: () -> Unit) {
    try {
      callback()
    } catch (e: Exception) {
      onCallbackError(e)
    }
  }
}
