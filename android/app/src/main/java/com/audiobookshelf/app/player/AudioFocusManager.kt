package com.audiobookshelf.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.audiobookshelf.app.BuildConfig

class AudioFocusManager(
  private val context: Context,
  private val tag: String,
  private val playerController: PlayerController
) {

  private val audioManager: AudioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
  private var audioFocusRequest: AudioFocusRequest? = null
  private var isDucked = false
  private var lastUnduckedVolume: Float = 1f

  interface PlayerController {
    /** Return previous volume when ducking (if applicable) so we can restore on gain. */
    fun duck(): Float?
    fun unduck(previousVolume: Float?)
    fun pause()
    fun isLocalPlayback(): Boolean
  }

  private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
      AudioManager.AUDIOFOCUS_GAIN -> {
        if (isDucked) {
          playerController.unduck(lastUnduckedVolume)
          isDucked = false
        }
      }

      AudioManager.AUDIOFOCUS_LOSS,
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        playerController.pause()
        abandonAudioFocus()
      }

      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        if (playerController.isLocalPlayback()) {
          lastUnduckedVolume = playerController.duck() ?: lastUnduckedVolume
          isDucked = true
        }
      }
    }
  }

  fun requestAudioFocus(): Boolean {
    if (!playerController.isLocalPlayback()) return true

    val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      requestAudioFocusOreo()
    } else {
      @Suppress("DEPRECATION")
      audioManager.requestAudioFocus(
        audioFocusChangeListener,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN
      )
    }

    val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    if (!granted && BuildConfig.DEBUG) {
      Log.w(tag, "Audio focus request was denied; continuing playback best-effort.")
    }
    return true
  }

  fun abandonAudioFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(audioFocusChangeListener)
    }
    if (isDucked) {
      playerController.unduck(lastUnduckedVolume)
      isDucked = false
    }
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun requestAudioFocusOreo(): Int {
    if (audioFocusRequest == null) {
      val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
      audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(audioFocusChangeListener)
        .build()
    }
    return audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
      ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
  }
}
