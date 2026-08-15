package com.audiobookshelf.app.player

import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.PlayerState
import com.audiobookshelf.app.managers.SleepTimerManager
import com.audiobookshelf.app.media.MediaProgressSyncer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.audiobookshelf.app.support.AbsSingletonRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `lastPauseTime`/`lazyIsPlaying` are companion-object statics shared across every
 * `PlayerListener` instance and every test class in the same Gradle test JVM - reset them in both
 * `@Before` and `@After` so this suite cannot leak state into (or absorb it from) another class.
 */
class PlayerListenerTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var pns: PlayerNotificationService
  private lateinit var player: Player
  private lateinit var listener: PlayerListener

  @Before
  fun setUp() {
    PlayerListener.lastPauseTime = 0
    PlayerListener.lazyIsPlaying = false
    pns = mockk(relaxed = true)
    player = mockk(relaxed = true)
    every { pns.currentPlayer } returns player
    listener = PlayerListener(pns)
  }

  @After
  fun tearDown() {
    PlayerListener.lastPauseTime = 0
    PlayerListener.lazyIsPlaying = false
  }

  @Test
  fun `onPlayerError delegates the error message to handlePlayerPlaybackError`() {
    val error = mockk<PlaybackException>(relaxed = true)
    every { error.message } returns "boom"

    listener.onPlayerError(error)

    verify { pns.handlePlayerPlaybackError("boom") }
  }

  @Test
  fun `onPlayerError falls back to a default message when the exception has none`() {
    val error = mockk<PlaybackException>(relaxed = true)
    every { error.message } returns null

    listener.onPlayerError(error)

    verify { pns.handlePlayerPlaybackError("Unknown Error") }
  }

  @Test
  fun `onPositionDiscontinuity with a seek reason triggers a syncer seek and resets pause time`() {
    PlayerListener.lastPauseTime = 12_345L
    val syncer = mockk<MediaProgressSyncer>(relaxed = true)
    every { pns.mediaProgressSyncer } returns syncer

    listener.onPositionDiscontinuity(
            mockk(relaxed = true), mockk(relaxed = true), Player.DISCONTINUITY_REASON_SEEK
    )

    verify { syncer.seek() }
    assertEquals(0L, PlayerListener.lastPauseTime)
  }

  @Test
  fun `onPositionDiscontinuity with a non-seek reason does not trigger a sync`() {
    val syncer = mockk<MediaProgressSyncer>(relaxed = true)
    every { pns.mediaProgressSyncer } returns syncer

    listener.onPositionDiscontinuity(
            mockk(relaxed = true), mockk(relaxed = true), Player.DISCONTINUITY_REASON_INTERNAL
    )

    verify(exactly = 0) { syncer.seek() }
  }

  @Test
  fun `onIsPlayingChanged ignores a pause event that occurs while buffering`() {
    every { player.playbackState } returns Player.STATE_BUFFERING
    val syncer = mockk<MediaProgressSyncer>(relaxed = true)
    every { pns.mediaProgressSyncer } returns syncer

    listener.onIsPlayingChanged(false)

    // Asserted against the syncer's own methods rather than against `pns.mediaProgressSyncer`
    // being read. Verifying the property getter would break on any refactor that reads it for
    // logging, without the behaviour under test having changed at all.
    verify(exactly = 0) { syncer.pause(any()) }
    verify(exactly = 0) { syncer.play(any()) }
    assertEquals(false, PlayerListener.lazyIsPlaying)
  }

  @Test
  fun `onIsPlayingChanged ignores a redundant event matching the current lazy state`() {
    PlayerListener.lazyIsPlaying = true
    every { player.playbackState } returns Player.STATE_READY
    val syncer = mockk<MediaProgressSyncer>(relaxed = true)
    every { pns.mediaProgressSyncer } returns syncer

    listener.onIsPlayingChanged(true)

    verify(exactly = 0) { syncer.play(any()) }
    verify(exactly = 0) { syncer.pause(any()) }
  }

  @Test
  fun `onIsPlayingChanged true starts progress sync and notifies the emitter`() {
    every { player.playbackState } returns Player.STATE_READY
    val syncer = mockk<MediaProgressSyncer>(relaxed = true)
    every { pns.mediaProgressSyncer } returns syncer
    val session = mockk<PlaybackSession>(relaxed = true)
    every { syncer.currentPlaybackSession } returns session
    every { pns.sleepTimerManager } returns mockk<SleepTimerManager>(relaxed = true)

    listener.onIsPlayingChanged(true)

    verify { syncer.play(session) }
    verify { pns.clientEventEmitter?.onPlayingUpdate(true) }
    assertEquals(true, PlayerListener.lazyIsPlaying)
  }

  @Test
  fun `onIsPlayingChanged false records the pause time and pauses progress sync`() {
    // lazyIsPlaying defaults to false, and the method ignores an event that matches the current
    // lazy state - so this must start from a "was playing" state for isPlaying=false to be a real
    // transition rather than the ignored-redundant-event branch.
    PlayerListener.lazyIsPlaying = true
    every { player.playbackState } returns Player.STATE_READY
    val syncer = mockk<MediaProgressSyncer>(relaxed = true)
    every { pns.mediaProgressSyncer } returns syncer

    listener.onIsPlayingChanged(false)

    verify { syncer.pause(any()) }
    assertTrue(PlayerListener.lastPauseTime > 0)
    assertEquals(false, PlayerListener.lazyIsPlaying)
  }



  private fun events(vararg present: Int): Player.Events {
    val events = mockk<Player.Events>()
    every { events.contains(any()) } answers { present.contains(firstArg<Int>()) }
    every { events.size() } returns present.size
    return events
  }

  @Test
  fun `onEvents dispatches STATE_READY to sendClientMetadata`() {
    every { player.playbackState } returns Player.STATE_READY

    listener.onEvents(player, events(Player.EVENT_PLAYBACK_STATE_CHANGED))

    verify { pns.sendClientMetadata(PlayerState.READY) }
  }

  @Test
  fun `onEvents dispatches STATE_BUFFERING to sendClientMetadata`() {
    every { player.playbackState } returns Player.STATE_BUFFERING

    listener.onEvents(player, events(Player.EVENT_PLAYBACK_STATE_CHANGED))

    verify { pns.sendClientMetadata(PlayerState.BUFFERING) }
  }

  @Test
  fun `onEvents dispatches STATE_ENDED to sendClientMetadata and handlePlaybackEnded`() {
    every { player.playbackState } returns Player.STATE_ENDED

    listener.onEvents(player, events(Player.EVENT_PLAYBACK_STATE_CHANGED))

    verify { pns.sendClientMetadata(PlayerState.ENDED) }
    verify { pns.handlePlaybackEnded() }
  }

  @Test
  fun `onEvents dispatches STATE_IDLE to sendClientMetadata`() {
    every { player.playbackState } returns Player.STATE_IDLE

    listener.onEvents(player, events(Player.EVENT_PLAYBACK_STATE_CHANGED))

    verify { pns.sendClientMetadata(PlayerState.IDLE) }
  }

  @Test
  fun `onEvents without a playback state change reports no state`() {
    listener.onEvents(player, events())

    verify(exactly = 0) { pns.sendClientMetadata(any()) }
    verify(exactly = 0) { pns.handlePlaybackEnded() }
  }
}
