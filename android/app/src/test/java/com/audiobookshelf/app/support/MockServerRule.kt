package com.audiobookshelf.app.support

import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import okhttp3.mockwebserver.MockWebServer
import org.junit.rules.ExternalResource

/**
 * A [MockWebServer] for the whole test class, with `DeviceManager.serverConnectionConfig` pointed
 * at it and torn down afterwards.
 *
 * [AbsTestEnvironment.withMockServer] covers the *scoped* case - a server for one block inside one
 * test. Seven suites needed one for the entire class instead and each hand-rolled it in
 * `@Before`/`@After`, which is how `MediaManagerTest` came to shut its server down without clearing
 * the global config that pointed at it.
 *
 * Ordering matters: declare this **after** [AbsSingletonRule] in the class, or use a
 * `RuleChain`. JUnit applies rule fields outermost-first in declaration order, so the singleton
 * reset must wrap this one - otherwise `reset()` would null the config this rule just set.
 * `@get:Rule` fields on one class have no guaranteed order, so classes using both should use the
 * [chainedWith] helper.
 */
class MockServerRule(
        private val serverVersion: String = "2.17.0",
        private val configId: String = "test-server",
        private val token: String = "test-token",
        private val userId: String = "user-1",
        private val customHeaders: Map<String, String>? = null
) : ExternalResource() {
  lateinit var server: MockWebServer
    private set

  /** The config this rule installed, for tests that need to hand it to an API explicitly. */
  lateinit var config: ServerConnectionConfig
    private set

  val url: String
    get() = server.url("/").toString().trimEnd('/')

  override fun before() {
    server = MockWebServer()
    server.start()
    config =
            ServerConnectionConfig(
                    configId, 0, "Test", url, serverVersion, userId, "username", token, customHeaders
            )
    DeviceManager.serverConnectionConfig = config
  }

  override fun after() {
    server.shutdown()
    // Cleared here as well as by AbsSingletonRule: this rule owns the pointer it installed, and a
    // config aimed at a shut-down socket is the one piece of leaked state that fails loudly and
    // confusingly in an unrelated class.
    DeviceManager.serverConnectionConfig = null
  }

  companion object {
    /**
     * Correct ordering for a class that needs both rules: the singleton reset on the outside, the
     * server on the inside.
     *
     * ```
     * @get:Rule val rules = MockServerRule.chainedWith(mockServer)
     * ```
     */
    fun chainedWith(serverRule: MockServerRule): org.junit.rules.RuleChain =
            org.junit.rules.RuleChain.outerRule(AbsSingletonRule()).around(serverRule)
  }
}
