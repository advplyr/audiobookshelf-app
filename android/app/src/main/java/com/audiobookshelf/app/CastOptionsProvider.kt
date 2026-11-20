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
    return CastOptions.Builder()
      .setReceiverApplicationId(CastConstants.RECEIVER_APPLICATION_ID).setCastMediaOptions(
        CastMediaOptions.Builder()
          // We manage the media session and the notifications ourselves.
          .setMediaSessionEnabled(false)
          .setNotificationOptions(null)
          .build()
      )
      .setStopReceiverApplicationWhenEndingSession(true).build()
  }

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
    return null
  }
}
