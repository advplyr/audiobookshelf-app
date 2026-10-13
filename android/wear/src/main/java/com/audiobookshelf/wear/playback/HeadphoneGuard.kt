package com.audiobookshelf.wear.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asks where to play before using the watch speaker, like Spotify: with no headphones connected,
 * a play request waits (see [waitingForHeadphones]) while the player offers to connect a device or
 * play on the watch speaker. Wraps the player handed to the media session, so it covers the app,
 * the media notification, headset buttons and the tile. Playback starts by itself when headphones
 * connect soon after.
 */
@OptIn(UnstableApi::class)
class HeadphoneGuard(
  private val context: Context,
  player: Player,
  /** Routes the player's audio to a device, or back to the system's choice with null. */
  private val setPreferredDevice: (AudioDeviceInfo?) -> Unit,
  private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java),
  private val clock: () -> Long = SystemClock::elapsedRealtime,
  private val _waiting: MutableStateFlow<Boolean> = MutableStateFlow(false)
) : ForwardingPlayer(player) {

  /** True while a play request waits for the listener to pick where to play. */
  val waitingForHeadphones: StateFlow<Boolean> = _waiting.asStateFlow()

  private val _outputs = MutableStateFlow(currentOutputs())

  /** Connected headphones, then the watch speaker if it has one. */
  val outputs: StateFlow<List<AudioDeviceInfo>> = _outputs.asStateFlow()

  private val _chosen = MutableStateFlow<AudioDeviceInfo?>(null)

  /** The output picked in the player, while it stays connected. Null means the system's choice. */
  val chosen: StateFlow<AudioDeviceInfo?> = _chosen.asStateFlow()

  private var waitingSince = 0L

  private val deviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
      _outputs.value = currentOutputs()
      if (addedDevices.none(::isHeadphones)) return
      // New headphones take over from the speaker, as they would in other media apps
      choose(null)
      if (_waiting.value) {
        val waitedFor = clock() - waitingSince
        stopWaiting()
        if (waitedFor <= RESUME_WINDOW_MS) {
          Log.i(TAG, "Headphones connected, starting playback")
          super@HeadphoneGuard.play()
        }
      }
    }

    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
      _outputs.value = currentOutputs()
      if (removedDevices.any { it.id == _chosen.value?.id }) choose(null)
    }
  }

  init {
    audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
  }

  /** Stops listening for devices; call when the service shuts down. */
  fun unregister() {
    audioManager.unregisterAudioDeviceCallback(deviceCallback)
  }

  override fun play() {
    if (canPlay()) super.play() else waitForOutput()
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    if (!playWhenReady) {
      stopWaiting()
      super.setPlayWhenReady(false)
    } else if (canPlay()) {
      super.setPlayWhenReady(true)
    } else {
      waitForOutput()
    }
  }

  override fun pause() {
    stopWaiting()
    super.pause()
  }

  /**
   * Plays on [device], e.g. the watch speaker or headphones picked in the player. Starts a play
   * request that was waiting for an output; otherwise only switches where audio goes.
   */
  fun useOutput(device: AudioDeviceInfo) {
    choose(device)
    if (_waiting.value) {
      stopWaiting()
      super.play()
    }
  }

  fun openBluetoothPicker() {
    try {
      context.startActivity(bluetoothSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
      Log.w(TAG, "Could not open the Bluetooth picker: ${e.message}")
    }
  }

  fun hasHeadphones(): Boolean = _outputs.value.any(::isHeadphones)

  private fun canPlay() = hasHeadphones() || _chosen.value != null

  private fun choose(device: AudioDeviceInfo?) {
    _chosen.value = device
    setPreferredDevice(device)
  }

  private fun currentOutputs(): List<AudioDeviceInfo> {
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    // A Bluetooth device can show up once per profile; list it once
    val headphones = devices.filter(::isHeadphones).distinctBy { it.productName.toString() to it.type }
    return headphones + devices.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.take(1)
  }

  private fun waitForOutput() {
    waitingSince = clock()
    _waiting.value = true
  }

  private fun stopWaiting() {
    _waiting.value = false
  }

  companion object {
    private const val TAG = "HeadphoneGuard"
    /** Headphones connected later than this don't start playback by surprise. */
    const val RESUME_WINDOW_MS = 5 * 60_000L

    private val HEADPHONE_TYPES = setOf(
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
      AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
      AudioDeviceInfo.TYPE_WIRED_HEADSET,
      AudioDeviceInfo.TYPE_USB_HEADSET,
      AudioDeviceInfo.TYPE_HEARING_AID,
      // BLE audio (Android 12+); the values are fixed, so older watches simply never report them
      26, // TYPE_BLE_HEADSET
      27, // TYPE_BLE_SPEAKER
      30 // TYPE_BLE_BROADCAST
    )

    fun isHeadphones(device: AudioDeviceInfo) = device.type in HEADPHONE_TYPES

    /** Wear OS output switcher that closes once headphones connect. */
    fun bluetoothSettingsIntent() = Intent("android.settings.BLUETOOTH_SETTINGS").apply {
      putExtra("EXTRA_CONNECTION_ONLY", true)
      putExtra("EXTRA_CLOSE_ON_CONNECT", true)
      putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1)
    }
  }
}
