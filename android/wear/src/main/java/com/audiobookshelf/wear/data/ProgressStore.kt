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

  /** Queues a session report, unless a newer report for the same session is already queued. */
  @Synchronized
  fun addPendingSession(session: JSONObject) {
    val id = session.getString("id")
    val queued = pendingPrefs.getString(id, null)?.let(::JSONObject)
    if (queued != null && queued.optLong("updatedAt") > session.optLong("updatedAt")) return
    pendingPrefs.edit().putString(id, session.toString()).apply()
  }

  /**
   * Removes queued reports that have been sent. A report that was replaced by a newer one while
   * the older copy was being sent is kept, so it still gets sent.
   */
  @Synchronized
  fun removePendingSessions(sent: Collection<JSONObject>) {
    val editor = pendingPrefs.edit()
    sent.forEach { session ->
      val id = session.getString("id")
      val queued = pendingPrefs.getString(id, null)?.let(::JSONObject) ?: return@forEach
      if (queued.optLong("updatedAt") <= session.optLong("updatedAt")) editor.remove(id)
    }
    editor.apply()
  }

  @Synchronized
  fun pendingSessions(): List<JSONObject> =
    pendingPrefs.all.values.mapNotNull { (it as? String)?.let(::JSONObject) }
}
