package com.audiobookshelf.app.device

import android.content.Context
import android.util.Log
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/**
 * Tells the Audiobookshelf Wear OS app when the phone's server or user changes (the watch then
 * asks [WatchBridge] for a sign in). Path and keys must match `PhoneConfig` in the wear module.
 */
object WatchSync {
  private const val tag = "WatchSync"
  private const val PATH_SERVER = "/abs/server"

  fun publishServerConfig(context: Context, config: ServerConnectionConfig?) {
    if (config == null) return publishSignedOut(context)
    put(context, PutDataMapRequest.create(PATH_SERVER).apply {
      dataMap.putString("address", config.address)
      dataMap.putString("username", config.username)
      dataMap.putString("customHeaders", JSONObject(config.customHeaders ?: emptyMap<String, String>()).toString())
    })
  }

  /** Tells the watch the phone signed out, so a watch signed in through the phone signs out too. */
  fun publishSignedOut(context: Context) {
    put(context, PutDataMapRequest.create(PATH_SERVER).apply {
      dataMap.putString("address", "")
      // Makes the item change even if the phone was already signed out
      dataMap.putLong("signedOutAt", System.currentTimeMillis())
    })
  }

  private fun put(context: Context, data: PutDataMapRequest) {
    val request = data.asPutDataRequest().setUrgent()
    Wearable.getDataClient(context.applicationContext).putDataItem(request)
      .addOnFailureListener { e ->
        // Expected on phones without a paired watch or the Wear OS companion
        Log.d(tag, "Could not share server with watch: ${e.message}")
      }
  }
}
