package com.audiobookshelf.app.server

import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Credential preservation across transient failures - the auth/transient-HTTP issue cluster
 * (see `TESTING.md` §10): [#1908](https://github.com/advplyr/audiobookshelf-app/issues/1908),
 * [#1900](https://github.com/advplyr/audiobookshelf-app/issues/1900),
 * [#1901](https://github.com/advplyr/audiobookshelf-app/issues/1901). Reported contract:
 *
 * > *A temporary 404/5xx/network failure must not delete credentials.*
 *
 * `ApiHandlerEdgeCaseTest` already covers what each failure *reports*; what was missing, and is
 * what this class adds, is what each failure *destroys*. The single place `ApiHandler` deletes
 * credentials is `handleRefreshFailure` (`ApiHandler.kt:390`), reached only from the 401 ->
 * `/auth/refresh` path, so that path is where these specs concentrate.
 *
 * The 401 -> refresh -> retry **success** path stays blocked: it needs a real `AndroidKeyStore` to
 * write the new tokens back through `SecureStorage`. Only the failure branches are asserted here,
 * with `SecureStorage` mocked so a stored refresh token exists to be found.
 */
class ApiHandlerCredentialTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var server: MockWebServer
  private lateinit var handler: ApiHandler
  private lateinit var secureStorage: SecureStorage

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    setActiveConfig(server.url("/").toString().trimEnd('/'))
    handler = AbsTestEnvironment.apiHandler()
    secureStorage = mockk(relaxed = true)
    AbsTestEnvironment.injectField(handler, "secureStorage", secureStorage)
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun setActiveConfig(address: String) {
    val config =
            ServerConnectionConfig(
                    "test-server", 0, "Test", address, "2.17.0", "user-1", "username",
                    "test-token", null
            )
    DeviceManager.deviceData.serverConnectionConfigs.add(config)
    DeviceManager.deviceData.lastServerConnectionConfigId = config.id
    DeviceManager.serverConnectionConfig = config
  }

  private fun getCurrentUserOnce() {
    val latch = CountDownLatch(1)
    handler.getCurrentUser { latch.countDown() }
    assertTrue("callback was never invoked", latch.await(5, TimeUnit.SECONDS))
  }

  private fun assertCredentialsIntact(situation: String) {
    assertNotNull("$situation must not clear the active server connection", DeviceManager.serverConnectionConfig)
    assertEquals("$situation must not discard the access token", "test-token", DeviceManager.serverConnectionConfig?.token)
    assertEquals(
            "$situation must not forget which server was last connected",
            "test-server",
            DeviceManager.deviceData.lastServerConnectionConfigId
    )
    assertEquals(1, DeviceManager.deviceData.serverConnectionConfigs.size)
  }

  // --- Non-401 failures: these already behave, and are pinned so they keep behaving -----------

  @Test
  fun `a 404 leaves the stored credentials untouched`() {
    server.enqueue(MockResponse().setResponseCode(404))

    getCurrentUserOnce()

    assertCredentialsIntact("a 404")
  }

  @Test
  fun `a 429 leaves the stored credentials untouched`() {
    server.enqueue(MockResponse().setResponseCode(429))

    getCurrentUserOnce()

    assertCredentialsIntact("a 429")
  }

  @Test
  fun `a 500 leaves the stored credentials untouched`() {
    server.enqueue(MockResponse().setResponseCode(500))

    getCurrentUserOnce()

    assertCredentialsIntact("a 500")
  }

  @Test
  fun `a 502 from a reverse proxy leaves the stored credentials untouched`() {
    server.enqueue(MockResponse().setResponseCode(502))

    getCurrentUserOnce()

    assertCredentialsIntact("a 502")
  }

  @Test
  fun `a dropped connection leaves the stored credentials untouched`() {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

    getCurrentUserOnce()

    assertCredentialsIntact("a dropped connection")
  }

  @Test
  fun `a malformed response body leaves the stored credentials untouched`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("{not json"))

    getCurrentUserOnce()

    assertCredentialsIntact("a malformed body")
  }

  @Test
  fun `a 401 with no refresh token available does not clear the connection`() {
    // handleTokenRefresh bails before contacting /auth/refresh when secure storage has nothing,
    // so handleRefreshFailure is never reached and nothing is cleared.
    every { secureStorage.getRefreshToken(any()) } returns null
    server.enqueue(MockResponse().setResponseCode(401))

    getCurrentUserOnce()

    assertCredentialsIntact("a 401 with no refresh token")
    assertEquals(1, server.requestCount)
  }

  /**
   * 403 is not 401. The server answers 403 for a permissions problem on an otherwise valid token
   * (a library the user may not read), so it must never take the refresh-and-logout path - the
   * user would be signed out for opening the wrong shelf.
   */
  @Test
  fun `a 403 leaves the stored credentials untouched and does not attempt a refresh`() {
    every { secureStorage.getRefreshToken(any()) } returns "refresh-token"
    server.enqueue(MockResponse().setResponseCode(403))

    getCurrentUserOnce()

    assertCredentialsIntact("a 403")
    assertEquals("403 must not trigger the refresh flow", 1, server.requestCount)
  }

  /**
   * A refresh that answers 200 with a body the client cannot read is not a successful refresh. It
   * must be treated as a failure rather than leaving the session in a half-updated state where the
   * old access token was discarded and no new one was stored.
   */
  @Test
  fun `a refresh answering 200 with an unreadable body does not report success`() {
    every { secureStorage.getRefreshToken(any()) } returns "refresh-token"
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setResponseCode(200).setBody("{not json"))

    getCurrentUserOnce()

    // Either outcome is defensible for the *session* (the refresh token may or may not still be
    // good), but the access token must never be replaced by something that was never parsed.
    val token = DeviceManager.serverConnectionConfig?.token
    assertTrue(
            "the access token must be either preserved or cleared, never set from an unparsed body, got $token",
            token == null || token == "test-token"
    )
  }

  // --- Refresh failures: the reported defect ---------------------------------------------------

  /**
   * Inputs: a stored refresh token exists; the original request gets a 401, and the follow-up
   * `POST /auth/refresh` **cannot connect** (server down, captive portal, VPN transition, phone
   * back on mobile data). This is #1908/#1900's report: users logged out during a temporary
   * network or server problem, having to re-enter credentials.
   *
   * Expected: a transport-level failure is retryable, so the session is kept and the request
   * simply fails. Nothing about a connection error says the credentials are invalid.
   *
   * Observed: `handleTokenRefresh`'s `onFailure` calls `handleRefreshFailure`
   * (`ApiHandler.kt:216`), which clears `DeviceManager.serverConnectionConfig`, nulls
   * `lastServerConnectionConfigId`, and persists that (`:393-395`) - the user is logged out by a
   * dropped TCP connection.
   */
  @Test
  fun `a refresh request that cannot connect must not log the user out`() {
    every { secureStorage.getRefreshToken(any()) } returns "refresh-token"
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

    getCurrentUserOnce()

    assertCredentialsIntact("a refresh connection failure")
  }

  /**
   * The same defect through a server-side transient: `/auth/refresh` answers `503`.
   *
   * Expected: 5xx is retryable and must be distinguished from a 401 on the refresh endpoint, which
   * is the only response that actually means "this refresh token is no longer valid".
   *
   * Observed: `handleTokenRefresh` treats every non-2xx refresh response identically -
   * `if (!it.isSuccessful) handleRefreshFailure(callback)` (`ApiHandler.kt:224`) - so a restarting
   * or overloaded server logs the user out.
   */
  @Test
  fun `a 503 from the refresh endpoint must not log the user out`() {
    every { secureStorage.getRefreshToken(any()) } returns "refresh-token"
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setResponseCode(503))

    getCurrentUserOnce()

    assertCredentialsIntact("a 503 from /auth/refresh")
  }

  /**
   * A second-order bug inside the clearing path itself, found while writing the two specs above.
   * `handleRefreshFailure` nulled `DeviceManager.serverConnectionConfig` **first** and only then
   * read `DeviceManager.serverConnectionConfigId` to decide whether to remove the stored refresh
   * token. That property is defined as `serverConnectionConfig?.id ?: ""`
   * (`DeviceManager.kt:35`), so by that point it was always empty and
   * `secureStorage.removeRefreshToken` was **never** called - as was the listener notification
   * guarded by the same check.
   *
   * That made the behaviour the worst of both: the connection was discarded while the
   * now-orphaned refresh token was left behind in secure storage. A rejected refresh token is
   * exactly the thing that must not survive a logout - it is the credential the server has
   * already refused, and leaving it means the next login writes over an entry that a subsequent
   * failure would otherwise have tried to reuse.
   *
   * This was previously pinned as a characterization ("never reaches removeRefreshToken"), on the
   * grounds that fixing the ordering should be a deliberate change rather than an accidental side
   * effect. It is now that deliberate change: the id is captured *before* the config is cleared,
   * and this spec asserts the cleanup actually happens.
   */
  @Test
  fun `handleRefreshFailure removes the rejected refresh token it was always meant to clear`() {
    every { secureStorage.getRefreshToken(any()) } returns "refresh-token"
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setResponseCode(401))

    getCurrentUserOnce()

    io.mockk.verify(exactly = 1) { secureStorage.removeRefreshToken("test-server") }
  }

  /**
   * A 401 from `/auth/refresh` itself is the one case where discarding the session is the documented
   * policy: the refresh token really is rejected. Pinned so a fix for the transient cases above does
   * not accidentally make the app cling to genuinely dead credentials.
   */
  @Test
  fun `a 401 from the refresh endpoint does clear the session`() {
    every { secureStorage.getRefreshToken(any()) } returns "refresh-token"
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setResponseCode(401))

    getCurrentUserOnce()

    assertEquals(null, DeviceManager.serverConnectionConfig)
    assertEquals(null, DeviceManager.deviceData.lastServerConnectionConfigId)
  }
}
