package com.audiobookshelf.app

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

class CastOptionsProvider : OptionsProvider {
  override fun getCastOptions(context: Context): CastOptions {
    Log.d("CastOptionsProvider", "getCastOptions")
    return try {
      val appId = "FD1F76C5"
      CastOptions.Builder()
        .setReceiverApplicationId(appId).setCastMediaOptions(
          CastMediaOptions.Builder()
            .setMediaSessionEnabled(false)
            .setNotificationOptions(null)
            .build()
        )
        .setStopReceiverApplicationWhenEndingSession(true).build()
    } catch (e: Exception) {
      Log.w("CastOptionsProvider", "Cast initialization failed, using default options: ${e.message}")
      CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build()
    }
  }

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
    return null
  }
}
