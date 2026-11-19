package com.audiobookshelf.app.device

import android.content.Context
import android.util.Log
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.server.ApiHandler
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http.HttpMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Answers requests from the Audiobookshelf Wear OS app over Wearable channels:
 *
 * - [PATH_AUTH] hands the watch the phone's server and access token, so signing in on the phone
 *   signs in the watch. The watch never gets the refresh token; when its access token expires it
 *   asks again with `staleToken` and the phone refreshes its own session.
 * - [PATH_RELAY] forwards HTTP requests to the server, for when the watch can't reach it directly
 *   (e.g. a server only reachable on the home network while the watch is on Bluetooth).
 *
 * Every message starts with a length-prefixed JSON frame. Must match `PhoneLink` in the wear module.
 */
object WatchBridge {
  private const val tag = "WatchBridge"
  const val PATH_AUTH = "/abs/auth"
  const val PATH_RELAY = "/abs/relay"
  private const val MAX_FRAME = 256 * 1024

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // Redirects go back to the watch so it sees the same responses it would get directly
  private val relayClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

  fun handle(context: Context, channel: ChannelClient.Channel) {
    val appContext = context.applicationContext
    scope.launch {
      val channels = Wearable.getChannelClient(appContext)
      try {
        DbManager.initialize(appContext)
        val input = Tasks.await(channels.getInputStream(channel), 10, TimeUnit.SECONDS)
        val output = Tasks.await(channels.getOutputStream(channel), 10, TimeUnit.SECONDS)
        when (channel.path) {
          PATH_AUTH -> writeFrame(output, auth(appContext, readFrame(input)))
          PATH_RELAY -> relay(input, output)
        }
        // Closing the stream ends the reply; the watch closes the channel once it has read everything
        output.close()
      } catch (e: Exception) {
        Log.w(tag, "Watch request on ${channel.path} failed: ${e.message}")
        runCatching { channels.close(channel) }
      }
    }
  }

  private fun currentConfig(): ServerConnectionConfig? =
    DeviceManager.serverConnectionConfig
      ?: DeviceManager.getServerConnectionConfig(DeviceManager.deviceData.lastServerConnectionConfigId)

  private fun auth(context: Context, request: JSONObject): JSONObject {
    val config = currentConfig()?.takeIf { it.token.isNotEmpty() }
      ?: return JSONObject().put("error", "signed_out")

    val staleToken = request.optString("staleToken")
    if (staleToken.isNotEmpty() && staleToken == config.token) {
      var result: ApiHandler.RefreshResult = ApiHandler.RefreshResult.Transient
      val done = CountDownLatch(1)
      ApiHandler(context).refreshAuthTokens(config.id) {
        result = it
        done.countDown()
      }
      done.await(30, TimeUnit.SECONDS)
      when (result) {
        ApiHandler.RefreshResult.Rejected -> return JSONObject().put("error", "signed_out")
        ApiHandler.RefreshResult.Transient -> return JSONObject().put("error", "refresh_failed")
        is ApiHandler.RefreshResult.Success -> {}
      }
    }

    val fresh = DeviceManager.getServerConnectionConfig(config.id) ?: config
    return JSONObject()
      .put("address", fresh.address)
      .put("username", fresh.username)
      .put("userId", fresh.userId)
      .put("accessToken", fresh.token)
      .put("customHeaders", JSONObject(fresh.customHeaders ?: emptyMap<String, String>()))
      .put("serverVersion", fresh.version)
  }

  private fun relay(input: InputStream, output: OutputStream) {
    val header = readFrame(input)
    val url = header.optString("url").toHttpUrlOrNull()
    // Only forward to servers this phone is signed in to, so the relay can't reach anything else
    if (url == null || !isSavedServer(url.toString())) {
      writeFrame(output, JSONObject().put("error", "not_allowed"))
      return
    }

    val bodyLength = header.optInt("bodyLength", 0)
    val body = ByteArray(bodyLength).also { DataInputStream(input).readFully(it) }
    val method = header.optString("method", "GET")
    val headers = header.optJSONArray("headers") ?: JSONArray()
    val contentType = (0 until headers.length()).map { headers.getJSONArray(it) }
      .firstOrNull { it.getString(0).equals("Content-Type", true) }?.getString(1)

    val requestBody = when {
      bodyLength > 0 -> body.toRequestBody(contentType?.toMediaTypeOrNull())
      HttpMethod.requiresRequestBody(method) -> ByteArray(0).toRequestBody(null)
      else -> null
    }
    val request = Request.Builder().url(url).method(method, requestBody).apply {
      for (i in 0 until headers.length()) {
        val (name, value) = headers.getJSONArray(i).let { it.getString(0) to it.getString(1) }
        if (name.lowercase() !in HOP_BY_HOP) addHeader(name, value)
      }
    }.build()

    val response = try {
      relayClient.newCall(request).execute()
    } catch (e: Exception) {
      Log.d(tag, "Phone can't reach ${url.host}: ${e.message}")
      writeFrame(output, JSONObject().put("error", "unreachable"))
      return
    }
    response.use {
      val responseHeaders = JSONArray()
      it.headers.forEach { (name, value) -> responseHeaders.put(JSONArray().put(name).put(value)) }
      writeFrame(output, JSONObject().put("code", it.code).put("message", it.message).put("headers", responseHeaders))
      it.body?.byteStream()?.copyTo(output, 64 * 1024)
    }
  }

  private fun isSavedServer(url: String): Boolean {
    val target = url.toHttpUrlOrNull() ?: return false
    return DeviceManager.deviceData.serverConnectionConfigs.any { config ->
      val saved = config.address.toHttpUrlOrNull() ?: return@any false
      saved.scheme == target.scheme && saved.host == target.host && saved.port == target.port
    }
  }

  private fun readFrame(input: InputStream): JSONObject {
    val data = DataInputStream(input)
    val length = data.readInt()
    require(length in 0..MAX_FRAME) { "Bad frame length $length" }
    val bytes = ByteArray(length).also { data.readFully(it) }
    return JSONObject(String(bytes, Charsets.UTF_8))
  }

  private fun writeFrame(output: OutputStream, json: JSONObject) {
    val bytes = json.toString().toByteArray(Charsets.UTF_8)
    DataOutputStream(output).apply {
      writeInt(bytes.size)
      write(bytes)
      flush()
    }
  }

  // Set by OkHttp for the phone's own connection
  private val HOP_BY_HOP = setOf("host", "connection", "content-length", "transfer-encoding", "keep-alive", "accept-encoding")
}
