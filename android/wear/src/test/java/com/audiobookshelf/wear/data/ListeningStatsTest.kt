package com.audiobookshelf.wear.data

import com.audiobookshelf.wear.fixtureJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ListeningStatsTest {
  private val saturday = LocalDate.of(2026, 10, 10)

  private fun stats(days: Map<String, Int>, today: LocalDate) =
    ListeningStats.from(JSONObject().put("days", JSONObject(days)).put("totalTime", 0), today)

  @Test
  fun `sums up the server response`() {
    val stats = ListeningStats.from(fixtureJson("listening_stats.json"), saturday)

    assertEquals(3600L, stats.todaySeconds)
    // Oct 4..10; Oct 1 is outside the week
    assertEquals(listOf(0L, 0L, 1200L, 0L, 600L, 1800L, 3600L), stats.last7Days)
    assertEquals(7200L, stats.weekSeconds)
    assertEquals(3, stats.streakDays)
    assertEquals(66000L, stats.totalSeconds)
  }

  @Test
  fun `the week runs across a month boundary`() {
    val stats = stats(mapOf("2026-09-30" to 60, "2026-10-01" to 120, "2026-09-24" to 999), LocalDate.of(2026, 10, 1))

    assertEquals(180L, stats.weekSeconds)
    assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 60L, 120L), stats.last7Days)
  }

  @Test
  fun `a streak stays alive until today is over`() {
    val stats = stats(mapOf("2026-10-09" to 60, "2026-10-08" to 60), saturday)

    assertEquals(0L, stats.todaySeconds)
    assertEquals(2, stats.streakDays)
  }

  @Test
  fun `a missed day ends the streak`() {
    assertEquals(0, stats(mapOf("2026-10-08" to 60, "2026-10-07" to 60), saturday).streakDays)
  }

  @Test
  fun `no listening at all`() {
    val stats = ListeningStats.from(JSONObject(), saturday)

    assertEquals(0L, stats.weekSeconds)
    assertEquals(0, stats.streakDays)
    assertEquals(7, stats.last7Days.size)
  }
}
