package com.audiobookshelf.wear.data

import com.audiobookshelf.wear.fixtureJson
import com.audiobookshelf.wear.playback.PlayingBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelsTest {
  private val podcastJson get() = fixtureJson("podcast_item.json")
  private fun episodeJson(id: String) =
    (0 until podcastJson.getJSONObject("media").getJSONArray("episodes").length())
      .map { podcastJson.getJSONObject("media").getJSONArray("episodes").getJSONObject(it) }
      .first { it.getString("id") == id }

  @Test
  fun `parses a book from the server`() {
    val book = BookDetail.fromServerJson(fixtureJson("book_item.json"))

    assertEquals("li_book1", book.id)
    assertEquals("One Word Kill", book.title)
    assertEquals("Mark Lawrence", book.author)
    assertEquals(360.5, book.duration, 0.0)
    assertEquals(2, book.tracks.size)
    assertEquals(".mp3", book.tracks[1].ext)
    assertEquals(3600L, book.totalSize)
    assertNull(book.episodeId)
    assertEquals(MEDIA_BOOK, book.mediaType)
    assertEquals(MediaKey("li_book1"), book.mediaKey)
    assertEquals("Chapter 2", book.chapterAt(130.0)?.title)
  }

  @Test
  fun `book survives a save and load`() {
    val book = BookDetail.fromServerJson(fixtureJson("book_item.json"))
    assertEquals(book, BookDetail.fromJson(book.toJson()))
  }

  @Test
  fun `parses an episode with an audio track`() {
    val episode = BookDetail.fromEpisodeJson(podcastJson, episodeJson("ep_old"))

    assertEquals("li_pod1", episode.id)
    assertEquals("ep_old", episode.episodeId)
    assertEquals("Older episode", episode.title)
    // The podcast stands in for the author
    assertEquals("The Daily Show", episode.author)
    assertEquals(MEDIA_PODCAST, episode.mediaType)
    assertEquals(MediaKey("li_pod1", "ep_old"), episode.mediaKey)
    assertEquals(1, episode.tracks.size)
    assertEquals("/api/items/li_pod1/file/901", episode.tracks[0].contentUrl)
    assertEquals(1800.0, episode.duration, 0.0)
  }

  @Test
  fun `parses an episode without an audio track from its audio file`() {
    val episode = BookDetail.fromEpisodeJson(podcastJson, episodeJson("ep_new"))

    val track = episode.tracks.single()
    assertEquals("/api/items/li_pod1/file/902", track.contentUrl)
    assertEquals("audio/mp4", track.mimeType)
    assertEquals(".m4a", track.ext)
    assertEquals(45000L, track.size)
    assertEquals(2400.0, episode.duration, 0.0)
    assertEquals(listOf("Intro", "Main"), episode.chapters.map { it.title })
  }

  @Test
  fun `episode survives a save and load`() {
    val episode = BookDetail.fromEpisodeJson(podcastJson, episodeJson("ep_new"))
    assertEquals(episode, BookDetail.fromJson(episode.toJson()))
  }

  @Test
  fun `podcast lists episodes newest first`() {
    val podcast = PodcastDetail.fromServerJson(podcastJson)

    assertEquals("The Daily Show", podcast.title)
    assertEquals("Some Network", podcast.author)
    assertEquals(listOf("ep_new", "ep_old"), podcast.episodes.map { it.id })
    // ep_new has no duration of its own, only on its audio file
    assertEquals(2400.0, podcast.episodes[0].duration, 0.0)
  }

  @Test
  fun `media keys round trip`() {
    assertEquals("li_1", MediaKey("li_1").key)
    assertEquals("li_1/ep_2", MediaKey("li_1", "ep_2").key)
    assertEquals(MediaKey("li_1"), MediaKey.parse("li_1"))
    assertEquals(MediaKey("li_1", "ep_2"), MediaKey.parse("li_1/ep_2"))
  }

  @Test
  fun `summaries tell podcasts from episodes`() {
    val podcast = BookSummary("li_pod1", "Show", "", 0.0, mediaType = MEDIA_PODCAST)
    val episode = podcast.copy(episodeId = "ep_1")

    assertTrue(podcast.isPodcast)
    assertFalse(episode.isPodcast)
    assertEquals(MediaKey("li_pod1", "ep_1"), episode.mediaKey)
  }

  @Test
  fun `series number comes from the series name`() {
    assertEquals("3", BookSummary("1", "t", "a", 0.0, seriesName = "Defiance of the Fall #3").seriesSequence)
    assertNull(BookSummary("1", "t", "a", 0.0, seriesName = "No number").seriesSequence)
  }

  @Test
  fun `book positions map onto tracks`() {
    val playing = PlayingBook(BookDetail.fromServerJson(fixtureJson("book_item.json")), isLocal = false, sessionId = "s")

    assertEquals(0 to 50_000L, playing.trackPosition(50.0))
    assertEquals(1 to 50_000L, playing.trackPosition(250.0))
    // Out of range positions are clamped to the book
    assertEquals(0 to 0L, playing.trackPosition(-10.0))
    assertEquals(1 to 160_500L, playing.trackPosition(9999.0))
  }

  @Test
  fun `server progress knows its episode`() {
    val progress = ServerProgress.fromJson(fixtureJson("me.json").getJSONArray("mediaProgress").getJSONObject(1))
    assertEquals(MediaKey("li_pod1", "ep_new"), progress.mediaKey)
    assertTrue(progress.isFinished)
  }

  @Test
  fun `server supplied file names can't leave the book folder`() {
    val track = Track(1, "../../shared_prefs", 1.0, 0.0, "/x", "audio/mpeg", "/../auth.xml", 10)
    assertFalse(track.fileName.contains('/'))
    assertEquals("1_123.mp3", track.copy(ino = "123", ext = ".mp3").fileName)
    assertEquals("__", safeFileName(".."))
    assertEquals("_", safeFileName(""))
    assertEquals("li_book1", safeFileName("li_book1"))
  }
}
