package com.audiobookshelf.wear.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.audiobookshelf.wear.data.PhoneServerConfig
import com.audiobookshelf.wear.data.toStringMap
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * The server the phone app is connected to, shared over the Wearable Data Layer.
 * Data item path and keys must match `WatchSync` in the phone app.
 */
class PhoneConfig(private val context: Context, private val onPhoneSignedOut: () -> Unit = {}) {
  private val prefs = context.getSharedPreferences("phone_config", Context.MODE_PRIVATE)
  private val _config = MutableStateFlow(load())
  val config: StateFlow<PhoneServerConfig?> = _config.asStateFlow()

  fun update(dataMap: DataMap) {
    val address = dataMap.getString(KEY_ADDRESS).orEmpty()
    if (address.isEmpty()) {
      if (dataMap.containsKey(KEY_SIGNED_OUT_AT)) {
        prefs.edit().clear().apply()
        _config.value = null
        onPhoneSignedOut()
      }
      return
    }
    prefs.edit()
      .putString(KEY_ADDRESS, address)
      .putString(KEY_USERNAME, dataMap.getString(KEY_USERNAME).orEmpty())
      .putString(KEY_HEADERS, dataMap.getString(KEY_HEADERS) ?: "{}")
      .apply()
    _config.value = load()
  }

  /** Reads the latest item from the Data Layer in case the listener service missed an update. */
  suspend fun refreshFromPhone() {
    try {
      val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(PATH_SERVER).build()
      val items = Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_PREFIX).await()
      try {
        items.forEach { item -> update(DataMapItem.fromDataItem(item).dataMap) }
      } finally {
        items.release()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not read phone config", e)
    }
  }

  private fun load(): PhoneServerConfig? {
    val address = prefs.getString(KEY_ADDRESS, null) ?: return null
    return PhoneServerConfig(
      serverAddress = address,
      username = prefs.getString(KEY_USERNAME, "").orEmpty(),
      customHeaders = runCatching { JSONObject(prefs.getString(KEY_HEADERS, "{}")!!).toStringMap() }.getOrDefault(emptyMap())
    )
  }

  companion object {
    private const val TAG = "PhoneConfig"
    const val PATH_SERVER = "/abs/server"
    const val KEY_ADDRESS = "address"
    const val KEY_USERNAME = "username"
    const val KEY_HEADERS = "customHeaders"
    const val KEY_SIGNED_OUT_AT = "signedOutAt"
  }
}
