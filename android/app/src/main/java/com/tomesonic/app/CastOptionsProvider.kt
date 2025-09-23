package com.tomesonic.app

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * CastOptionsProvider for Media3 integration with Audiobookshelf
 * Configures Cast Framework for book-level audiobook playback with skip controls
 */
class CastOptionsProvider : OptionsProvider {
    companion object {
        private const val TAG = "CastOptionsProvider"
    }

    override fun getCastOptions(context: Context): CastOptions {
        // Get Cast App ID from string resources for centralized configuration
        val castAppId = context.getString(R.string.cast_app_id)

        Log.d(TAG, "getCastOptions: Configuring Cast options for styled receiver with skip controls")
        Log.d(TAG, "getCastOptions: Using Cast App ID: $castAppId")

        // Create notification options with skip controls for audiobooks
        val notificationOptions = NotificationOptions.Builder()
            .setActions(listOf(
                MediaIntentReceiver.ACTION_REWIND,
                MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                MediaIntentReceiver.ACTION_FORWARD
            ), intArrayOf(0, 1, 2))
            .setSkipStepMs(30000) // 30 second skip for audiobooks
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(castAppId)
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    // Enable media session for book-level playback
                    .setMediaSessionEnabled(true)
                    // Add skip controls to notification
                    .setNotificationOptions(notificationOptions)
                    // Expanded controller shows Audiobookshelf activity
                    .setExpandedControllerActivityClassName(
                        "com.tomesonic.app.MainActivity"
                    )
                    .build()
            )
            // Stop receiver when session ends to save battery
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
            .also {
                Log.d(TAG, "getCastOptions: Cast options created with receiver app ID: ${it.receiverApplicationId}")
            }
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        Log.d(TAG, "getAdditionalSessionProviders: No additional session providers")
        return null
    }
}
