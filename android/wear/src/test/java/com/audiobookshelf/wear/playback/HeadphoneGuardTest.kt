package com.audiobookshelf.wear.playback

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.wear.testContext
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowLooper

@UnstableApi
private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
  var playing = false

  override fun getState(): State = State.Builder()
    .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
    .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
    .build()

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    playing = playWhenReady
    return Futures.immediateVoidFuture()
  }
}

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class HeadphoneGuardTest {
  private val audioManager = testContext.getSystemService(AudioManager::class.java)
  private val player = FakePlayer()
  private var preferred: AudioDeviceInfo? = null
  private var now = 0L
  private val waiting = MutableStateFlow(false)
  private lateinit var guard: HeadphoneGuard

  @Before
  fun setUp() {
    connect(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    guard = HeadphoneGuard(testContext, player, { preferred = it }, audioManager, { now }, waiting)
  }

  private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

  private fun connect(type: Int): AudioDeviceInfo {
    val device = device(type)
    shadowOf(audioManager).addOutputDevice(device, true)
    ShadowLooper.idleMainLooper()
    return device
  }

  private fun speaker() = guard.outputs.value.single { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

  @Test
  fun `waits for a choice without headphones`() {
    guard.play()

    assertFalse(player.playing)
    assertTrue(waiting.value)
    // The player asks where to play; nothing opens by itself
    assertNull(shadowOf(testContext as android.app.Application).nextStartedActivity)
  }

  @Test
  fun `plays right away with headphones connected`() {
    connect(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    guard.play()

    assertTrue(player.playing)
    assertFalse(waiting.value)
  }

  @Test
  fun `lists headphones before the speaker`() {
    connect(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    assertEquals(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), guard.outputs.value.map { it.type })
  }

  @Test
  fun `picking the speaker starts the waiting request`() {
    guard.play()

    guard.useOutput(speaker())

    assertTrue(player.playing)
    assertFalse(waiting.value)
    assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, preferred?.type)
  }

  @Test
  fun `the speaker choice is remembered`() {
    guard.play()
    guard.useOutput(speaker())
    guard.pause()

    guard.play()

    assertTrue(player.playing)
  }

  @Test
  fun `switching output does not start playback`() {
    guard.useOutput(speaker())
    assertFalse(player.playing)
  }

  @Test
  fun `starts playing when headphones connect`() {
    guard.play()
    now += 60_000

    connect(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    assertTrue(player.playing)
    assertFalse(waiting.value)
  }

  @Test
  fun `does not start by surprise long after the request`() {
    guard.play()
    now += HeadphoneGuard.RESUME_WINDOW_MS + 1

    connect(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    assertFalse(player.playing)
  }

  @Test
  fun `new headphones take over from the speaker`() {
    guard.play()
    guard.useOutput(speaker())

    connect(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    assertNull(guard.chosen.value)
    assertNull(preferred)
  }

  @Test
  fun `pausing cancels the wait`() {
    guard.play()
    guard.pause()

    connect(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    assertFalse(waiting.value)
    assertFalse(player.playing)
  }
}
