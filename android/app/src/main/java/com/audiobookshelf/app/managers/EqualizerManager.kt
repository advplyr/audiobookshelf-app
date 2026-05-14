package com.audiobookshelf.app.managers

import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.data.EqualizerBand
import com.audiobookshelf.app.player.PlayerNotificationService

class EqualizerManager
constructor(private val playerNotificationService: PlayerNotificationService) {
  private val tag = "EqualizerManager"
  lateinit var equalizer: Equalizer
  private var bandChangeRequests = mutableMapOf<Int, Short>()
  private var debounceHandler: Handler? = null
  private var debounceHandlerFinished = true
  companion object {
    private const val DEBOUNCE_MS = 400L // How often updates are made
  }

  // Everytime a new audio session is made, this needs to be called
  fun setup(audioSessionId: Int) {
    try {
      if (audioSessionId == 0) {
        Log.w(tag, "audio session not ready yet, audioSessionId=0")
        return
      }
      equalizer = Equalizer(0, audioSessionId)
      equalizer.enabled = true

      Log.d(tag, "Equalizer initialized")
    } catch (e: Exception) {
      Log.e(tag, "Error initializing Equalizer: $e")
    }
  }

  fun getAvailableFrequencies(): List<Int> {
    val frequencies = mutableListOf<Int>()
    for (i in 0 until equalizer.numberOfBands) {
      val centerFreq = equalizer.getCenterFreq(i.toShort()) / 1000 // in Hz
      frequencies.add(centerFreq)
    }

    return frequencies
  }

  fun emitAvailableFrequencies() {
    playerNotificationService.clientEventEmitter?.onEqualizerFrequenciesSet(getAvailableFrequencies())
  }

  private fun requestBandChange(band: Int, gain: Short) {
    // Non standard debounce, doesn't refresh delay, instead it chunks requests
    bandChangeRequests[band] = gain // Update band with newest gain

    if (!debounceHandlerFinished) {
      return
    }

    // Set new debounce handler if previous one finished
    debounceHandler = Handler(Looper.getMainLooper())
    debounceHandlerFinished = false
    debounceHandler?.postDelayed({
      // Apply the latest requested gains
      for ((band, gain) in bandChangeRequests) {
        equalizer.setBandLevel(band.toShort(), gain)
      }
      bandChangeRequests.clear()
      debounceHandlerFinished = true
    }, DEBOUNCE_MS)
  }

  fun updateBands(bands: List<EqualizerBand>) {
    // Do a sanity check on given band frequencies to ensure they match
    val givenFrequencies = bands.map { it.freq }
    val availableFrequencies = getAvailableFrequencies()

    // These SHOULD match, as the frontend does some work to maintain continuity. But good practice to check anyway
    if (givenFrequencies != availableFrequencies) {
      Log.i(tag, "Given equalizer frequencies do not match with available ones")
      return
    }

    // Now apply the bands to the equalizer
    for ((i, band) in bands.withIndex()) {
      requestBandChange(i, band.gain.toShort())
      Log.d(tag, "Equalizer, setting band #$i (${band.freq}Hz) to ${band.gain}")
    }
  }
}


