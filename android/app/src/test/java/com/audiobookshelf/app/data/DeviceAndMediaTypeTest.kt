package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAndMediaTypeTest {
  /**
   * Note the third assertion: `application/octet-stream` counts as audio. That is deliberate in
   * production - servers and SAF providers routinely fail to type `.m4b`/`.opus` and fall back to
   * octet-stream, and rejecting it would make those files undownloadable - but it does mean the
   * check is a filename-extension decision in disguise, and that any binary blob passes it. The
   * downstream guard is `FolderScanner`, which is where a file that is not really audio has to be
   * caught. Recorded here so the looseness is visible rather than implied.
   */
  @Test
  fun `canonical audio mime types are detected`() {
    assertTrue(localFile("audio/mpeg").isAudioFile())
    assertTrue(localFile("video/mp4").isAudioFile())
    assertTrue(localFile("application/octet-stream").isAudioFile())
    assertFalse(localFile("application/pdf").isAudioFile())
    assertFalse(localFile(null).isAudioFile())
  }

  @Test
  fun `audio mime detection tolerates case whitespace and parameters`() {
    assertTrue(localFile(" Audio/MPEG; charset=binary ").isAudioFile())
  }

  @Test
  fun `canonical ebook mime types map to formats`() {
    assertEquals("pdf", localFile("application/pdf").getEBookFormat())
    assertEquals("epub", localFile("application/epub+zip").getEBookFormat())
    assertEquals("mobi", localFile("application/x-mobipocket-ebook").getEBookFormat())
    assertEquals("cbz", localFile("application/vnd.comicbook+zip").getEBookFormat())
    assertEquals("cbr", localFile("application/vnd.comicbook-rar").getEBookFormat())
    assertEquals("azw3", localFile("application/vnd.amazon.mobi8-ebook").getEBookFormat())
    assertNull(localFile("text/plain").getEBookFormat())
  }

  @Test
  fun `ebook format detection tolerates case and parameters`() {
    assertEquals("pdf", localFile("Application/PDF; charset=binary").getEBookFormat())
  }

  @Test
  fun `default device settings expose consistent derived values`() {
    val settings = DeviceSettings.default()

    assertEquals(10_000L, settings.jumpBackwardsTimeMs)
    assertEquals(10_000L, settings.jumpForwardTimeMs)
    assertEquals(22, settings.autoSleepTimerStartHour)
    assertEquals(0, settings.autoSleepTimerStartMinute)
    assertEquals(6, settings.autoSleepTimerEndHour)
    assertEquals(0, settings.autoSleepTimerEndMinute)
    assertEquals(1.5f, settings.getShakeThresholdGravity())
  }

  @Test
  fun `all shake sensitivities map monotonically`() {
    val settings = DeviceSettings.default()
    val thresholds =
            ShakeSensitivitySetting.entries.map {
              settings.shakeSensitivity = it
              settings.getShakeThresholdGravity()
            }

    assertEquals(listOf(2.7f, 2f, 1.5f, 1.3f, 1.1f), thresholds)
  }

  @Test
  fun `missing last server config returns null`() {
    val config = ServerConnectionConfig("one", 0, "One", "https://one", null, "u", "n", "t", null)

    assertNull(DeviceData(mutableListOf(config), "missing", null, null).getLastServerConnectionConfig())
    assertEquals(config, DeviceData(mutableListOf(config), "one", null, null).getLastServerConnectionConfig())
  }

  @Test
  fun `podcast accepts tracks when episodes were absent`() {
    val podcast =
            Podcast(
                    PodcastMetadata("Podcast", null, null, mutableListOf(), false),
                    null,
                    mutableListOf(),
                    null,
                    false,
                    null
            )

    podcast.setAudioTracks(mutableListOf(audioTrack(localFileId = "episode")))

    assertEquals(1, podcast.episodes?.size)
    assertEquals("local_ep_episode", podcast.episodes?.single()?.id)
    assertEquals(1, podcast.episodes?.single()?.index)
  }

  private fun localFile(mimeType: String?) =
          LocalFile("id", "file", "", "", "/file", mimeType, 1)
}
