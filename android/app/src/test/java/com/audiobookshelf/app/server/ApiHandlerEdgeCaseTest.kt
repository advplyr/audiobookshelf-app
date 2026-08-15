package com.audiobookshelf.app.server

import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.models.User
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Failure-path and callback-cardinality coverage for `ApiHandler`. Every callback here is a
 * public contract the rest of the app relies on firing exactly once; a callback that silently
 * never fires reads as a hang, and one that fires twice can double-write local state.
 */
class ApiHandlerEdgeCaseTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var server: MockWebServer
  private lateinit var handler: ApiHandler

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    DeviceManager.serverConnectionConfig =
            ServerConnectionConfig(
                    "test-server", 0, "Test", server.url("/").toString().trimEnd('/'),
                    "2.17.0", "user-1", "username", "test-token", null
            )
    handler = AbsTestEnvironment.apiHandler()
  }

  @After
  fun tearDown() {
    server.shutdown()
    // Otherwise DeviceManager.serverConnectionConfig keeps pointing at this now-shut-down
    // MockWebServer instance for whichever test class the shared JVM runs next.
  }

  private fun getCurrentUserOnce(): Pair<User?, Int> {
    val latch = CountDownLatch(1)
    val callCount = AtomicInteger(0)
    var user: User? = null
    handler.getCurrentUser {
      user = it
      callCount.incrementAndGet()
      latch.countDown()
    }
    assertTrue("callback was never invoked", latch.await(5, TimeUnit.SECONDS))
    // Give a second invocation a chance to arrive before we declare the callback single-shot.
    Thread.sleep(200)
    return user to callCount.get()
  }

  @Test
  fun `401 with no stored refresh token reports failure without retrying`() {
    server.enqueue(MockResponse().setResponseCode(401))

    val (user, callCount) = getCurrentUserOnce()

    assertNull(user)
    assertEquals(1, callCount)
    // No refresh token in secure storage -> handleTokenRefresh bails before ever calling
    // /auth/refresh, so only the original /api/me request should have been sent.
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `500 response reports failure without throwing`() {
    server.enqueue(MockResponse().setResponseCode(500))

    val (user, callCount) = getCurrentUserOnce()

    assertNull(user)
    assertEquals(1, callCount)
  }

  @Test
  fun `404 response reports failure`() {
    server.enqueue(MockResponse().setResponseCode(404))

    val (user, _) = getCurrentUserOnce()

    assertNull(user)
  }

  @Test
  fun `429 response reports failure`() {
    server.enqueue(MockResponse().setResponseCode(429))

    val (user, _) = getCurrentUserOnce()

    assertNull(user)
  }

  @Test
  fun `malformed JSON body is reported as an error rather than crashing`() {
    server.enqueue(MockResponse().setBody("{not valid json"))

    val (user, callCount) = getCurrentUserOnce()

    assertNull(user)
    assertEquals(1, callCount)
  }

  @Test
  fun `empty body is reported as an error rather than crashing`() {
    server.enqueue(MockResponse().setBody(""))

    val (user, _) = getCurrentUserOnce()

    assertNull(user)
  }

  @Test
  fun `literal OK body still invokes the callback`() {
    // "OK" is special-cased in makeRequest to short-circuit to an empty JSObject rather than
    // attempting to JSON-parse the literal text "OK". closePlaybackSession's callback ignores the
    // response body entirely, so this isolates the "OK" short-circuit from the separate
    // deserialization-failure defect documented below.
    server.enqueue(MockResponse().setBody("OK"))

    val latch = CountDownLatch(1)
    handler.closePlaybackSession("session-1", null) { latch.countDown() }

    assertTrue(latch.await(5, TimeUnit.SECONDS))
  }

  @Test
  fun `connection failure is reported as a failure callback`() {
    server.shutdown()

    val (user, callCount) = getCurrentUserOnce()

    assertNull(user)
    assertEquals(1, callCount)
  }

  @Test
  fun `pingServer times out against a server that never responds`() {
    server.enqueue(
            MockResponse().setBody("""{"success":"true"}""").setSocketPolicy(SocketPolicy.NO_RESPONSE)
    )
    val config = DeviceManager.serverConnectionConfig!!

    val latch = CountDownLatch(1)
    var result: Boolean? = null
    handler.pingServer(config) {
      result = it
      latch.countDown()
    }

    // pingClient has a hardcoded 3-second call timeout; allow a margin above it.
    assertTrue("pingServer never called back", latch.await(6, TimeUnit.SECONDS))
    assertEquals(false, result)
  }

  @Test
  fun `pingServer succeeds when success is reported as a string`() {
    server.enqueue(MockResponse().setBody("""{"success":"true"}"""))
    val config = DeviceManager.serverConnectionConfig!!

    val latch = CountDownLatch(1)
    var result: Boolean? = null
    handler.pingServer(config) {
      result = it
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))

    assertEquals(true, result)
  }

  @Test
  fun `pingServer reports failure rather than crashing when success is a JSON boolean`() {
    // org.json's JSONObject#getString throws JSONException when the stored value isn't literally
    // a String (no boolean/number coercion). Capacitor's JSObject#getString (which is what
    // ApiHandler actually calls, not raw org.json) catches that JSONException internally and
    // returns the default (null) - so a boolean "success" field reads as absent and pingServer
    // treats it as a failed ping rather than hanging or crashing. This test locks in that
    // protective behavior; it is not itself a defect.
    server.enqueue(MockResponse().setBody("""{"success":true}"""))
    val config = DeviceManager.serverConnectionConfig!!

    val latch = CountDownLatch(1)
    var result: Boolean? = null
    handler.pingServer(config) {
      result = it
      latch.countDown()
    }

    assertTrue("pingServer never called back", latch.await(3, TimeUnit.SECONDS))
    assertEquals(false, result)
  }

  @Test
  fun `a response that fails to deserialize into the expected type drops the callback entirely`() {
    // Valid JSON, but missing the non-nullable "username" Jackson requires for User. Nothing in
    // getCurrentUser's response handler catches deserialization exceptions, so - like the
    // pingServer case above - the callback never fires: not with the user, not with null, not at
    // all. Callers waiting on this callback (or a UI spinner gated on it) hang indefinitely.
    server.enqueue(MockResponse().setBody("""{"id":"u1","mediaProgress":[]}"""))

    val latch = CountDownLatch(1)
    handler.getCurrentUser { latch.countDown() }

    assertTrue(
            "getCurrentUser's callback should fire (with an error) even when the response body " +
                    "doesn't match the expected shape",
            latch.await(3, TimeUnit.SECONDS)
    )
  }

  @Test
  fun `custom headers configured for a server connection are never sent`() {
    // ServerConnectionConfig.customHeaders exists specifically so users can configure headers
    // required by a reverse proxy in front of their server (see AbsDatabase's
    // ServerConnConfigPayload). getRequest/postRequest/patchRequest only ever add the
    // Authorization header - config.customHeaders is read nowhere in ApiHandler, so every
    // configured custom header is silently dropped from every request.
    server.enqueue(MockResponse().setBody("""{"id":"u1","username":"jane","mediaProgress":[]}"""))
    DeviceManager.serverConnectionConfig =
            ServerConnectionConfig(
                    "test-server", 0, "Test", server.url("/").toString().trimEnd('/'),
                    "2.17.0", "user-1", "username", "test-token",
                    mapOf("X-Proxy-Auth" to "secret")
            )

    getCurrentUserOnce()

    val request = server.takeRequest(5, TimeUnit.SECONDS)
    assertEquals(
            "expected the configured custom header to be sent with every request",
            "secret",
            request?.getHeader("X-Proxy-Auth")
    )
  }
}
