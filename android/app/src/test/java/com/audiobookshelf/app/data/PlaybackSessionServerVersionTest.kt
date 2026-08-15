package com.audiobookshelf.app.data

import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PLAYMETHOD_DIRECTPLAY
import com.audiobookshelf.app.player.PLAYMETHOD_TRANSCODE
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `getContentUri`'s server-version branching and `checkIsServerVersionGte`'s config-mismatch
 * guard, uncovered since `PlaybackSessionTest`/`PlaybackSessionExtraTest` deliberately avoid
 * touching `DeviceManager`.
 */
class PlaybackSessionServerVersionTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  @Before
  fun setUp() {
    AbsTestEnvironment.mockUriParse()
  }

  @After
  fun tearDown() {
    io.mockk.unmockkAll()
  }

  private fun session(playMethod: Int) =
          playbackSession(mutableListOf(audioTrack(index = 0))).apply {
            this.playMethod = playMethod
            serverConnectionConfigId = "srv-1"
            serverAddress = "https://example.invalid"
          }

  private fun connect(version: String, configId: String = "srv-1") {
    DeviceManager.serverConnectionConfig =
            ServerConnectionConfig(configId, 0, "n", "https://example.invalid", version, "u", "un", "tok", null)
  }

  @Test
  fun `getContentUri for local playback ignores the server entirely`() {
    // isLocal depends only on playMethod == PLAYMETHOD_LOCAL (3); no local item wiring needed.
    val track = audioTrack(index = 0).apply { contentUrl = "file:///local/track.mp3" }
    val localSession = playbackSession(mutableListOf(track)).apply { playMethod = 3 }

    val uri = localSession.getContentUri(track)

    assertEquals("file:///local/track.mp3", uri.toString())
  }

  @Test
  fun `getContentUri direct-plays through the public session endpoint on servers 2_22_0 and newer`() {
    connect("2.22.0")
    val track = audioTrack(index = 3).apply { contentUrl = "/api/items/x/file/y" }
    val s = session(PLAYMETHOD_DIRECTPLAY)

    val uri = s.getContentUri(track)

    assertEquals("https://example.invalid/public/session/${s.id}/track/3", uri.toString())
  }

  @Test
  fun `getContentUri routes through HlsRouter when transcoding on servers 2_22_0 and newer`() {
    connect("2.22.0")
    val track = audioTrack(index = 0).apply { contentUrl = "/hls/abc/track.m3u8" }
    val s = session(PLAYMETHOD_TRANSCODE)

    val uri = s.getContentUri(track)

    assertEquals("https://example.invalid/hls/abc/track.m3u8", uri.toString())
  }

  @Test
  fun `getContentUri falls back to a tokenized url on servers older than 2_22_0`() {
    connect("2.21.0")
    val track = audioTrack(index = 0).apply { contentUrl = "/api/items/x/file/y" }
    val s = session(PLAYMETHOD_DIRECTPLAY)

    val uri = s.getContentUri(track)

    assertEquals("https://example.invalid/api/items/x/file/y?token=tok", uri.toString())
  }

  @Test
  fun `checkIsServerVersionGte returns false when the session belongs to a different server connection than the active one`() {
    connect("2.22.0", configId = "srv-other")
    val s = session(PLAYMETHOD_DIRECTPLAY) // serverConnectionConfigId = "srv-1", but active is "srv-other"

    assertFalse(s.checkIsServerVersionGte("2.22.0"))
  }

  @Test
  fun `checkIsServerVersionGte returns false when not connected to any server`() {
    val s = session(PLAYMETHOD_DIRECTPLAY)

    assertFalse(s.checkIsServerVersionGte("2.22.0"))
  }
}
