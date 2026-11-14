package com.audiobookshelf.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import java.util.UUID
import org.json.JSONObject

// Paper owns requests made while the WebView is stopped. JavaScript imports
// them into the existing offline bookmark queue before removing them here.
data class AndroidAutoBookmarkRequest(
  val id: String,
  val serverConnectionConfigId: String,
  val serverAddress: String,
  val userId: String,
  val libraryItemId: String,
  val time: Long,
  val title: String
)

data class AndroidAutoBookmark(val time: Long, val title: String)

class AndroidAutoBookmarks(private val context: Context) {
  private val tag = "AndroidAutoBookmarks"
  private data class Account(val id: String, val address: String, val userId: String, val itemId: String, val userIds: Set<String>)

  private fun account(session: PlaybackSession?): Account? {
    val playbackSession = session ?: return null
    if (playbackSession.mediaType != "book" || playbackSession.libraryItemId.isNullOrBlank()) return null
    val config = DeviceManager.getServerConnectionConfig(playbackSession.serverConnectionConfigId) ?: run {
      Log.w(tag, "Bookmark unavailable: playback server configuration is missing")
      return null
    }
    // Offline mode can clear the active configuration. A downloaded session
    // still carries the server and user that own its bookmark data.
    if (!matchesBookmarkAccount(DeviceManager.deviceData.lastServerConnectionConfigId, config.id, playbackSession.isLocal)) {
      Log.w(tag, "Bookmark unavailable: playback account differs from active account")
      return null
    }
    if (playbackSession.serverAddress != config.address) {
      Log.w(tag, "Bookmark unavailable: playback server address differs from saved configuration")
      return null
    }
    val record = record(config.id, config.userId, config.address)
    val aliases = record?.optJSONArray("aliases")
    val userIds = mutableSetOf(config.userId)
    for (i in 0 until (aliases?.length() ?: 0)) aliases?.optString(i)?.let { userIds.add(it) }
    val userMatches = playbackSession.userId in userIds
    if (!userMatches) {
      Log.w(tag, "Bookmark unavailable: playback user differs from saved configuration")
      return null
    }
    return Account(config.id, config.address, config.userId, playbackSession.libraryItemId!!, userIds)
  }

  fun canUse(session: PlaybackSession?): Boolean = account(session) != null

  private fun record(configId: String, userId: String, address: String): JSONObject? {
    val key = "offlineBookmarks:v1:${Uri.encode(configId)}:${Uri.encode(userId)}"
    val value = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).getString(key, null) ?: return null
    return try {
      JSONObject(value).takeIf { it.optInt("version") == 1 && it.optString("address") == address }
    } catch (_: Exception) { null }
  }

  fun visible(session: PlaybackSession?): List<AndroidAutoBookmark> {
    val account = account(session) ?: return emptyList()
    val bookmarks = sortedMapOf<Long, AndroidAutoBookmark>()
    val saved = record(account.id, account.userId, account.address)
    val snapshot = saved?.optJSONArray("snapshot")
    for (i in 0 until (snapshot?.length() ?: 0)) {
      val entry = snapshot?.optJSONObject(i) ?: continue
      if (entry.optString("libraryItemId") == account.itemId) {
        val time = entry.optLong("time", -1)
        if (time >= 0) bookmarks[time] = AndroidAutoBookmark(time, entry.optString("title"))
      }
    }
    val pending = saved?.optJSONArray("pending")
    for (i in 0 until (pending?.length() ?: 0)) {
      val operation = pending?.optJSONObject(i) ?: continue
      val entry = operation.optJSONObject("bookmark") ?: continue
      if (entry.optString("libraryItemId") != account.itemId) continue
      val time = entry.optLong("time", -1)
      if (time < 0) continue
      if (operation.optString("type") == "delete") bookmarks.remove(time)
      else bookmarks[time] = AndroidAutoBookmark(time, entry.optString("title"))
    }
    DeviceManager.dbManager.getAndroidAutoBookmarkRequests().forEach { request ->
      if (request.serverConnectionConfigId == account.id && request.serverAddress == account.address && request.userId in account.userIds && request.libraryItemId == account.itemId) {
        bookmarks[request.time] = AndroidAutoBookmark(request.time, request.title)
      }
    }
    return bookmarks.values.toList()
  }

  @Synchronized fun create(session: PlaybackSession?, time: Long): AndroidAutoBookmarkRequest? {
    val account = account(session) ?: return null
    if (time < 0) {
      Log.w(tag, "Bookmark ignored: invalid playback position $time")
      return null
    }
    if (visible(session).any { it.time == time }) {
      Log.i(tag, "Bookmark ignored: one already exists at position ${time}s")
      return null
    }
    val title = "Bookmark ${formatTime(time)}"
    val request = AndroidAutoBookmarkRequest(UUID.randomUUID().toString(), account.id, account.address, account.userId, account.itemId, time, title)
    DeviceManager.dbManager.saveAndroidAutoBookmarkRequest(request)
    return request
  }

  companion object {
    internal fun matchesBookmarkAccount(activeConfigId: String?, sessionConfigId: String, isLocal: Boolean): Boolean =
      activeConfigId == sessionConfigId || (activeConfigId.isNullOrBlank() && isLocal)

    fun formatTime(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
  }
}
