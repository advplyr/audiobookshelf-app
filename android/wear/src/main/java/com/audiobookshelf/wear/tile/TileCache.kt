package com.audiobookshelf.wear.tile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last server data each tile showed, so tiles appear instantly and still work offline.
 * Server data is refreshed in the background whenever a tile is drawn.
 */
class TileCache(context: Context) {
  private val prefs = context.getSharedPreferences("tiles", Context.MODE_PRIVATE)

  var continueEntries: List<ContinueEntry>?
    get() = prefs.getString(KEY_CONTINUE, null)?.let { ContinueEntry.listFromJson(JSONArray(it)) }
    set(value) = prefs.edit().putString(KEY_CONTINUE, value?.let { ContinueEntry.listToJson(it).toString() }).apply()

  /** The raw listening stats response and when it was fetched. */
  fun stats(): Pair<JSONObject, Long>? {
    val json = prefs.getString(KEY_STATS, null) ?: return null
    return JSONObject(json) to prefs.getLong(KEY_STATS_AT, 0L)
  }

  fun saveStats(json: JSONObject, fetchedAt: Long) {
    prefs.edit().putString(KEY_STATS, json.toString()).putLong(KEY_STATS_AT, fetchedAt).apply()
  }

  /** Server data belongs to the signed in user; drop it when they change. */
  fun clear() {
    prefs.edit().clear().apply()
  }

  companion object {
    private const val KEY_CONTINUE = "continue"
    private const val KEY_STATS = "stats"
    private const val KEY_STATS_AT = "statsAt"
  }
}
