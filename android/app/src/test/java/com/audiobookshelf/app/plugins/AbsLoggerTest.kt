package com.audiobookshelf.app.plugins

import com.audiobookshelf.app.managers.DbManager
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.audiobookshelf.app.support.AbsSingletonRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AbsLoggerTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var db: DbManager

  @Before
  fun setUp() {
    db = DbManager()
  }

  @Test
  fun `companion info persists a log entry`() {
    AbsLogger.info("MyTag", "hello world")

    val logs = db.getAllLogs()
    assertEquals(1, logs.size)
    assertEquals("info", logs.first().level)
    assertEquals("MyTag", logs.first().tag)
    assertEquals("hello world", logs.first().message)
  }

  @Test
  fun `companion error persists a log entry with the error level`() {
    AbsLogger.error("MyTag", "something broke")

    assertEquals("error", db.getAllLogs().first().level)
  }

  @Test
  fun `companion log notifies the registered emitter exactly once`() {
    val received = mutableListOf<AbsLog>()
    AbsLogger.onLogEmitter = { received.add(it) }

    AbsLogger.info("MyTag", "hello")

    assertEquals(1, received.size)
    assertEquals("hello", received.first().message)
  }

  @Test
  fun `info plugin method rejects when message is missing`() {
    val call = mockk<PluginCall>(relaxed = true)
    every { call.getString("message") } returns null

    AbsLogger().info(call)

    verify { call.reject("No message") }
    assertTrue(db.getAllLogs().isEmpty())
  }

  @Test
  fun `info plugin method resolves and persists when message is present`() {
    val call = mockk<PluginCall>(relaxed = true)
    every { call.getString("message") } returns "hello"
    every { call.getString("tag") } returns "PluginTag"

    AbsLogger().info(call)

    verify { call.resolve() }
    assertEquals(1, db.getAllLogs().size)
    assertEquals("PluginTag", db.getAllLogs().first().tag)
  }

  @Test
  fun `info plugin method defaults the tag to empty when absent`() {
    val call = mockk<PluginCall>(relaxed = true)
    every { call.getString("message") } returns "hello"
    every { call.getString("tag") } returns null

    AbsLogger().info(call)

    assertEquals("", db.getAllLogs().first().tag)
  }

  @Test
  fun `error plugin method rejects when message is missing`() {
    val call = mockk<PluginCall>(relaxed = true)
    every { call.getString("message") } returns null

    AbsLogger().error(call)

    verify { call.reject("No message") }
  }

  @Test
  fun `getAllLogs plugin method resolves with every persisted log`() {
    AbsLogger.info("A", "one")
    AbsLogger.info("B", "two")
    val call = mockk<PluginCall>(relaxed = true)
    val resolved = slot<JSObject>()
    every { call.resolve(capture(resolved)) } returns Unit

    AbsLogger().getAllLogs(call)

    val value = resolved.captured.getJSONArray("value")
    assertEquals(2, value.length())
  }

  @Test
  fun `clearLogs plugin method removes every persisted log and resolves`() {
    AbsLogger.info("A", "one")
    val call = mockk<PluginCall>(relaxed = true)

    AbsLogger().clearLogs(call)

    verify { call.resolve() }
    assertTrue(db.getAllLogs().isEmpty())
  }
}
