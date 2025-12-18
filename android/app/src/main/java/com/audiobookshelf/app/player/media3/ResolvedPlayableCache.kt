package com.audiobookshelf.app.player.media3

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LRU cache for Media3BrowseTree.ResolvedPlayable items with TTL and size limits.
 * Thread-safe using Mutex for concurrent access, automatically cleans expired entries.
 */
class ResolvedPlayableCache(
  private val timeToLiveMillis: Long,
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

  private suspend fun cleanup(nowMs: Long) {
    mutex.withLock {
      while (deque.isNotEmpty()) {
        val head = deque.firstOrNull() ?: break
        if (nowMs - head.timestamp > timeToLiveMillis) {
          deque.removeFirst()
        } else {
          break
        }
      }
    }
  }

  suspend fun get(mediaId: String, preferCastUris: Boolean): Media3BrowseTree.ResolvedPlayable? {
    val entryKey = key(mediaId, preferCastUris)
    val nowMs = System.currentTimeMillis()
    return mutex.withLock {
      val entry = deque.firstOrNull { it.key == entryKey } ?: return@withLock null
      if (nowMs - entry.timestamp > timeToLiveMillis) {
        null
      } else {
        entry.value.copy(session = entry.value.session.clone())
      }
    }
  }

  suspend fun put(
    mediaId: String,
    preferCastUris: Boolean,
    resolvedPlayable: Media3BrowseTree.ResolvedPlayable
  ) {
    val entryKey = key(mediaId, preferCastUris)
    val playableCopy = resolvedPlayable.copy(session = resolvedPlayable.session.clone())
    val nowMs = System.currentTimeMillis()
    mutex.withLock {
      deque.removeAll { it.key == entryKey }
      deque.addLast(Entry(entryKey, playableCopy, nowMs))
      while (deque.size > limit) deque.removeFirst()
    }
    cleanup(nowMs)
  }
}
