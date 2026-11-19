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
  private val progressStore: ProgressStore,
  /** Called after listening time reached the server. */
  private val onSent: () -> Unit = {}
) {
  private val sendMutex = Mutex()

  @SuppressLint("HardwareIds")
  val deviceInfo: JSONObject = JSONObject()
    .put("deviceId", Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))
    .put("manufacturer", Build.MANUFACTURER)
    .put("model", Build.MODEL)
    .put("sdkVersion", Build.VERSION.SDK_INT)
    .put("clientVersion", BuildConfig.VERSION_NAME)

  /** Newest report sent per session, so a late, older report can't roll the session back. */
  private val lastSent = mutableMapOf<String, Long>()

  /** Sends the session now, or queues it to be sent later if the server can't be reached. */
  suspend fun sync(session: JSONObject) {
    if (auth.current == null) {
      progressStore.addPendingSession(session)
      return
    }
    // One request at a time, so reports reach the server in the order they were made
    val sent = sendMutex.withLock {
      val id = session.getString("id")
      val updatedAt = session.optLong("updatedAt")
      if (updatedAt < (lastSent[id] ?: 0L)) return
      try {
        api.syncLocalSession(session)
        lastSent[id] = updatedAt
        progressStore.removePendingSessions(listOf(session))
        true
      } catch (e: Exception) {
        Log.w(TAG, "Session sync failed, queued for later: ${e.message}")
        progressStore.addPendingSession(session)
        false
      }
    }
    if (sent) {
      onSent()
      flushPending()
    }
  }

  suspend fun flushPending() {
    if (auth.current == null) return
    sendMutex.withLock {
      val queued = progressStore.pendingSessions()
      // A queued report older than one already sent for the same session would roll it back
      val (stale, pending) = queued.partition { it.optLong("updatedAt") < (lastSent[it.getString("id")] ?: 0L) }
      progressStore.removePendingSessions(stale)
      if (pending.isEmpty()) return
      try {
        api.syncLocalSessions(pending, deviceInfo)
        pending.forEach { lastSent[it.getString("id")] = maxOf(lastSent[it.getString("id")] ?: 0L, it.optLong("updatedAt")) }
        progressStore.removePendingSessions(pending)
        Log.i(TAG, "Synced ${pending.size} queued sessions")
        onSent()
      } catch (e: Exception) {
        Log.w(TAG, "Could not sync queued sessions: ${e.message}")
      }
    }
  }

  companion object {
    private const val TAG = "SessionSync"
  }
}
