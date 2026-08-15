package com.audiobookshelf.app.data

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jackson deserialization of the models that come off the wire.
 *
 * Everywhere else in this suite, models are built with the Kotlin constructor - which never
 * exercises `@JsonIgnoreProperties`, a missing field, or the int-vs-double the server actually
 * emits. `UserTest` was the only class round-tripping a model through Jackson at all, so the
 * question "does the app survive a server that added a field, or that serializes a whole number
 * without a decimal point?" had exactly one answer for exactly one type.
 *
 * These are contract tests for the *client's tolerance*, deliberately written against hand-built
 * minimal JSON. They are the complement to the golden fixtures in
 * `server/GoldenResponseFixtureTest`, which check the opposite direction: that real server bodies
 * still fit these models.
 */
class ModelDeserializationTest {
  private val mapper = jacksonObjectMapper()

  // --- Unknown fields: the server adds them, and the app must not care ------------------------

  @Test
  fun `LibraryItem ignores server fields it does not model`() {
    val json =
            """
      {"id":"li-1","ino":"1","libraryId":"lib","folderId":"f","path":"/p","relPath":"p",
       "mtimeMs":0,"ctimeMs":0,"birthtimeMs":0,"addedAt":0,"updatedAt":0,"isMissing":false,
       "isInvalid":false,"mediaType":"book",
       "media":{"metadata":{"title":"T","subtitle":null,"genres":[],"explicit":false},
                "coverPath":null,"tags":[],"tracks":[]},
       "someFieldAddedInAFutureRelease":{"nested":true},"anotherOne":[1,2,3]}
    """

    val item = mapper.readValue<LibraryItem>(json)

    assertEquals("li-1", item.id)
    assertEquals("T", item.title)
  }

  @Test
  fun `MediaProgress ignores unknown fields`() {
    val json =
            """
      {"id":"p1","libraryItemId":"li-1","episodeId":null,"duration":100.0,"progress":0.5,
       "currentTime":50.0,"isFinished":false,"ebookLocation":null,"ebookProgress":null,
       "lastUpdate":1,"startedAt":0,"finishedAt":null,"hideFromContinueListening":true}
    """

    val progress = mapper.readValue<MediaProgress>(json)

    assertEquals(0.5, progress.progress, 0.0)
    assertEquals(50.0, progress.currentTime, 0.0)
  }

  @Test
  fun `PlaybackSession ignores unknown fields`() {
    val session = mapper.readValue<PlaybackSession>(playbackSessionJson(extra = ""","videoTrack":null"""))

    assertEquals("s1", session.id)
    assertEquals(100.0, session.duration, 0.0)
  }

  // --- Numeric coercion: the server emits whole numbers without a decimal point ---------------

  /**
   * The server serializes a whole-number duration as `100`, not `100.0`. Every one of these fields
   * is a Kotlin `Double`, so if Jackson did not coerce, a book of exactly 100 seconds would fail to
   * parse while one of 100.5 seconds succeeded - the kind of defect that only shows up on
   * particular content.
   */
  @Test
  fun `whole-number durations and times deserialize into Double fields`() {
    val json =
            """
      {"id":"p1","libraryItemId":"li-1","episodeId":null,"duration":100,"progress":1,
       "currentTime":50,"isFinished":true,"ebookLocation":null,"ebookProgress":null,
       "lastUpdate":1,"startedAt":0,"finishedAt":null}
    """

    val progress = mapper.readValue<MediaProgress>(json)

    assertEquals(100.0, progress.duration, 0.0)
    assertEquals(1.0, progress.progress, 0.0)
    assertEquals(50.0, progress.currentTime, 0.0)
  }

  @Test
  fun `an AudioTrack with integer offsets and duration deserializes`() {
    val json =
            """
      {"index":0,"startOffset":0,"duration":30,"title":"T","contentUrl":"/t","mimeType":"audio/mpeg",
       "metadata":null,"isLocal":false,"localFileId":null,"serverIndex":0}
    """

    val track = mapper.readValue<AudioTrack>(json)

    assertEquals(0.0, track.startOffset, 0.0)
    assertEquals(30.0, track.duration, 0.0)
    assertEquals(30_000L, track.endOffsetMs)
  }

  // --- Absent optional fields ------------------------------------------------------------------

  @Test
  fun `a Book with no chapters tracks or ebookFile deserializes with nulls rather than failing`() {
    val json =
            """
      {"metadata":{"title":"T","subtitle":null,"genres":[],"explicit":false},"coverPath":null,
       "tags":[],"tracks":null}
    """

    val book = mapper.readValue<Book>(json)

    assertEquals("T", book.metadata.title)
    assertNull(book.tracks)
    assertNull(book.ebookFile)
    assertTrue("a book with no tracks reports none", !book.checkHasTracks())
  }

  @Test
  fun `a Podcast with no episodes deserializes and reports no tracks`() {
    val json =
            """
      {"metadata":{"title":"Cast","author":null,"feedUrl":null,"genres":[],"explicit":false},
       "coverPath":null,"tags":[],"episodes":null,"autoDownloadEpisodes":false}
    """

    val podcast = mapper.readValue<Podcast>(json)

    assertEquals("Cast", podcast.metadata.title)
    assertNull(podcast.episodes)
    assertTrue(podcast.getAudioTracks().isEmpty())
  }

  /**
   * Two constraints are in play and both are easy to trip over. `genres` is a non-null
   * `MutableList<String>`, so it must be present even though the collections around it are
   * optional; and naming `BookMetadata` as the target does **not** bypass subtype deduction, since
   * the `@JsonTypeInfo` sits on the `MediaTypeMetadata` supertype and is inherited - so the payload
   * still needs a book-only field name (`subtitle` here) to resolve.
   */
  @Test
  fun `BookMetadata tolerates absent author narrator and series collections`() {
    val metadata =
            mapper.readValue<BookMetadata>("""{"title":"T","subtitle":null,"genres":[],"explicit":false}""")

    assertEquals("T", metadata.title)
    assertNotNull("an author display name must always resolve", metadata.getAuthorDisplayName())
  }

  // --- Missing required fields: predictable failure, not a silent default ----------------------

  /**
   * The counterpart to the tolerance specs above. A *required* field going missing must fail
   * loudly at the parse rather than defaulting, because a silently-defaulted id would be written
   * into local storage under the wrong key.
   *
   * This is also the mechanism behind `ApiHandlerEdgeCaseTest`'s spec that a body which fails to
   * deserialize used to drop the callback entirely: the throw is correct, and handling it is the
   * caller's job.
   */
  @Test(expected = MismatchedInputException::class)
  fun `a LibraryItem missing its id fails to deserialize`() {
    val json =
            """
      {"ino":"1","libraryId":"lib","folderId":"f","path":"/p","relPath":"p","mtimeMs":0,
       "ctimeMs":0,"birthtimeMs":0,"addedAt":0,"updatedAt":0,"isMissing":false,"isInvalid":false,
       "mediaType":"book",
       "media":{"metadata":{"title":"T","genres":[],"explicit":false},"coverPath":null,"tags":[],
                "tracks":[]}}
    """

    mapper.readValue<LibraryItem>(json)
  }

  /**
   * Characterization, and a sharper edge than it looks. `currentTime` is a non-null `Double`, but
   * it is a plain constructor parameter forwarded to `MediaProgressWrapper` rather than a property
   * on `MediaProgress` itself - so Jackson's Kotlin module does not enforce it the way it enforces
   * `id`, and an absent value silently becomes `0.0` instead of failing.
   *
   * The distinction matters: a *rejected* response is retried or reported, while a response that
   * parses to position zero is written to local storage as "the user is at the beginning". No
   * server version is known to omit the field, which is why this is pinned rather than enabled as
   * a failure - but it is the difference between the two `MediaProgress` fields below, and it is
   * invisible from the class declaration.
   */
  @Test
  fun `a MediaProgress missing its currentTime silently defaults to zero rather than failing`() {
    val json =
            """
      {"id":"p1","libraryItemId":"li-1","episodeId":null,"duration":100.0,"progress":0.5,
       "isFinished":false,"ebookLocation":null,"ebookProgress":null,"lastUpdate":1,
       "startedAt":0,"finishedAt":null}
    """

    val progress = mapper.readValue<MediaProgress>(json)

    assertEquals(
            "an absent currentTime is not rejected; it reads as the start of the book",
            0.0,
            progress.currentTime,
            0.0
    )
  }

  /**
   * `MediaType` and `MediaTypeMetadata` are resolved with `JsonTypeInfo.Id.DEDUCTION`: Jackson
   * picks `Book`/`Podcast` (and `BookMetadata`/`PodcastMetadata`) purely from which *field names*
   * are present. A payload carrying only the fields the two subtypes share is therefore ambiguous
   * and fails to resolve at all.
   *
   * Worth pinning because it is a real coupling to the server's response shape that is invisible in
   * the model declarations: if a future server release trimmed a minified metadata object down to
   * the common fields, this is the failure the client would produce, and it looks like a parse bug
   * rather than a schema change.
   */
  @Test
  fun `metadata carrying only fields common to both subtypes cannot be deduced`() {
    var thrown: Throwable? = null
    try {
      mapper.readValue<MediaTypeMetadata>("""{"title":"T","genres":[],"explicit":false}""")
    } catch (e: Throwable) {
      thrown = e
    }

    assertNotNull("deduction needs at least one field unique to a subtype", thrown)
    assertTrue(
            "expected a Jackson type-resolution failure, got $thrown",
            thrown is com.fasterxml.jackson.databind.exc.InvalidTypeIdException
    )
  }

  /** The positive half: one book-only field name is enough to resolve the subtype. */
  @Test
  fun `a single book-only field name is enough to deduce BookMetadata`() {
    val metadata =
            mapper.readValue<MediaTypeMetadata>("""{"title":"T","subtitle":null,"genres":[],"explicit":false}""")

    assertTrue("expected BookMetadata, got ${metadata.javaClass.simpleName}", metadata is BookMetadata)
  }

  // --- Round trip ------------------------------------------------------------------------------

  /**
   * What the app writes must be what the app can read back. `PlaybackSession` is the model this
   * matters most for: `MediaProgressSyncer` persists it to Paper on every offline sync attempt and
   * `AbsDatabase.syncLocalSessionsWithServer` reads it back on reconnect, so a serialization
   * asymmetry here loses a listening position rather than just failing a request.
   */
  @Test
  fun `a PlaybackSession survives a serialize-deserialize round trip`() {
    val original = mapper.readValue<PlaybackSession>(playbackSessionJson())

    val roundTripped = mapper.readValue<PlaybackSession>(mapper.writeValueAsString(original))

    assertEquals(original.id, roundTripped.id)
    assertEquals(original.currentTime, roundTripped.currentTime, 0.0)
    assertEquals(original.duration, roundTripped.duration, 0.0)
    assertEquals(original.libraryItemId, roundTripped.libraryItemId)
    assertEquals(original.timeListening, roundTripped.timeListening)
  }

  private fun playbackSessionJson(extra: String = "") =
          """
      {"id":"s1","userId":"u1","libraryItemId":"li-1","episodeId":null,"mediaType":"book",
       "mediaMetadata":{"title":"T","subtitle":null,"genres":[],"explicit":false},
       "deviceInfo":{"deviceId":"d","manufacturer":"m","model":"mo","sdkVersion":35,"clientVersion":"1"},
       "chapters":[],"displayTitle":"T","displayAuthor":"A","coverPath":null,"duration":100.0,
       "playMethod":0,"startedAt":0,"updatedAt":0,"timeListening":12,"audioTracks":[],
       "currentTime":25.0,"libraryItem":null,"localLibraryItem":null,"localEpisodeId":null,
       "serverConnectionConfigId":null,"serverAddress":null,"mediaPlayer":"exo-player"$extra}
    """
}
