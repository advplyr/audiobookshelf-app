package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscCoverageTest {
  @Test
  fun `collection book count reflects list size or zero when absent`() {
    val withBooks = LibraryCollection("collection", "library", "Name", null, mutableListOf(libraryItem(), libraryItem()))
    val withoutBooks = LibraryCollection("collection", "library", "Name", null, null)

    assertEquals(2, withBooks.bookCount)
    assertEquals(0, withoutBooks.bookCount)
  }

  @Test
  fun `local file ebook detection matches known formats only`() {
    assertTrue(localFile("application/epub+zip").isEBookFile())
    assertFalse(localFile("audio/mpeg").isEBookFile())
    assertFalse(localFile(null).isEBookFile())
  }

  @Test
  fun `audio track relative path falls back to empty string without metadata`() {
    val withoutMetadata = audioTrack()
    val withMetadata = withoutMetadata.copy(metadata = FileMetadata("f.mp3", "mp3", "/f.mp3", "sub/f.mp3", 10))

    assertEquals("", withoutMetadata.relPath)
    assertEquals("sub/f.mp3", withMetadata.relPath)
  }

  @Test
  fun `negative audio track duration produces an end offset before the start`() {
    val track = audioTrack(startOffset = 5.0, duration = -2.0)

    assertEquals(5_000L, track.startOffsetMs)
    assertEquals(-2_000L, track.durationMs)
    assertEquals(3_000L, track.endOffsetMs)
  }

  @Test
  fun `book chapter converts start and end seconds to milliseconds`() {
    val chapter = BookChapter(1, 1.2345, 9.8765, "Chapter 1")

    assertEquals(1_234L, chapter.startMs)
    assertEquals(9_876L, chapter.endMs)
  }

  @Test
  fun `podcast addEpisode appends and renumbers using the source episode id`() {
    val podcast = Podcast(PodcastMetadata("Cast", null, null, mutableListOf(), false), null, mutableListOf(), mutableListOf(), false, 0)
    val serverEpisode =
            PodcastEpisode(
                    "server-ep-1", 9, "1", "full", "Episode Title", "Sub", "Desc", null, 100L,
                    null, null, null, 42.0, 555L, null, null
            )
    val track = audioTrack(localFileId = "lf-1", duration = 42.0)

    val added = podcast.addEpisode(track, serverEpisode)

    assertEquals("local_ep_server-ep-1", added.id)
    assertEquals(1, added.index)
    assertEquals("Episode Title", added.title)
    assertEquals("server-ep-1", added.serverEpisodeId)
    assertEquals(track, added.audioTrack)
    assertEquals(1, podcast.episodes?.size)
  }

  @Test
  fun `podcast addEpisode renumbers every episode by publish order added`() {
    val podcast = Podcast(PodcastMetadata("Cast", null, null, mutableListOf(), false), null, mutableListOf(), mutableListOf(), false, 0)
    val first = PodcastEpisode("ep-1", 1, null, null, "First", null, null, null, null, null, null, null, 10.0, 1L, null, null)
    val second = PodcastEpisode("ep-2", 1, null, null, "Second", null, null, null, null, null, null, null, 10.0, 1L, null, null)

    podcast.addEpisode(audioTrack(localFileId = "lf-1"), first)
    podcast.addEpisode(audioTrack(localFileId = "lf-2"), second)

    assertEquals(listOf(1, 2), podcast.episodes?.map { it.index })
  }

  @Test
  fun `podcast getLocalCopy strips episodes but keeps metadata and settings`() {
    val episode = PodcastEpisode("ep-1", 1, null, null, "T", null, null, null, null, null, null, null, 10.0, 1L, null, null)
    val podcast =
            Podcast(
                    PodcastMetadata("Cast", "Author", "https://feed", mutableListOf("tag1"), true),
                    "/cover.jpg", mutableListOf("t1"), mutableListOf(episode), true, 5
            )

    val copy = podcast.getLocalCopy()

    assertTrue(copy.episodes?.isEmpty() == true)
    assertEquals(0, copy.numEpisodes)
    assertEquals("/cover.jpg", copy.coverPath)
    assertEquals(true, copy.autoDownloadEpisodes)
    assertEquals("Cast", copy.metadata.title)
  }

  @Test
  fun `book getLocalCopy strips tracks and duration but keeps chapters and metadata`() {
    val chapterList = listOf(BookChapter(1, 0.0, 10.0, "One"))
    val original = book(mutableListOf(audioTrack(duration = 10.0)), series = null)
    original.chapters = chapterList
    original.size = 12345L

    val copy = original.getLocalCopy()

    assertTrue(copy.tracks?.isEmpty() == true)
    assertEquals(0, copy.numTracks)
    assertEquals(null, copy.duration)
    assertEquals(null, copy.size)
    assertEquals(chapterList, copy.chapters)
    assertEquals("Book", copy.metadata.title)
  }

  private fun localFile(mimeType: String?) = LocalFile("id", "file", "", "", "/file", mimeType, 1)
}
