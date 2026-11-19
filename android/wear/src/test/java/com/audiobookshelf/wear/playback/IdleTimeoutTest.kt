package com.audiobookshelf.wear.playback

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

class IdleTimeoutTest {
  private val scope = TestScope()
  private var idle = true
  private var timeouts = 0
  private val timeout = IdleTimeout(scope, TIMEOUT_MS, isIdle = { idle }, onTimeout = { timeouts++ })

  private fun advance(ms: Long) {
    scope.advanceTimeBy(ms)
    scope.runCurrent()
  }

  @Test
  fun `fires once the player has been idle for the timeout`() {
    timeout.restart()
    advance(TIMEOUT_MS - 1)
    assertEquals(0, timeouts)
    advance(1)
    assertEquals(1, timeouts)
    advance(TIMEOUT_MS * 3)
    assertEquals(1, timeouts)
  }

  @Test
  fun `does not fire when playback resumes`() {
    timeout.restart()
    advance(TIMEOUT_MS / 2)
    timeout.cancel()
    advance(TIMEOUT_MS * 2)
    assertEquals(0, timeouts)
  }

  @Test
  fun `restarting counts from the latest pause`() {
    timeout.restart()
    advance(TIMEOUT_MS / 2)
    timeout.restart()
    advance(TIMEOUT_MS / 2)
    assertEquals(0, timeouts)
    advance(TIMEOUT_MS / 2)
    assertEquals(1, timeouts)
  }

  @Test
  fun `waits while not idle, like while waiting for headphones`() {
    idle = false
    timeout.restart()
    advance(TIMEOUT_MS * 2)
    assertEquals(0, timeouts)
    idle = true
    advance(TIMEOUT_MS)
    assertEquals(1, timeouts)
  }

  private companion object {
    const val TIMEOUT_MS = 15 * 60_000L
  }
}
