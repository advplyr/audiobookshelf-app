package com.audiobookshelf.app.player.media3

import android.content.Context
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import com.audiobookshelf.app.player.wrapper.AbsPlayerWrapper
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@UnstableApi
class Media3CastCoordinator(
  private val context: Context,
  private val scope: CoroutineScope,
  private val speechAudioAttributes: AudioAttributes,
  private val onSwitchToCast: (AbsPlayerWrapper) -> Unit,
  private val onSwitchToLocal: () -> Unit,
  private val pauseLocalForCasting: () -> Unit,
  private val debug: (msg: () -> String) -> Unit = { }
) {
  private val tag = "Media3CastCoordinator"

  fun initialize(): AbsPlayerWrapper? {
    return try {
      val castContext = CastContext.getSharedInstance(context)
      val coreCastPlayer = CastPlayer(castContext).apply {
        setAudioAttributes(speechAudioAttributes, true)
      }
      val wrapper = AbsPlayerWrapper(coreCastPlayer, context)

      coreCastPlayer.setSessionAvailabilityListener(object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
          scope.launch {
            debug { "Cast session available. Switching to CastPlayer." }
            pauseLocalForCasting()
            onSwitchToCast(wrapper)
          }
        }

        override fun onCastSessionUnavailable() {
          scope.launch {
            debug { "Cast session unavailable. Switching back to local player." }
            onSwitchToLocal()
          }
        }
      })

      debug { "Cast player initialized and listener attached." }
      wrapper
    } catch (e: Exception) {
      Log.e(tag, "Failed to initialize CastContext. Cast feature will be unavailable.", e)
      null
    }
  }
}
