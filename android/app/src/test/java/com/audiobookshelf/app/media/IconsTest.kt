package com.audiobookshelf.app.media

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import com.audiobookshelf.app.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class IconsTest {
  private lateinit var context: Context
  private lateinit var resources: Resources

  @Before
  fun setUp() {
    mockkStatic(Uri::class)
    every { Uri.parse(any()) } returns mockk(relaxed = true)

    resources = mockk(relaxed = true)
    context = mockk()
    every { context.resources } returns resources
    every { resources.getResourcePackageName(any()) } returns "com.audiobookshelf.app"
    every { resources.getResourceTypeName(any()) } returns "drawable"
    every { resources.getResourceEntryName(any()) } returns "entry"
  }

  @After
  fun tearDown() {
    unmockkStatic(Uri::class)
  }

  @Test
  fun `known abs icon names resolve to their dedicated drawable`() {
    val expectations =
            mapOf(
                    "audiobookshelf" to R.drawable.abs_audiobookshelf,
                    "authors" to R.drawable.abs_authors,
                    "book-1" to R.drawable.abs_book_1,
                    "podcast" to R.drawable.abs_podcast,
                    "rss" to R.drawable.abs_rss,
                    "star" to R.drawable.abs_star
            )

    expectations.forEach { (iconName, expectedId) ->
      getUriToAbsIconDrawable(context, iconName)
      verify { resources.getResourcePackageName(expectedId) }
    }
  }

  @Test
  fun `unrecognized icon name falls back to the folder icon`() {
    getUriToAbsIconDrawable(context, "not-a-real-icon")

    verify { resources.getResourcePackageName(R.drawable.icon_library_folder) }
  }

  @Test
  fun `uri is built from resource package type and entry names`() {
    getUriToDrawable(context, R.drawable.abs_headphones)

    verify {
      Uri.parse("android.resource://com.audiobookshelf.app/drawable/entry")
    }
  }
}
