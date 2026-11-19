package com.audiobookshelf.wear.sync

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Tag for requests that should try the direct connection first, e.g. downloads on Wi-Fi brought up for them. */
object DirectFirst

/**
 * Sends requests through the phone app when [ServerRoute] says the server can't be reached
 * directly, and falls back to the phone when a direct request can't connect. Must come after
 * the auth interceptor so relayed requests carry the watch's credentials.
 */
class PhoneRelayInterceptor(private val route: ServerRoute, private val phoneLink: PhoneLink) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val directFirst = request.tag(DirectFirst::class.java) != null

    if (route.current == ServerRoute.Route.PHONE && !directFirst) {
      try {
        return relay(request)
      } catch (e: IOException) {
        // The phone went away or the link broke; maybe the server is reachable directly again
        route.probeSoon()
        // A broken link may have already delivered the request, so only resend what's safe to repeat
        if (chain.call().isCanceled() || (e !is PhoneUnavailableException && !isIdempotent(request))) throw e
      }
    }

    return try {
      chain.proceed(request)
    } catch (e: IOException) {
      if (chain.call().isCanceled() || !canResendViaPhone(request, e) || !route.directFailed()) throw e
      relay(request)
    }
  }

  /**
   * A request that may have reached the server (e.g. the response timed out) is only resent when
   * repeating it is harmless, so writes like a listening session sync aren't applied twice.
   */
  private fun canResendViaPhone(request: Request, e: IOException): Boolean = isIdempotent(request) || neverSent(e)

  private fun neverSent(e: IOException): Boolean = e is ConnectException || e is UnknownHostException ||
    e is NoRouteToHostException || (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)

  private fun isIdempotent(request: Request): Boolean = request.method in IDEMPOTENT_METHODS

  private fun relay(request: Request): Response {
    val body = request.body?.let { requestBody -> Buffer().also { requestBody.writeTo(it) }.readByteArray() }
    val headers = JSONArray()
    request.headers.forEach { (name, value) -> headers.put(JSONArray().put(name).put(value)) }
    request.body?.contentType()?.let { type ->
      if (request.header("Content-Type") == null) headers.put(JSONArray().put("Content-Type").put(type.toString()))
    }

    val reply = phoneLink.call(
      PhoneLink.PATH_RELAY,
      JSONObject().put("method", request.method).put("url", request.url.toString()).put("headers", headers),
      body
    )
    val header = reply.header
    when (header.optString("error")) {
      "" -> {}
      "unreachable" -> {
        reply.close()
        throw PhoneUnavailableException("Neither the watch nor your phone can reach the server")
      }
      else -> {
        reply.close()
        throw PhoneUnavailableException("Your phone isn't signed in to this server")
      }
    }

    val responseHeaders = Headers.Builder().apply {
      val pairs = header.optJSONArray("headers") ?: JSONArray()
      for (i in 0 until pairs.length()) {
        val pair = pairs.getJSONArray(i)
        addUnsafeNonAscii(pair.getString(0), pair.getString(1))
      }
    }.build()

    val source = object : okio.ForwardingSource(reply.body.source()) {
      override fun close() {
        super.close()
        reply.close()
      }
    }.buffer()

    return Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(header.getInt("code"))
      .message(header.optString("message"))
      .headers(responseHeaders)
      .body(source.asResponseBody(responseHeaders["Content-Type"]?.toMediaTypeOrNull(), responseHeaders["Content-Length"]?.toLongOrNull() ?: -1))
      .sentRequestAtMillis(System.currentTimeMillis())
      .receivedResponseAtMillis(System.currentTimeMillis())
      .build()
  }

  private companion object {
    val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS", "PUT", "DELETE")
  }
}
