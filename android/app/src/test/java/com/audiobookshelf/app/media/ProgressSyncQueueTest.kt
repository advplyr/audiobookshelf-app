package com.audiobookshelf.app.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressSyncQueueTest {
  private fun serverRequest(sessionId: String, currentTime: Double, timeListened: Long = 15) =
          SyncRequest.Server(
                  sessionId,
                  MediaProgressSyncData(timeListened, 3600.0, currentTime),
                  "Book"
          )

  /** Records sent requests. Each send waits until the test releases it, like a slow network. */
  private class FakeServer {
    val sent = mutableListOf<SyncRequest>()
    val gates = mutableListOf<CompletableDeferred<Boolean>>()

    suspend fun send(request: SyncRequest): Boolean {
      sent.add(request)
      val gate = CompletableDeferred<Boolean>()
      gates.add(gate)
      return gate.await()
    }
  }

  private fun TestScope.queue(server: FakeServer) =
          ProgressSyncQueue<SyncRequest, Boolean>(
                  TestScope(StandardTestDispatcher(testScheduler)),
                  SyncRequest::merge,
                  server::send
          )

  @Test
  fun pauseSyncIsSentAfterSlowPeriodicSync() = runTest {
    val server = FakeServer()
    val queue = queue(server)
    val results = mutableListOf<String>()

    queue.enqueue(serverRequest("s1", 100.0)) { results.add("periodic:$it") }
    advanceUntilIdle()
    queue.enqueue(serverRequest("s1", 107.0)) { results.add("pause:$it") }
    advanceUntilIdle()

    // The pause sync waits for the periodic sync instead of racing it
    assertEquals(1, server.sent.size)

    server.gates[0].complete(true)
    advanceUntilIdle()
    server.gates[1].complete(true)
    advanceUntilIdle()

    assertEquals(listOf(100.0, 107.0), server.sent.map { it.currentTime })
    assertEquals(listOf("periodic:true", "pause:true"), results)
  }

  @Test
  fun backlogIsMergedIntoNewestPosition() = runTest {
    val server = FakeServer()
    val queue = queue(server)
    val results = mutableListOf<Boolean>()

    queue.enqueue(serverRequest("s1", 100.0)) { results.add(it) }
    advanceUntilIdle()
    queue.enqueue(serverRequest("s1", 115.0, timeListened = 15)) { results.add(it) }
    queue.enqueue(serverRequest("s1", 130.0, timeListened = 15)) { results.add(it) }
    queue.enqueue(serverRequest("s1", 134.0, timeListened = 4)) { results.add(it) }

    server.gates[0].complete(true)
    advanceUntilIdle()
    server.gates[1].complete(true)
    advanceUntilIdle()

    assertEquals(2, server.sent.size)
    val merged = server.sent[1] as SyncRequest.Server
    assertEquals(134.0, merged.syncData.currentTime, 0.0)
    // Listening time from the merged requests is not lost
    assertEquals(34L, merged.syncData.timeListened)
    // Every caller gets a result
    assertEquals(listOf(true, true, true, true), results)
  }

  @Test
  fun requestsForDifferentSessionsAreNotMerged() = runTest {
    val server = FakeServer()
    val queue = queue(server)

    queue.enqueue(serverRequest("s1", 100.0)) {}
    advanceUntilIdle()
    queue.enqueue(serverRequest("s1", 110.0)) {}
    queue.enqueue(serverRequest("s2", 5.0)) {}

    repeat(3) { i ->
      advanceUntilIdle()
      server.gates[i].complete(true)
    }
    advanceUntilIdle()

    assertEquals(listOf("s1", "s1", "s2"), server.sent.map { it.sessionId })
  }

  @Test
  fun failedSyncDoesNotBlockLaterSyncs() = runTest {
    val server = FakeServer()
    val queue = queue(server)
    val results = mutableListOf<Boolean>()

    queue.enqueue(serverRequest("s1", 100.0)) { results.add(it) }
    advanceUntilIdle()
    queue.enqueue(serverRequest("s1", 107.0)) { results.add(it) }
    server.gates[0].complete(false)
    advanceUntilIdle()
    server.gates[1].complete(true)
    advanceUntilIdle()

    assertEquals(listOf(false, true), results)

    // Queue is idle again and starts a new drain for the next request
    queue.enqueue(serverRequest("s1", 120.0)) { results.add(it) }
    advanceUntilIdle()
    server.gates[2].complete(true)
    advanceUntilIdle()
    assertEquals(listOf(false, true, true), results)
  }

  @Test
  fun whenIdleRunsAfterPendingSyncs() = runTest {
    val server = FakeServer()
    val queue = queue(server)
    val events = mutableListOf<String>()

    queue.enqueue(serverRequest("s1", 100.0)) { events.add("pause sync") }
    advanceUntilIdle()
    queue.whenIdle { events.add("close session") }
    assertEquals(emptyList<String>(), events)

    server.gates[0].complete(true)
    advanceUntilIdle()
    assertEquals(listOf("pause sync", "close session"), events)

    // Idle queue runs the action right away
    queue.whenIdle { events.add("now") }
    assertEquals("now", events.last())
  }

  @Test
  fun failingCallbackDoesNotStallQueue() = runTest {
    val server = FakeServer()
    val errors = mutableListOf<Throwable>()
    val queue =
            ProgressSyncQueue<SyncRequest, Boolean>(
                    TestScope(StandardTestDispatcher(testScheduler)),
                    SyncRequest::merge,
                    server::send
            ) { errors.add(it) }
    val results = mutableListOf<Boolean>()

    queue.enqueue(serverRequest("s1", 100.0)) { throw IllegalStateException("callback failed") }
    advanceUntilIdle()
    queue.enqueue(serverRequest("s2", 5.0)) { results.add(it) }
    server.gates[0].complete(true)
    advanceUntilIdle()
    server.gates[1].complete(true)
    advanceUntilIdle()

    assertEquals(1, errors.size)
    assertEquals(listOf(true), results)
  }

  @Test
  fun mergeRejectsDifferentSessions() {
    val older = serverRequest("s1", 1.0)
    val otherSession = serverRequest("s2", 2.0)
    assertNull(SyncRequest.merge(older, otherSession))
  }
}
