package com.audiobookshelf.app.server

import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.BookMetadata
import com.audiobookshelf.app.data.Library
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.models.User
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Response bodies as the **server** emits them, checked against the models the client parses them
 * into.
 *
 * This closes the gap `TESTING.md` records as the suite's third-largest risk: every other
 * server-shaped fixture here is hand-written inside the test that uses it and sized to whatever
 * that test asserts, so it stays green after the server changes a serializer, a field type or a
 * status. A hand-written body is written to match the model - it cannot disagree with it.
 *
 * These bodies carry every field the server sends, including the ones the client does not model,
 * so a schema change surfaces here rather than as a parse failure on a device. Provenance, the
 * per-fixture mapping back to the server serializer that produces it, and how to refresh them for
 * a newer server are in `src/test/resources/fixtures/server-2.36.0/README.md`.
 *
 * Adding a server version means adding a directory and an entry in [serverVersions]; the old
 * fixtures stay, because they are what prove the client remained backward compatible.
 */
class GoldenResponseFixtureTest {
  private val mapper = jacksonObjectMapper()

  private fun fixture(version: String, name: String): String =
          checkNotNull(javaClass.getResourceAsStream("/fixtures/$version/$name")) {
                    "missing fixture /fixtures/$version/$name"
                  }
                  .bufferedReader()
                  .readText()

  // --- Library item: book ----------------------------------------------------------------------

  @Test
  fun `a server book library item deserializes with its metadata chapters and files intact`() {
    serverVersions.forEach { version ->
      val item = mapper.readValue<LibraryItem>(fixture(version, "library-item-book.json"))

      assertEquals(version, "li_8gch9ve09orv2p8lp0", item.id)
      assertEquals(version, "book", item.mediaType)
      assertEquals(version, "Wizards First Rule", item.title)

      val book = item.media as Book
      assertEquals(version, 2, book.chapters?.size)
      assertEquals(version, "/metadata/items/li_8gch9ve09orv2p8lp0/cover.jpg", book.coverPath)
      assertNotNull("$version: libraryFiles must survive the parse", item.libraryFiles)
    }
  }

  /**
   * `authors`, `series` and `narrators` are the fields the Android UI reads for every shelf label,
   * and all three are collections of objects the server builds by hand in `oldMetadataToJSON`.
   * They are the most likely thing to be reshaped and the least likely to be noticed, because a
   * null collection renders as an empty label rather than an error.
   */
  @Test
  fun `a server book's author series and narrator collections map onto the client model`() {
    serverVersions.forEach { version ->
      val item = mapper.readValue<LibraryItem>(fixture(version, "library-item-book.json"))
      val metadata = (item.media as Book).metadata as BookMetadata

      assertEquals(version, "Terry Goodkind", metadata.authors?.single()?.name)
      assertEquals(version, listOf("Sam Tsoutsouvas"), metadata.narrators)
      assertEquals(version, listOf("Fantasy"), metadata.genres)
      assertEquals(version, "Terry Goodkind", metadata.getAuthorDisplayName())
      assertEquals(version, "2008", metadata.publishedYear)
      assertEquals(version, "1", item.seriesSequence)
    }
  }

  // --- Library item: podcast -------------------------------------------------------------------

  @Test
  fun `a server podcast library item deserializes with its episodes intact`() {
    serverVersions.forEach { version ->
      val item = mapper.readValue<LibraryItem>(fixture(version, "library-item-podcast.json"))

      assertEquals(version, "podcast", item.mediaType)
      val podcast = item.media as Podcast
      assertEquals(version, "A Host", (podcast.metadata as PodcastMetadata).author)
      assertEquals(version, 1, podcast.episodes?.size)

      val episode = podcast.episodes!!.single()
      assertEquals(version, "ep_lh6ko39pumnrma3dhv", episode.id)
      assertEquals(version, 1_675_764_000_000L, episode.publishedAt)
      assertNotNull("$version: the episode's audioTrack must survive", episode.audioTrack)
      assertEquals(version, 1, podcast.getAudioTracks().size)
    }
  }

  // --- Progress --------------------------------------------------------------------------------

  /**
   * The server emits `userId`, `mediaItemId`, `mediaItemType` and `hideFromContinueListening`,
   * none of which the client models. That is exactly the situation `@JsonIgnoreProperties` exists
   * for, and exactly the situation that breaks a client which lacks it - so it is asserted against
   * a body that really carries all four rather than against a trimmed one.
   */
  @Test
  fun `a server media progress record deserializes despite fields the client does not model`() {
    serverVersions.forEach { version ->
      val progress = mapper.readValue<MediaProgress>(fixture(version, "media-progress.json"))

      assertEquals(version, "li_8gch9ve09orv2p8lp0", progress.libraryItemId)
      assertEquals(version, 14455.088, progress.currentTime, 0.0001)
      assertEquals(version, 33854.905, progress.duration, 0.0001)
      assertEquals(version, 1_668_586_015_492L, progress.lastUpdate)
      assertTrue(version, !progress.isFinished)
    }
  }

  // --- User ------------------------------------------------------------------------------------

  @Test
  fun `a server user deserializes with its embedded media progress`() {
    serverVersions.forEach { version ->
      val user = mapper.readValue<User>(fixture(version, "user.json"))

      assertEquals(version, "jane", user.username)
      assertEquals(version, 1, user.mediaProgress.size)
      assertEquals(version, "li_8gch9ve09orv2p8lp0", user.mediaProgress.single().libraryItemId)
    }
  }

  // --- Library ---------------------------------------------------------------------------------

  @Test
  fun `a server library deserializes with its folders and media type`() {
    serverVersions.forEach { version ->
      val library = mapper.readValue<Library>(fixture(version, "library.json"))

      assertEquals(version, "lib_c1u6t4p45c35rf0nzd", library.id)
      assertEquals(version, "Main", library.name)
      assertEquals(version, "book", library.mediaType)
      assertEquals(version, 1, library.folders.size)
    }
  }

  private companion object {
    /**
     * Every server version whose fixtures are committed. Keeping older entries here is the point:
     * the client is expected to parse all of them, so a change that only satisfies the newest
     * schema fails against the older ones.
     */
    val serverVersions = listOf("server-2.36.0")
  }
}
