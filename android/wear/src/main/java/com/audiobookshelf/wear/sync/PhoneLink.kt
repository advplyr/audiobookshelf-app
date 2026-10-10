package com.audiobookshelf.wear.sync

import android.content.Context
import android.util.Log
import com.audiobookshelf.wear.data.Account
import com.audiobookshelf.wear.data.toStringMap
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A request to the phone failed. The message is shown to the user as is. */
class PhoneUnavailableException(message: String) : IOException(message)

/** The phone app answered but has no signed in server. */
class PhoneSignedOutException : IOException("Sign in to Audiobookshelf on your phone first")

/**
 * Talks to the Audiobookshelf phone app over Wearable channels, one channel per request.
 * Paths and the framing (length-prefixed JSON, then raw bytes) must match `WatchBridge` in the phone app.
 */
class PhoneLink(context: Context) {
  private val appContext = context.applicationContext
  private val channels by lazy { Wearable.getChannelClient(appContext) }
  private val watchdog = Executors.newSingleThreadScheduledExecutor()

  /** A response from the phone. [body] streams until the phone closes the channel; close it when done. */
  class Reply(val header: JSONObject, val body: InputStream, private val onClose: () -> Unit) : AutoCloseable {
    override fun close() = onClose()
  }

  @Volatile private var cachedNode: Pair<String, Long>? = null

  /** The nearest connected phone running this app's phone build. Blocking. */
  fun phoneNode(): String {
    cachedNode?.let { (node, at) -> if (System.currentTimeMillis() - at < NODE_CACHE_MS) return node }
    return findPhoneNode().also { cachedNode = it to System.currentTimeMillis() }
  }

  private fun findPhoneNode(): String {
    val capability = try {
      Tasks.await(
        Wearable.getCapabilityClient(appContext).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE),
        5, TimeUnit.SECONDS
      )
    } catch (e: Exception) {
      throw PhoneUnavailableException("Can't connect to your phone")
    }
    val nodes = capability.nodes
    nodes.firstOrNull { it.isNearby }?.let { return it.id }
    nodes.firstOrNull()?.let { return it.id }

    val connected = runCatching {
      Tasks.await(Wearable.getNodeClient(appContext).connectedNodes, 5, TimeUnit.SECONDS)
    }.getOrDefault(emptyList())
    throw PhoneUnavailableException(
      if (connected.isEmpty()) "Phone not connected"
      else "Audiobookshelf on your phone needs updating. Install the phone app from the same release as the watch app."
    )
  }

  fun isPhoneReachable(): Boolean = runCatching { phoneNode() }.isSuccess

  /** Opens a channel at [path], sends [header] and [body], and waits for the phone's reply header. Blocking. */
  fun call(path: String, header: JSONObject, body: ByteArray? = null): Reply {
    val node = phoneNode()
    val channel = try {
      Tasks.await(channels.openChannel(node, path), TIMEOUT_S, TimeUnit.SECONDS)
    } catch (e: Exception) {
      cachedNode = null
      throw PhoneUnavailableException("Can't connect to your phone")
    }
    var closed = false
    fun close() {
      if (closed) return
      closed = true
      runCatching { channels.close(channel) }
    }

    // Reading has no timeout of its own, so close the channel if the phone doesn't answer
    val timeout = watchdog.schedule({ close() }, REPLY_TIMEOUT_S, TimeUnit.SECONDS)
    try {
      val output = Tasks.await(channels.getOutputStream(channel), TIMEOUT_S, TimeUnit.SECONDS)
      val input = Tasks.await(channels.getInputStream(channel), TIMEOUT_S, TimeUnit.SECONDS)
      writeFrame(output, header.put("bodyLength", body?.size ?: 0))
      body?.let { output.write(it) }
      output.flush()
      val reply = readFrame(input)
      timeout.cancel(false)
      return Reply(reply, input) { close() }
    } catch (e: Exception) {
      timeout.cancel(false)
      close()
      Log.w(TAG, "Phone request $path failed", e)
      throw e as? IOException ?: PhoneUnavailableException("Can't connect to your phone")
    }
  }

  /**
   * The phone's server and a current access token. Pass the token the server just rejected as
   * [staleToken] to have the phone refresh its session first.
   */
  suspend fun requestAccount(staleToken: String? = null): Account = withContext(Dispatchers.IO) { requestAccountBlocking(staleToken) }

  fun requestAccountBlocking(staleToken: String? = null): Account {
    val reply = call(PATH_AUTH, JSONObject().apply { if (staleToken != null) put("staleToken", staleToken) })
    reply.close()
    val json = reply.header
    when (json.optString("error")) {
      "" -> {}
      "signed_out" -> throw PhoneSignedOutException()
      else -> throw PhoneUnavailableException("Your phone couldn't refresh its sign in")
    }
    return Account(
      serverAddress = json.getString("address").trimEnd('/'),
      username = json.optString("username"),
      userId = json.optString("userId"),
      accessToken = json.getString("accessToken"),
      refreshToken = null,
      customHeaders = json.optJSONObject("customHeaders")?.toStringMap() ?: emptyMap(),
      serverVersion = json.optString("serverVersion").ifEmpty { null },
      linkedToPhone = true
    )
  }

  companion object {
    private const val TAG = "PhoneLink"
    const val CAPABILITY = "abs_phone_app"
    const val PATH_AUTH = "/abs/auth"
    const val PATH_RELAY = "/abs/relay"
    private const val TIMEOUT_S = 10L
    private const val REPLY_TIMEOUT_S = 45L
    private const val MAX_FRAME = 256 * 1024
    private const val NODE_CACHE_MS = 30_000L

    fun readFrame(input: InputStream): JSONObject {
      val data = DataInputStream(input)
      val length = data.readInt()
      if (length !in 0..MAX_FRAME) throw IOException("Bad reply from phone")
      val bytes = ByteArray(length).also { data.readFully(it) }
      return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun writeFrame(output: OutputStream, json: JSONObject) {
      val bytes = json.toString().toByteArray(Charsets.UTF_8)
      DataOutputStream(output).apply {
        writeInt(bytes.size)
        write(bytes)
      }
    }
  }
}
