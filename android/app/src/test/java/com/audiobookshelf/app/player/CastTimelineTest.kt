package com.audiobookshelf.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `android.util.SparseArray` has no working implementation under the AGP mockable android.jar
 * (get/put are stubbed no-ops without Robolectric), so [CastTimeline]'s constructor - which reads
 * item data out of a `SparseArray` - cannot be exercised as a host-JVM test. These cases are
 * limited to the pure, collection-free surface of the class.
 */
class CastTimelineTest {
  @Test
  fun `empty cast timeline has no windows or periods`() {
    val timeline = CastTimeline.EMPTY_CAST_TIMELINE

    assertEquals(0, timeline.windowCount)
    assertEquals(0, timeline.periodCount)
  }

  @Test
  fun `item data reuses instance when values are unchanged`() {
    val data = CastTimeline.ItemData(1_000L, 0L, false)

    val same = data.copyWithNewValues(1_000L, 0L, false)
    val different = data.copyWithNewValues(2_000L, 0L, false)

    assertTrue(same === data)
    assertTrue(different !== data)
    assertEquals(2_000L, different.durationUs)
  }
}
