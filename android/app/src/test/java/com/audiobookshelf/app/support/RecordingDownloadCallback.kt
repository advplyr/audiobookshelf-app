package com.audiobookshelf.app.support

import com.audiobookshelf.app.managers.DownloadItemManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue

/**
 * Records what `InternalDownloadManager` reports back, for the two suites that drive it
 * (`InternalDownloadManagerTest`, `DownloadIntegrityTest`). It lived as a byte-identical private
 * class in both before this was extracted.
 *
 * Everything crossing a thread boundary is either atomic or guarded by a latch. `onProgress` and
 * `onComplete` run on OkHttp's dispatcher thread while the test body reads from the JUnit thread,
 * and the *second* completion - the one a cardinality assertion exists to catch - arrives after
 * [completion] has already been released, so it has no happens-before edge to the reader unless the
 * counter is atomic and the wait is on a latch rather than a sleep.
 */
class RecordingDownloadCallback : DownloadItemManager.InternalProgressCallback {
  private val completion = CountDownLatch(1)
  /** Released by a *second* completion, so [assertNoFurtherCompletion] can wait on an event. */
  private val duplicateCompletion = CountDownLatch(2)
  private val completions = AtomicInteger(0)
  private val failedFlag = java.util.concurrent.atomic.AtomicBoolean(false)

  /** Synchronized: appended on the download thread, read on the test thread. */
  val progressUpdates: MutableList<Pair<Long, Long>> =
          java.util.Collections.synchronizedList(mutableListOf())

  val failed: Boolean
    get() = failedFlag.get()

  val completionCount: Int
    get() = completions.get()

  override fun onProgress(totalBytesWritten: Long, progress: Long) {
    progressUpdates += totalBytesWritten to progress
  }

  override fun onComplete(failed: Boolean) {
    failedFlag.set(failed)
    completions.incrementAndGet()
    completion.countDown()
    duplicateCompletion.countDown()
  }

  fun awaitCompletion() {
    assertTrue("download callback timed out", completion.await(5, TimeUnit.SECONDS))
  }

  /**
   * Waits for a *second* completion and asserts none arrives. Replaces a `Thread.sleep(200)`: this
   * returns immediately when the duplicate does show up (failing with a real count) and otherwise
   * costs the timeout only, rather than paying a fixed delay on every run.
   */
  fun assertNoFurtherCompletion() {
    val duplicated = duplicateCompletion.await(500, TimeUnit.MILLISECONDS)
    assertTrue(
            "expected exactly one completion callback, got ${completions.get()}",
            !duplicated && completions.get() == 1
    )
  }

  /** A defensive copy, so callers can iterate without holding the synchronized list's lock. */
  fun progressPercentages(): List<Long> = progressUpdates.toList().map { it.second }
}
