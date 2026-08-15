package com.audiobookshelf.app.device

import com.audiobookshelf.app.data.ServerConnectionConfig
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import com.audiobookshelf.app.support.AbsSingletonRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeviceManagerVersionTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private fun withServerVersion(version: String) {
    DeviceManager.serverConnectionConfig =
            ServerConnectionConfig("id", 0, "n", "https://x", version, "u", "un", "t", null)
  }

  @Test
  fun `equal versions compare as greater than or equal`() {
    withServerVersion("2.26.0")
    assertTrue(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.26.0"))
  }

  @Test
  fun `greater major minor or patch each satisfy the comparison`() {
    withServerVersion("3.0.0")
    assertTrue(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.26.0"))

    withServerVersion("2.27.0")
    assertTrue(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.26.5"))

    withServerVersion("2.26.6")
    assertTrue(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.26.5"))
  }

  @Test
  fun `lesser major minor or patch each fail the comparison`() {
    withServerVersion("1.9.9")
    assertFalse(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.0.0"))

    withServerVersion("2.25.9")
    assertFalse(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.26.0"))

    withServerVersion("2.26.4")
    assertFalse(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.26.5"))
  }

  @Test
  fun `differing part counts compare the missing parts as zero`() {
    withServerVersion("2.17")
    assertTrue(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.17.0"))
    assertFalse(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.17.1"))
  }

  @Test
  fun `empty server version is never greater than or equal to anything`() {
    withServerVersion("")
    assertFalse(DeviceManager.isServerVersionGreaterThanOrEqualTo("0.0.1"))
    assertFalse(DeviceManager.isServerVersionGreaterThanOrEqualTo(""))
  }

  @Test
  fun `empty compare version is always satisfied`() {
    withServerVersion("1.0.0")
    assertTrue(DeviceManager.isServerVersionGreaterThanOrEqualTo(""))
  }

  @Test
  fun `a prerelease suffix parses its numeric-looking segment as zero`() {
    // "2.26.0-beta".split(".") -> ["2", "26", "0-beta"]; "0-beta".toIntOrNull() is null, so the
    // patch component silently becomes 0 instead of failing to parse or comparing the suffix.
    // This means a server on "2.26.0-beta" reports itself as satisfying a "2.26.0" gate, which
    // may or may not be the intended contract - this test locks in the current behavior so a
    // change to it is deliberate rather than accidental.
    withServerVersion("2.26.0-beta")
    assertTrue(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.26.0"))
  }

  /**
   * The comparison must be numeric, not lexicographic. `"2.9.0" < "2.10.0"` as *strings*, so a
   * simplification to `serverVersion >= compareVersion` would invert this one case while leaving
   * every other spec in this class green - which is exactly why it is pinned separately.
   */
  @Test
  fun `a two-digit minor version compares numerically, not lexicographically`() {
    withServerVersion("2.10.0")
    assertTrue(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.9.0"))

    withServerVersion("2.9.0")
    assertFalse(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.10.0"))
  }

  @Test
  fun `a fourth version component participates in the comparison`() {
    withServerVersion("2.17.0.1")
    assertTrue(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.17.0"))

    withServerVersion("2.17.0")
    assertFalse(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.17.0.1"))
  }

  @Test
  fun `an empty component parses as zero rather than throwing`() {
    withServerVersion("2..0")

    assertTrue(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.0.0"))
    assertFalse(DeviceManager.isServerVersionGreaterThanOrEqualTo("2.1.0"))
  }

  @Test
  fun `getServerConnectionConfig finds by id and misses cleanly`() {
    val config = ServerConnectionConfig("one", 0, "One", "https://one", null, "u", "n", "t", null)
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(config)

    assertEquals(config, DeviceManager.getServerConnectionConfig("one"))
    assertNull(DeviceManager.getServerConnectionConfig("missing"))
    assertNull(DeviceManager.getServerConnectionConfig(null))
  }

  @Test
  fun `getBase64Id encodes the id bytes with the URL_SAFE and NO_WRAP flags`() {
    // android.util.Base64 has no working implementation under the mockable android.jar (see
    // ApiHandlerContractTest's Base64 delegation), so this locks in the *contract* - which bytes
    // and which flags get passed - rather than the actual encoded output.
    mockkStatic(android.util.Base64::class)
    val capturedBytes = slot<ByteArray>()
    val capturedFlags = slot<Int>()
    every { android.util.Base64.encodeToString(capture(capturedBytes), capture(capturedFlags)) } returns "encoded"
    try {
      val result = DeviceManager.getBase64Id("my-id")

      assertEquals("encoded", result)
      assertEquals("my-id", String(capturedBytes.captured))
      assertEquals(android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP, capturedFlags.captured)
    } finally {
      unmockkStatic(android.util.Base64::class)
    }
  }
}
