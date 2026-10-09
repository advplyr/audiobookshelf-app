package com.audiobookshelf.wear.data

import android.content.Context
import org.json.JSONObject

data class LocalProgress(val currentTime: Double, val updatedAt: Long)

/**
 * Remembers where the watch left off in each book, plus listening sessions that could not be sent
 * to the server yet (e.g. while offline). Pending sessions are sent on the next successful sync.
 */
class ProgressStore(context: Context) {
  private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
  private val pendingPrefs = context.getSharedPreferences("pending_sessions", Context.MODE_PRIVATE)

  fun get(itemId: String): LocalProgress? {
    val stored = prefs.getString(itemId, null) ?: return null
    val json = JSONObject(stored)
    return LocalProgress(json.optDouble("currentTime", 0.0), json.optLong("updatedAt"))
  }

  fun save(itemId: String, currentTime: Double, updatedAt: Long) {
    prefs.edit()
      .putString(itemId, JSONObject().put("currentTime", currentTime).put("updatedAt", updatedAt).toString())
      .apply()
  }

  @Synchronized
  fun addPendingSession(session: JSONObject) {
    pendingPrefs.edit().putString(session.getString("id"), session.toString()).apply()
  }

  @Synchronized
  fun removePendingSessions(ids: Collection<String>) {
    pendingPrefs.edit().apply { ids.forEach { remove(it) } }.apply()
  }

  @Synchronized
  fun pendingSessions(): List<JSONObject> =
    pendingPrefs.all.values.mapNotNull { (it as? String)?.let(::JSONObject) }
}
