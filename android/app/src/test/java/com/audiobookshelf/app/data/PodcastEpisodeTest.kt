package com.audiobookshelf.app.data

import android.content.Context
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.util.Date
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `PodcastEpisode.getMediaDescription` - the last uncovered corner of this class. Reuses the
 * `DateFormat.getDateInstance()` stub `MediaManagerTest` needed for the same reason: the mockable
 * `android.jar` returns `null` from that static factory, and `sdf.format(...)` would otherwise NPE
 * whenever `publishedAt` is non-null (a test-environment gap, not a production bug).
 *
 * NOT tested here: the `extras` `Bundle` this method populates (download-status, completion
 * status/percentage). Spike-verified this session: `android.os.Bundle` is entirely non-functional
 * under the mockable `android.jar` - `putInt`/`putLong` are no-ops and `getInt`/`getLong`/
 * `containsKey` all return their zero-value defaults regardless of what was put. A test asserting
 * `getInt(KEY) == expectedValue` would pass or fail based on whether `expectedValue` happens to be
 * the Kotlin/Java default (0/false), not on anything the method under test actually did - exactly
 * the false-green shape `TESTING.md` §8 warns about.
 */
class PodcastEpisodeTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private val ctx: Context = mockk(relaxed = true)

  @Before
  fun setUp() {
    AbsTestEnvironment.mockUriParse()
    mockkStatic(android.icu.text.DateFormat::class)
    val formatter = mockk<android.icu.text.DateFormat>()
    every { formatter.format(any<Date>()) } returns "Jan 1, 2024"
    every { android.icu.text.DateFormat.getDateInstance() } returns formatter
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  private fun episode(publishedAt: Long? = null, localEpisodeId: String? = null) =
          PodcastEpisode(
                  "ep-1", 1, null, null, "Episode Title", null, null, null, publishedAt,
                  null, audioTrack(localFileId = "lf-1"), null, 10.0, 100L, "server-ep-1", localEpisodeId
          )

  @Test
  fun `subtitle is just the library item title when publishedAt is absent`() {
    val item = libraryItem()

    val description = episode(publishedAt = null).getMediaDescription(item, null, ctx)

    assertEquals(item.title, description.subtitle)
  }

  @Test
  fun `subtitle is prefixed with the formatted publish date when publishedAt is present`() {
    val item = libraryItem()

    val description = episode(publishedAt = 1_700_000_000_000L).getMediaDescription(item, null, ctx)

    assertEquals("Jan 1, 2024 / ${item.title}", description.subtitle)
  }

  @Test
  fun `uses the server library item's token-based cover uri when given a server wrapper`() {
    val item = libraryItem()

    val description = episode().getMediaDescription(item, null, ctx)

    assertEquals(item.getCoverUri().toString(), description.iconUri.toString())
  }

  @Test
  fun `uses the local library item's cover when given a local wrapper`() {
    val local = localLibraryItem(coverContentUrl = "content://downloads/1")

    val description = episode().getMediaDescription(local, null, ctx)

    assertEquals("content://downloads/1", description.iconUri.toString())
  }
}
