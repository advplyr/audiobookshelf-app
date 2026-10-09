package com.audiobookshelf.wear.sync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.audiobookshelf.wear.BuildConfig
import com.audiobookshelf.wear.data.AbsApi
import com.audiobookshelf.wear.data.AuthStore
import com.audiobookshelf.wear.data.ProgressStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Sends listening sessions to the server the same way the phone app does for downloaded books
 * (`/api/session/local`). The server updates the user's progress, so the phone picks up where the
 * watch left off and vice versa.
 */
class SessionSync(
  context: Context,
  private val api: AbsApi,
  private val auth: AuthStore,
  private val progressStore: ProgressStore
) {
  private val flushMutex = Mutex()

  @SuppressLint("HardwareIds")
  val deviceInfo: JSONObject = JSONObject()
    .put("deviceId", Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))
    .put("manufacturer", Build.MANUFACTURER)
    .put("model", Build.MODEL)
    .put("sdkVersion", Build.VERSION.SDK_INT)
    .put("clientVersion", BuildConfig.VERSION_NAME)

  /** Sends the session now, or queues it to be sent later if the server can't be reached. */
  suspend fun sync(session: JSONObject) {
    if (auth.current == null) {
      progressStore.addPendingSession(session)
      return
    }
    try {
      api.syncLocalSession(session)
      progressStore.removePendingSessions(listOf(session.getString("id")))
      flushPending()
    } catch (e: Exception) {
      Log.w(TAG, "Session sync failed, queued for later: ${e.message}")
      progressStore.addPendingSession(session)
    }
  }

  suspend fun flushPending() {
    if (auth.current == null) return
    flushMutex.withLock {
      val pending = progressStore.pendingSessions()
      if (pending.isEmpty()) return
      try {
        api.syncLocalSessions(pending, deviceInfo)
        progressStore.removePendingSessions(pending.map { it.getString("id") })
        Log.i(TAG, "Synced ${pending.size} queued sessions")
      } catch (e: Exception) {
        Log.w(TAG, "Could not sync queued sessions: ${e.message}")
      }
    }
  }

  companion object {
    private const val TAG = "SessionSync"
  }
}
