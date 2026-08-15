package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaProgressExtraTest {
  @Test
  fun `mediaItemId prefers server library item id over local ids`() {
    val progress = localProgress(libraryItemId = "server-item", episodeId = "episode")

    assertEquals("server-item-episode", progress.mediaItemId)
  }

  @Test
  fun `mediaItemId falls back to local ids when server id is absent`() {
    val bookProgress =
            LocalMediaProgress(
                    id = "progress",
                    localLibraryItemId = "local-item",
                    localEpisodeId = null,
                    duration = 100.0,
                    progress = 0.0,
                    currentTime = 0.0,
                    isFinished = false,
                    ebookLocation = null,
                    ebookProgress = null,
                    lastUpdate = 0,
                    startedAt = 0,
                    finishedAt = null,
                    serverConnectionConfigId = null,
                    serverAddress = null,
                    serverUserId = null,
                    libraryItemId = null,
                    episodeId = null
            )
    assertEquals("local-item", bookProgress.mediaItemId)

    bookProgress.localEpisodeId = "local-ep"
    assertEquals("local-item-local-ep", bookProgress.mediaItemId)
  }

  @Test
  fun `updateEbookProgress overwrites location and progress and stamps lastUpdate`() {
    val progress = localProgress()
    progress.lastUpdate = 0

    progress.updateEbookProgress("epubcfi(/6/2)", 0.42)

    assertEquals("epubcfi(/6/2)", progress.ebookLocation)
    assertEquals(0.42, progress.ebookProgress ?: -1.0, 0.0)
    assertTrue(progress.lastUpdate > 0)
  }

  @Test
  fun `updateFromServerMediaProgress copies every server-tracked field`() {
    val progress = localProgress()
    val server =
            MediaProgress(
                    id = "server-progress",
                    libraryItemId = "library-item",
                    episodeId = null,
                    duration = 200.0,
                    progress = 0.75,
                    currentTime = 150.0,
                    isFinished = true,
                    ebookLocation = "cfi",
                    ebookProgress = 0.9,
                    lastUpdate = 999L,
                    startedAt = 111L,
                    finishedAt = 888L
            )

    progress.updateFromServerMediaProgress(server)

    assertTrue(progress.isFinished)
    assertEquals(0.75, progress.progress, 0.0)
    assertEquals(150.0, progress.currentTime, 0.0)
    assertEquals(0.9, progress.ebookProgress ?: -1.0, 0.0)
    assertEquals("cfi", progress.ebookLocation)
    assertEquals(200.0, progress.duration, 0.0)
    assertEquals(999L, progress.lastUpdate)
    assertEquals(888L, progress.finishedAt)
    assertEquals(111L, progress.startedAt)
  }

  @Test
  fun `updateFromServerMediaProgress clears finishedAt when server reports unfinished`() {
    val progress = localProgress()
    progress.isFinished = true
    progress.finishedAt = 123L

    val server =
            MediaProgress(
                    id = "server-progress",
                    libraryItemId = "library-item",
                    episodeId = null,
                    duration = 200.0,
                    progress = 0.1,
                    currentTime = 20.0,
                    isFinished = false,
                    ebookLocation = null,
                    ebookProgress = null,
                    lastUpdate = 5L,
                    startedAt = 1L,
                    finishedAt = null
            )

    progress.updateFromServerMediaProgress(server)

    assertFalse(progress.isFinished)
    assertNull(progress.finishedAt)
  }
}
