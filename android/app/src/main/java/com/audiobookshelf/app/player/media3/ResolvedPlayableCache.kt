package com.audiobookshelf.app.player.media3

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

  /** Caller must hold [mutex]. Entries are appended in timestamp order, so the head expires first. */
  private fun evictExpired(nowMs: Long) {
    while (deque.isNotEmpty()) {
      val head = deque.first()
      if (nowMs - head.timestamp <= timeToLiveMillis) break
      deque.removeFirst()
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
      evictExpired(nowMs)
    }
  }
}
