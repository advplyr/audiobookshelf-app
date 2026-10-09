package com.audiobookshelf.app.device

import android.content.Context
import android.util.Log
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/**
 * Shares the connected server with the Audiobookshelf Wear OS app so the watch can prefill its
 * sign in. Tokens are not shared: the watch keeps its own session because the server rotates
 * refresh tokens. Path and keys must match `PhoneConfig` in the wear module.
 */
object WatchSync {
  private const val tag = "WatchSync"
  private const val PATH_SERVER = "/abs/server"

  fun publishServerConfig(context: Context, config: ServerConnectionConfig?) {
    if (config == null) return
    val request = PutDataMapRequest.create(PATH_SERVER).apply {
      dataMap.putString("address", config.address)
      dataMap.putString("username", config.username)
      dataMap.putString("customHeaders", JSONObject(config.customHeaders ?: emptyMap<String, String>()).toString())
    }.asPutDataRequest().setUrgent()

    Wearable.getDataClient(context.applicationContext).putDataItem(request)
      .addOnFailureListener { e ->
        // Expected on phones without a paired watch or the Wear OS companion
        Log.d(tag, "Could not share server with watch: ${e.message}")
      }
  }
}
