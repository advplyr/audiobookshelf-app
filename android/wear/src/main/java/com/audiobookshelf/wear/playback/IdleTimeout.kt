package com.audiobookshelf.wear.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calls [onTimeout] once the player has stayed idle for [timeoutMs]. If it isn't idle when the time
 * is up (e.g. still waiting for headphones), it waits another [timeoutMs] and checks again.
 */
class IdleTimeout(
  private val scope: CoroutineScope,
  private val timeoutMs: Long,
  private val isIdle: () -> Boolean,
  private val onTimeout: () -> Unit
) {
  private var job: Job? = null

  /** Starts counting from now. */
  fun restart() {
    job?.cancel()
    job = scope.launch {
      while (true) {
        delay(timeoutMs)
        if (isIdle()) break
      }
      job = null
      onTimeout()
    }
  }

  fun cancel() {
    job?.cancel()
    job = null
  }
}
