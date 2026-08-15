package com.audiobookshelf.app.support

import com.audiobookshelf.app.device.DeviceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AbsTestEnvironmentSmokeTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  @Test
  fun `reset provides a clean device manager state`() {
    assertNull(DeviceManager.serverConnectionConfig)
    assertEquals("", DeviceManager.serverAddress)
  }

  @Test
  fun `api handler constructs against the fake keystore provider`() {
    val handler = AbsTestEnvironment.apiHandler()
    assertEquals(handler.tag, "ApiHandler")
  }

  @Test
  fun `mock server wiring updates DeviceManager server address`() {
    AbsTestEnvironment.withMockServer { server ->
      assertEquals(server.url("/").toString().trimEnd('/'), DeviceManager.serverAddress)
    }
    assertNull(DeviceManager.serverConnectionConfig)
  }
}
