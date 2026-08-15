package com.audiobookshelf.app.support

import org.junit.rules.ExternalResource

/**
 * Calls [AbsTestEnvironment.reset] before **and** after every test.
 *
 * Prefer this over hand-writing the `@Before`/`@After` pair. The pair had been re-derived by hand
 * in roughly thirty classes and four of them got it wrong - resetting on the way in but not on the
 * way out - which is silent, because the damage lands in whichever *unrelated* class the shared
 * Gradle test JVM happens to run next. `MediaManagerTest` was the worst of the four: it pointed
 * `DeviceManager.serverConnectionConfig` at a `MockWebServer` and then shut that server down,
 * leaving the global singleton aimed at a dead socket.
 *
 * Usage:
 * ```
 * @get:Rule val absEnvironment = AbsSingletonRule()
 * ```
 *
 * A class that also needs `mockLocalFileStatics()`/`mockUriParse()` still declares its own
 * `@Before`, and its own `@After` calling `unmockkAll()`; this rule only owns the singleton state.
 * JUnit runs rule teardown after `@After` methods, so `unmockkAll()` still happens first.
 */
class AbsSingletonRule : ExternalResource() {
  override fun before() = AbsTestEnvironment.reset()

  override fun after() = AbsTestEnvironment.reset()
}
