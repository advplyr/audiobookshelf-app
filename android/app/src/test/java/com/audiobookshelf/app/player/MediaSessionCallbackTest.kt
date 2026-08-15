package com.audiobookshelf.app.player

import android.content.Intent
import android.view.KeyEvent
import com.audiobookshelf.app.data.LibraryItemWithEpisode
import com.audiobookshelf.app.data.LibraryItemWrapper
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.media.MediaManager
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.audiobookshelf.app.support.AbsSingletonRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `MediaSessionCallback` is the media-button / Android Auto / lock-screen command surface; the
 * call-interruption path is `handleCallMediaButton` below. Every collaborator is a relaxed mock of
 * `PlayerNotificationService`, so these are interaction tests.
 *
 * Deliberately NOT exercised: `KEYCODE_MEDIA_PLAY_PAUSE` (key down), `KEYCODE_HEADSETHOOK`,
 * `KEYCODE_MEDIA_PLAY`/`KEYCODE_MEDIA_PAUSE` (key up) - these route through
 * `handleMediaButtonClickCount()`, which starts a real non-daemon `java.util.Timer` and posts to
 * an `android.os.Handler` whose no-arg constructor is a no-op stub on the host JVM. The queued
 * message never dispatches, and the Timer thread has no `cancel()` path, so exercising this would
 * leak a live thread per test run. Device-test territory.
 */
class MediaSessionCallbackTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var pns: PlayerNotificationService
  private lateinit var callback: MediaSessionCallback

  @Before
  fun setUp() {
    pns = mockk(relaxed = true)
    callback = MediaSessionCallback(pns)
  }

  @Test
  fun `onCustomAction jump forward delegates to jumpForward`() {
    callback.onCustomAction(CUSTOM_ACTION_JUMP_FORWARD, null)

    verify(exactly = 1) { pns.jumpForward() }
  }

  @Test
  fun `onCustomAction jump backward delegates to jumpBackward`() {
    callback.onCustomAction(CUSTOM_ACTION_JUMP_BACKWARD, null)

    verify(exactly = 1) { pns.jumpBackward() }
  }

  @Test
  fun `onCustomAction skip forward delegates to skipToNext`() {
    callback.onCustomAction(CUSTOM_ACTION_SKIP_FORWARD, null)

    verify(exactly = 1) { pns.skipToNext() }
  }

  @Test
  fun `onCustomAction skip backward delegates to skipToPrevious`() {
    callback.onCustomAction(CUSTOM_ACTION_SKIP_BACKWARD, null)

    verify(exactly = 1) { pns.skipToPrevious() }
  }

  @Test
  fun `onCustomAction unknown or null action is a no-op`() {
    callback.onCustomAction("com.unknown.action", null)
    callback.onCustomAction(null, null)

    verify(exactly = 0) { pns.jumpForward() }
    verify(exactly = 0) { pns.jumpBackward() }
    verify(exactly = 0) { pns.skipToNext() }
    verify(exactly = 0) { pns.skipToPrevious() }
  }

  @Test
  fun `onCustomAction change speed cycles through the preset table and resets above the range`() {
    val mediaManager = mockk<MediaManager>(relaxed = true)

    val table =
            mapOf(
                    0.5f to 1.0f, 0.6f to 1.0f, 0.7f to 1.0f,
                    0.8f to 1.2f, 1.0f to 1.2f,
                    1.1f to 1.5f, 1.2f to 1.5f,
                    1.3f to 2.0f, 1.5f to 2.0f,
                    1.6f to 3.0f, 2.0f to 3.0f,
                    2.1f to 0.5f, 3.0f to 0.5f,
                    3.5f to 1.0f // above the preset range resets to 1.0, not the next tier
            )

    table.forEach { (current, expectedNext) ->
      // Recorded calls must be cleared between iterations. Every expected value in the table
      // recurs (1.0f four times, 1.2f twice, ...), so with a shared call history an
      // at-least-once `verify` is satisfied from the value's *second* occurrence onward by an
      // earlier iteration's call rather than by this one - about half the table was
      // unverifiable. `answers = false` keeps the stubs above intact.
      clearMocks(mediaManager, pns, answers = false)
      every { mediaManager.getSavedPlaybackRate() } returns current
      every { pns.mediaManager } returns mediaManager

      callback.onCustomAction(CUSTOM_ACTION_CHANGE_SPEED, null)

      verify(exactly = 1) { mediaManager.setSavedPlaybackRate(expectedNext) }
      verify(exactly = 1) { pns.setPlaybackSpeed(expectedNext) }
    }
  }

  @Test
  fun `onSeekTo adds the current track start offset before seeking`() {
    every { pns.getCurrentTrackStartOffsetMs() } returns 5_000L

    callback.onSeekTo(2_000L)

    verify { pns.seekPlayer(7_000L) }
  }

  @Test
  fun `simple transport controls delegate directly to the player service`() {
    callback.onPlay()
    verify(exactly = 1) { pns.play() }

    callback.onPause()
    verify(exactly = 1) { pns.pause() }

    callback.onStop()
    verify(exactly = 2) { pns.pause() } // onStop also routes through pause()

    callback.onSkipToNext()
    verify(exactly = 1) { pns.skipToNext() }

    callback.onSkipToPrevious()
    verify(exactly = 1) { pns.skipToPrevious() }

    callback.onFastForward()
    verify(exactly = 1) { pns.jumpForward() }

    callback.onRewind()
    verify(exactly = 1) { pns.jumpBackward() }
  }

  @Suppress("DEPRECATION")
  private fun mediaButtonIntent(keyAction: Int, keyCode: Int): Intent {
    val intent = mockk<Intent>()
    every { intent.action } returns Intent.ACTION_MEDIA_BUTTON
    val keyEvent = mockk<KeyEvent>()
    every { keyEvent.action } returns keyAction
    every { keyEvent.keyCode } returns keyCode
    // Production branches on Build.VERSION.SDK_INT >= 33 for which overload it calls; the host
    // JVM's stubbed SDK_INT is unreliable to assume, so both overloads are stubbed.
    every { intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) } returns keyEvent
    every { intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) } returns keyEvent
    return intent
  }

  @Test
  fun `media button fast forward and rewind fire on key down`() {
    assertTrue(
            callback.onMediaButtonEvent(
                    mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            )
    )
    verify { pns.jumpForward() }

    assertTrue(
            callback.onMediaButtonEvent(mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND))
    )
    verify { pns.jumpBackward() }
  }

  @Test
  fun `media button next previous and stop fire on key up`() {
    assertTrue(
            callback.onMediaButtonEvent(mediaButtonIntent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
    )
    verify { pns.jumpForward() }

    assertTrue(
            callback.onMediaButtonEvent(mediaButtonIntent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    )
    verify { pns.jumpBackward() }

    assertTrue(
            callback.onMediaButtonEvent(mediaButtonIntent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))
    )
    verify { pns.closePlayback() }
  }

  @Test
  fun `unrecognized key-up code returns false`() {
    assertFalse(
            callback.onMediaButtonEvent(mediaButtonIntent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAMERA))
    )
  }

  @Test
  fun `non media-button intents are a no-op that still returns true`() {
    val intent = mockk<Intent>()
    every { intent.action } returns Intent.ACTION_VIEW

    assertTrue(callback.onMediaButtonEvent(intent))
    verify(exactly = 0) { pns.jumpForward() }
    verify(exactly = 0) { pns.jumpBackward() }
  }

  @Suppress("DEPRECATION")
  @Test
  fun `a media-button intent with no key event extra is tolerated`() {
    val intent = mockk<Intent>()
    every { intent.action } returns Intent.ACTION_MEDIA_BUTTON
    every { intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) } returns null
    every { intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) } returns null

    assertTrue(callback.onMediaButtonEvent(intent))
  }

  @Test
  fun `onPlayFromMediaId with a blank id falls back to getFirstItem`() {
    val mediaManager = mockk<MediaManager>(relaxed = true)
    every { pns.mediaManager } returns mediaManager
    val item = mockk<LibraryItemWrapper>(relaxed = true)
    every { mediaManager.getFirstItem() } returns item

    callback.onPlayFromMediaId(null, null)

    verify { mediaManager.play(item, null, any(), any()) }
  }

  @Test
  fun `onPlayFromMediaId resolves a podcast episode before falling back to a plain item`() {
    val mediaManager = mockk<MediaManager>(relaxed = true)
    every { pns.mediaManager } returns mediaManager
    val item = mockk<LibraryItemWrapper>(relaxed = true)
    val episode = mockk<PodcastEpisode>(relaxed = true)
    every { mediaManager.getPodcastWithEpisodeByEpisodeId("ep-1") } returns
            LibraryItemWithEpisode(item, episode)

    callback.onPlayFromMediaId("ep-1", null)

    verify { mediaManager.play(item, episode, any(), any()) }
    verify(exactly = 0) { mediaManager.getById(any()) }
  }

  @Test
  fun `onPlayFromMediaId falls back to getById when no podcast episode matches`() {
    val mediaManager = mockk<MediaManager>(relaxed = true)
    every { pns.mediaManager } returns mediaManager
    every { mediaManager.getPodcastWithEpisodeByEpisodeId("item-1") } returns null
    val item = mockk<LibraryItemWrapper>(relaxed = true)
    every { mediaManager.getById("item-1") } returns item

    callback.onPlayFromMediaId("item-1", null)

    verify { mediaManager.play(item, null, any(), any()) }
  }

  @Test
  fun `onPlayFromMediaId does nothing when the id cannot be resolved at all`() {
    val mediaManager = mockk<MediaManager>(relaxed = true)
    every { pns.mediaManager } returns mediaManager
    every { mediaManager.getPodcastWithEpisodeByEpisodeId(any()) } returns null
    every { mediaManager.getById(any()) } returns null

    callback.onPlayFromMediaId("missing", null)

    verify(exactly = 0) { mediaManager.play(any(), any(), any(), any()) }
  }
}
