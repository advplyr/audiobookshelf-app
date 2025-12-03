package com.audiobookshelf.app.player.media3

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ResolvedPlayableCache(
  private val ttlMs: Long,
  private val limit: Int
) {
  private data class Entry(
    val key: String,
    val value: Media3BrowseTree.ResolvedPlayable,
    val timestamp: Long
  )

  private val deque = ArrayDeque<Entry>()
  private val mutex = Mutex()

  private fun key(mediaId: String, preferCastUris: Boolean) = "$mediaId|cast=$preferCastUris"

  private suspend fun cleanup(nowMs: Long = System.currentTimeMillis()) {
    mutex.withLock {
      while (deque.isNotEmpty()) {
        val head = deque.firstOrNull() ?: break
        if (nowMs - head.timestamp > ttlMs) {
          deque.removeFirst()
        } else {
          break
        }
      }
    }
  }

  suspend fun get(mediaId: String, preferCastUris: Boolean): Media3BrowseTree.ResolvedPlayable? {
    cleanup()
    val k = key(mediaId, preferCastUris)
    return mutex.withLock {
      val entry = deque.firstOrNull { it.key == k } ?: return@withLock null
      entry.value.copy(session = entry.value.session.clone())
    }
  }

  suspend fun put(
    mediaId: String,
    preferCastUris: Boolean,
    resolvedPlayable: Media3BrowseTree.ResolvedPlayable
  ) {
    cleanup()
    val k = key(mediaId, preferCastUris)
    val copy = resolvedPlayable.copy(session = resolvedPlayable.session.clone())
    mutex.withLock {
      deque.removeAll { it.key == k }
      deque.addLast(Entry(k, copy, System.currentTimeMillis()))
      while (deque.size > limit) deque.removeFirst()
    }
  }
}
