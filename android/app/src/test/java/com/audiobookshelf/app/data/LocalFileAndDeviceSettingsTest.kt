package com.audiobookshelf.app.data

import android.content.Context
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileAndDeviceSettingsTest {
  @Test
  fun `local file existence reflects the real filesystem for non-content uris`() {
    val ctx = mockk<Context>()
    val temp = File.createTempFile("abs-test", ".tmp")
    temp.deleteOnExit()

    val present = LocalFile("id", "file", "", "", temp.absolutePath, "audio/mpeg", 1)
    assertTrue(present.exists(ctx))

    temp.delete()
    assertFalse(present.exists(ctx))
  }

  @Test
  fun `missing local file with never-created path does not exist`() {
    val ctx = mockk<Context>()
    val missing = LocalFile("id", "file", "", "", "/does/not/exist/anywhere.mp3", "audio/mpeg", 1)

    assertFalse(missing.exists(ctx))
  }

  @Test
  fun `last server connection config selects the matching entry among several`() {
    val one = ServerConnectionConfig("one", 0, "One", "https://one", null, "u", "n", "t", null)
    val two = ServerConnectionConfig("two", 1, "Two", "https://two", null, "u", "n", "t", null)
    val data = DeviceData(mutableListOf(one, two), "two", null, null)

    assertEquals(two, data.getLastServerConnectionConfig())
  }

  @Test
  fun `malformed sleep timer start time does not crash the hour lookup`() {
    val settings = DeviceSettings.default()
    settings.autoSleepTimerStartTime = "invalid"

    assertEquals(22, settings.autoSleepTimerStartHour)
  }

  /**
   * Inputs:   `autoSleepTimerEndTime = "0600"` - a time string with no `:` separator.
   *
   * Expected: an hour inside `0..23`. Any value outside the clock is unusable to every caller.
   *
   * Observed: **600**. `"0600".split(":")` is `["0600"]` and `"0600".toIntOrNull()` is a perfectly
   *           valid integer, so the existing `?: 6` fallback never fires - there is nothing to fall
   *           back *from*. The guard that is missing is a range check, not a parse check.
   *
   * Path:     `DeviceClasses.kt:227` (`autoSleepTimerEndHour`) ->
   *           `SleepTimerManager.checkAutoSleepTimer`, which compares the current hour against this
   *           to decide whether it is inside the auto-timer window. 600 matches no hour, so the
   *           auto sleep timer silently stops working with nothing logged anywhere - worse than a
   *           crash, because there is no signal at all.
   *
   * Found by tightening a sibling spec that asserted only the *minute*, whose expected value `0`
   * happened to equal the fallback default and so could not distinguish parse from fallback.
   */
  @Test
  fun `a separator-less sleep timer time yields a real clock hour, not a parsed integer`() {
    val settings = DeviceSettings.default()
    settings.autoSleepTimerEndTime = "0600"

    assertTrue(
            "the end hour must be a valid clock hour, got ${settings.autoSleepTimerEndHour}",
            settings.autoSleepTimerEndHour in 0..23
    )
  }

  /**
   * Inputs:   `"99:88"` and `"-4:-9"` - values that parse cleanly but are not clock times.
   *
   * Expected: the documented defaults (22:00 start, 06:00 end), as for any unusable value.
   *
   * Observed: `99`, `88`, `-4` and `-9` are returned as-is.
   *
   * Path:     as above. Same missing range check, reached with a separator present.
   */
  @Test
  fun `an out-of-range hour or minute falls back to the default rather than being used`() {
    val settings = DeviceSettings.default()
    settings.autoSleepTimerStartTime = "99:88"
    settings.autoSleepTimerEndTime = "-4:-9"

    assertEquals(22, settings.autoSleepTimerStartHour)
    assertEquals(0, settings.autoSleepTimerStartMinute)
    assertEquals(6, settings.autoSleepTimerEndHour)
    assertEquals(0, settings.autoSleepTimerEndMinute)
  }

  @Test
  fun `a well-formed sleep timer time is parsed rather than defaulted`() {
    // The guard on the two specs above: clamping must not swallow legitimate values.
    val settings = DeviceSettings.default()
    settings.autoSleepTimerStartTime = "23:45"
    settings.autoSleepTimerEndTime = "07:15"

    assertEquals(23, settings.autoSleepTimerStartHour)
    assertEquals(45, settings.autoSleepTimerStartMinute)
    assertEquals(7, settings.autoSleepTimerEndHour)
    assertEquals(15, settings.autoSleepTimerEndMinute)
  }

  /**
   * The end *minute* default is also `0`, so asserting only that would not distinguish "parsed the
   * malformed value safely" from "fell back to the default". The hour is asserted alongside it:
   * a fallback yields the default hour `6`, while any implementation that tried to parse "0600"
   * positionally would not. Together the two pin the actual behaviour.
   */
  @Test
  fun `sleep timer time without a separator does not crash the minute lookup`() {
    val settings = DeviceSettings.default()
    settings.autoSleepTimerEndTime = "0600"

    assertEquals(0, settings.autoSleepTimerEndMinute)
    assertEquals(6, settings.autoSleepTimerEndHour)
  }
}
