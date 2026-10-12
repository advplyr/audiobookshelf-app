package com.audiobookshelf.app.server

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.models.User
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.managers.SecureStorage
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.EMPTY_REQUEST
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.util.concurrent.TimeUnit

/**
 * Whether the server applied a progress sync. Positions are absolute, but the server adds the
 * listening time of every sync request, so a sync that may have been applied must not be resent.
 */
enum class SyncOutcome {
  APPLIED,
  /** The request never reached the server, or the server rejected it before applying it (4xx) */
  NOT_APPLIED,
  /** Failed after the request may have been applied, e.g. a timeout, dropped connection or 5xx */
  UNKNOWN
}

// The body is only read after a success status, so this error means the server handled the request
internal const val RESPONSE_BODY_READ_FAILED = "Failed to read response"

/** Failures that happen before any request data is sent, so the server never saw the request */
internal fun IOException.isBeforeRequestSent() =
        this is ConnectException ||
                this is UnknownHostException ||
                this is NoRouteToHostException ||
                this is SSLHandshakeException

/**
 * Whether an error status means the server rejected the request without changing anything. 4xx
 * (auth, not found, bad request) are returned before a sync is applied. A 5xx can come after the
 * update was saved, e.g. from a proxy timing out, so it does not prove the sync was not applied.
 */
internal fun isRejectedBeforeApplying(statusCode: Int) = statusCode in 400..499

/**
 * Outcome of a sync request from its error result. Sync responses carry no data the app needs, so
 * a success status whose body could not be read still means the server applied the sync.
 */
internal fun syncOutcomeOf(error: String?, notApplied: Boolean): SyncOutcome =
        when {
          error.isNullOrEmpty() || error == RESPONSE_BODY_READ_FAILED -> SyncOutcome.APPLIED
          notApplied -> SyncOutcome.NOT_APPLIED
          else -> SyncOutcome.UNKNOWN
        }

/**
 * Cancels a request, including a retry made after a token refresh. Once cancelled, no further call
 * is started for the request, so a request abandoned by its caller cannot reach the server later.
 */
class RequestCancellation {
  private var cancelled = false
  private var call: Call? = null

  /** Tracks the call about to be enqueued. Returns false if the request was already cancelled. */
  @Synchronized
  fun track(newCall: Call): Boolean {
    if (cancelled) return false
    call = newCall
    return true
  }

  @Synchronized
  fun cancel() {
    cancelled = true
    call?.cancel()
  }
}

class ApiHandler(var ctx:Context) {
  val tag = "ApiHandler"

  companion object {
    // For sending data back to the Webview frontend
    lateinit var absDatabaseNotifyListeners:(String, JSObject) -> Unit

    fun checkAbsDatabaseNotifyListenersInitted():Boolean {
      return ::absDatabaseNotifyListeners.isInitialized
    }

    // Set on error results when the request certainly did not change anything on the server
    private const val NOT_APPLIED_KEY = "notApplied"

    // Callbacks waiting on the token refresh in flight, per server connection config id.
    // Shared because several ApiHandler instances exist (player, plugins, downloads).
    private val inFlightRefreshes = HashMap<String, MutableList<(RefreshResult) -> Unit>>()
  }

  private var defaultClient = OkHttpClient()
  private var pingClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())
  private var secureStorage = SecureStorage(ctx)

  @JsonIgnoreProperties(ignoreUnknown = true)
  data class LocalSessionSyncResult(val id:String, val success:Boolean, val progressSynced:Boolean?, val error:String?)
  data class LocalSessionsSyncResponsePayload(val results:List<LocalSessionSyncResult>)

  private fun getRequest(endpoint:String, httpClient:OkHttpClient?, config:ServerConnectionConfig?, cb: (JSObject) -> Unit) {
    val address = config?.address ?: DeviceManager.serverAddress
    val token = config?.token ?: DeviceManager.token

    try {
      val request = Request.Builder()
        .url("${address}$endpoint").addHeader("Authorization", "Bearer $token")
        .build()
      makeRequest(request, httpClient, cb)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun postRequest(endpoint:String, payload: JSObject?, config:ServerConnectionConfig?, cancellation: RequestCancellation? = null, cb: (JSObject) -> Unit) {
    val address = config?.address ?: DeviceManager.serverAddress
    val token = config?.token ?: DeviceManager.token
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload?.toString()?.toRequestBody(mediaType) ?: EMPTY_REQUEST
    val requestUrl = "${address}$endpoint"
    Log.d(tag, "postRequest to $requestUrl")
    try {
      val request = Request.Builder().post(requestBody)
        .url(requestUrl).addHeader("Authorization", "Bearer ${token}")
        .build()
      makeRequest(request, null, cb, cancellation)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun patchRequest(endpoint:String, payload: JSObject, cb: (JSObject) -> Unit) {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload.toString().toRequestBody(mediaType)
    try {
      val request = Request.Builder().patch(requestBody)
        .url("${DeviceManager.serverAddress}$endpoint").addHeader("Authorization", "Bearer ${DeviceManager.token}")
        .build()
      makeRequest(request, null, cb)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun makeRequest(request:Request, httpClient:OkHttpClient?, cb: (JSObject) -> Unit, cancellation: RequestCancellation? = null) {
    val client = httpClient ?: defaultClient

    val call = client.newCall(request)
    if (cancellation?.track(call) == false) {
      cb(JSObject().put("error", "Request cancelled").put(NOT_APPLIED_KEY, true))
      return
    }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.d(tag, "FAILURE TO CONNECT")
        e.printStackTrace()

        val jsobj = JSObject()
        jsobj.put("error", "Failed to connect")
        if (e.isBeforeRequestSent()) jsobj.put(NOT_APPLIED_KEY, true)
        cb(jsobj)
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (it.code == 401) {
            // Handle 401 Unauthorized by attempting token refresh
            AbsLogger.info(tag, "makeRequest: 401 Unauthorized for request to \"${request.url}\" - attempt token refresh")
            handleTokenRefresh(request, httpClient, cb, cancellation)
            return
          }

          if (!it.isSuccessful) {
            val jsobj = JSObject()
            jsobj.put("error", "Unexpected code $response")
            if (isRejectedBeforeApplying(it.code)) jsobj.put(NOT_APPLIED_KEY, true)
            cb(jsobj)
            return
          }

          // Reading the body can still fail (e.g. call timeout mid-body). An exception here would be
          // swallowed by OkHttp and cb never called, leaving callers waiting forever.
          val bodyString = try {
            it.body!!.string()
          } catch (e: IOException) {
            AbsLogger.error(tag, "makeRequest: Failed to read response from \"${request.url.encodedPath}\" (${e.message})")
            // Headers arrived but the body stalled: the pooled connection is likely half dead and
            // would stall the following requests too, so make them open a new connection.
            // Close first: only idle connections are evicted.
            it.close()
            client.connectionPool.evictAll()
            cb(JSObject().put("error", RESPONSE_BODY_READ_FAILED))
            return
          }
          if (bodyString == "OK") {
            cb(JSObject())
          } else {
            try {
              var jsonObj = JSObject()
              if (bodyString.startsWith("[")) {
                val array = JSArray(bodyString)
                jsonObj.put("value", array)
              } else {
                jsonObj = JSObject(bodyString)
              }
              cb(jsonObj)
            } catch(je:JSONException) {
              Log.e(tag, "Invalid JSON response ${je.localizedMessage} from body $bodyString")
              val jsobj = JSObject()
              jsobj.put("error", "Invalid response body")
              cb(jsobj)
            }
          }
        }
      }
    })
  }

  /**
   * Handles token refresh when a 401 Unauthorized response is received
   * This function will:
   * 1. Get the refresh token from secure storage for the current server connection
   * 2. Make a request to /auth/refresh endpoint with the refresh token
   * 3. Update the stored tokens with the new access token
   * 4. Retry the original request with the new access token
   * 5. If refresh fails, fail the request ([refreshAuthTokens] owns clearing the session)
   *
   * @param originalRequest The original request that failed with 401
   * @param httpClient The HTTP client to use for the request
   * @param callback The callback to return the response
   */
  private fun handleTokenRefresh(originalRequest: Request, httpClient: OkHttpClient?, callback: (JSObject) -> Unit, cancellation: RequestCancellation? = null) {
    val serverConnectionConfigId = DeviceManager.serverConnectionConfigId

    // Another request may have refreshed the token while this one was in flight. Retry with the
    // new token instead of refreshing again with a refresh token that may already be rotated.
    val currentToken = DeviceManager.getServerConnectionConfig(serverConnectionConfigId)?.token
    val sentToken = originalRequest.header("Authorization")?.removePrefix("Bearer ")
    if (!currentToken.isNullOrEmpty() && sentToken != null && sentToken != currentToken) {
      AbsLogger.info(tag, "handleTokenRefresh: Token already refreshed, retrying request to \"${originalRequest.url}\"")
      retryOriginalRequest(originalRequest, currentToken, httpClient, callback, cancellation)
      return
    }

    refreshAuthTokens(serverConnectionConfigId, httpClient) { result ->
      if (result is RefreshResult.Success) {
        retryOriginalRequest(originalRequest, result.accessToken, httpClient, callback, cancellation)
      } else {
        callback(JSObject().put("error", "Authentication failed - login again").put(NOT_APPLIED_KEY, true))
      }
    }
  }

  sealed interface RefreshResult {
    data class Success(val accessToken: String) : RefreshResult

    /** The server rejected the refresh token, so the session has already been cleared. */
    data object Rejected : RefreshResult

    /** The refresh could not be completed. Credentials are untouched and the caller may retry. */
    data object Transient : RefreshResult
  }

  /**
   * Refreshes tokens for a specific saved server, including downloads queued while another server is active.
   *
   * Concurrent calls for the same server share one refresh request. The server rotates the refresh
   * token, so a second parallel refresh with the old token would be rejected and log the user out.
   */
  fun refreshAuthTokens(
          serverConnectionConfigId: String,
          httpClient: OkHttpClient? = null,
          onResult: (RefreshResult) -> Unit
  ) {
    synchronized(inFlightRefreshes) {
      val waiting = inFlightRefreshes[serverConnectionConfigId]
      if (waiting != null) {
        AbsLogger.info(tag, "refreshAuthTokens: Refresh already in progress for $serverConnectionConfigId, waiting for it")
        waiting.add(onResult)
        return
      }
      inFlightRefreshes[serverConnectionConfigId] = mutableListOf(onResult)
    }

    requestTokenRefresh(serverConnectionConfigId, httpClient) { result ->
      val waiting = synchronized(inFlightRefreshes) { inFlightRefreshes.remove(serverConnectionConfigId) }
      waiting?.forEach { it(result) }
    }
  }

  private fun requestTokenRefresh(
          serverConnectionConfigId: String,
          httpClient: OkHttpClient?,
          onResult: (RefreshResult) -> Unit
  ) {
    val config = DeviceManager.getServerConnectionConfig(serverConnectionConfigId)
    val refreshToken = secureStorage.getRefreshToken(serverConnectionConfigId)
    if (config == null || refreshToken.isNullOrEmpty()) {
      AbsLogger.error(tag, "No refresh token or server configuration for $serverConnectionConfigId")
      handleRefreshRejected(serverConnectionConfigId)
      onResult(RefreshResult.Rejected)
      return
    }
    val request = try {
      Request.Builder()
              .url("${config.address}/auth/refresh")
              .addHeader("x-refresh-token", refreshToken)
              .addHeader("Content-Type", "application/json")
              .post(EMPTY_REQUEST)
              .build()
    } catch (e: Exception) {
      AbsLogger.error(tag, "Could not create refresh request for ${config.name}: ${e.message}")
      onResult(RefreshResult.Transient)
      return
    }
    (httpClient ?: defaultClient).newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        AbsLogger.error(tag, "Token refresh failed for ${config.name}: ${e.message}")
        onResult(RefreshResult.Transient)
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!it.isSuccessful) {
            AbsLogger.error(tag, "Token refresh returned ${it.code} for ${config.name}")
            if (it.code != 401 && it.code != 403) {
              onResult(RefreshResult.Transient)
              return
            }
            handleRefreshRejected(serverConnectionConfigId)
            onResult(RefreshResult.Rejected)
            return
          }
          try {
            val user = JSONObject(it.body!!.string()).optJSONObject("user")
            val accessToken = user?.optString("accessToken").orEmpty()
            if (accessToken.isEmpty()) {
              AbsLogger.error(tag, "Refresh response had no access token for ${config.name}")
              onResult(RefreshResult.Transient)
              return
            }
            updateTokens(accessToken, user?.optString("refreshToken").orEmpty().ifEmpty { refreshToken }, serverConnectionConfigId)
            onResult(RefreshResult.Success(accessToken))
          } catch (e: Exception) {
            AbsLogger.error(tag, "Could not parse refresh response for ${config.name}: ${e.message}")
            onResult(RefreshResult.Transient)
          }
        }
      }
    })
  }

  /**
   * Clears only the server that rejected the refresh token; queued downloads can target a non-active server.
   *
   * Only call this when the server explicitly rejected the refresh token. Transient failures should not log the user out
   */
  private fun handleRefreshRejected(serverConnectionConfigId: String) {
    // Must not throw: callers still have to report the refresh result to an in-flight request or download.
    try {
      secureStorage.removeRefreshToken(serverConnectionConfigId)
      if (DeviceManager.serverConnectionConfigId != serverConnectionConfigId) return
      DeviceManager.serverConnectionConfig = null
      DeviceManager.deviceData.lastServerConnectionConfigId = null
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      if (checkAbsDatabaseNotifyListenersInitted()) {
        absDatabaseNotifyListeners(
                "onTokenRefreshFailure",
                JSObject().put("error", "Token refresh failed").put("serverConnectionConfigId", serverConnectionConfigId))
      }
    } catch (e: Exception) {
      AbsLogger.error(tag, "Could not clear session for $serverConnectionConfigId: ${e.message}")
    }
  }

  /**
   * Updates the stored tokens with new access and refresh tokens
   *
   * @param newAccessToken The new access token
   * @param newRefreshToken The new refresh token (or existing one if not provided)
   */
  private fun updateTokens(newAccessToken: String, newRefreshToken: String, serverConnectionConfigId: String) {
    try {
      // Update the refresh token in secure storage if it's new
      if (newRefreshToken != secureStorage.getRefreshToken(serverConnectionConfigId)) {
        secureStorage.storeRefreshToken(serverConnectionConfigId, newRefreshToken)
        Log.d(tag, "updateTokens: Updated refresh token in secure storage")
      }

      // The refreshed connection may be queued in the downloader rather than currently active.
      DeviceManager.getServerConnectionConfig(serverConnectionConfigId)?.let { config ->
        config.token = newAccessToken
        DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
        Log.d(tag, "updateTokens: Updated access token in server connection config")
      }

      // Send access token to Webview frontend
      if (DeviceManager.serverConnectionConfigId == serverConnectionConfigId && checkAbsDatabaseNotifyListenersInitted()) {
        val tokenJsObject = JSObject()
        tokenJsObject.put("accessToken", newAccessToken)
        absDatabaseNotifyListeners("onTokenRefresh", tokenJsObject)
      } else {
        // Can happen if Webview is never run
        Log.i(tag, "AbsDatabaseNotifyListeners is not initialized so cannot send new access token")
      }
      AbsLogger.info(tag, "updateTokens: Successfully refreshed auth tokens for server ${DeviceManager.serverConnectionConfigString}")
    } catch (e: Exception) {
      Log.e(tag, "updateTokens: Failed to update tokens", e)
      AbsLogger.error(tag, "updateTokens: Failed to refresh auth tokens for server ${DeviceManager.serverConnectionConfigString} (error: ${e.message})")
    }
  }

  /**
   * Retries the original request with the new access token
   *
   * @param originalRequest The original request to retry
   * @param newAccessToken The new access token to use
   * @param httpClient The HTTP client to use
   * @param callback The callback to return the response
   */
  private fun retryOriginalRequest(originalRequest: Request, newAccessToken: String, httpClient: OkHttpClient?, callback: (JSObject) -> Unit, cancellation: RequestCancellation? = null) {
    try {
      // Create a new request with the updated authorization header
      val newRequest = originalRequest.newBuilder()
        .removeHeader("Authorization")
        .addHeader("Authorization", "Bearer $newAccessToken")
        .build()

      Log.d(tag, "retryOriginalRequest: Retrying request to ${newRequest.url}")

      // Make the retry request
      val client = httpClient ?: defaultClient
      val call = client.newCall(newRequest)
      if (cancellation?.track(call) == false) {
        callback(JSObject().put("error", "Request cancelled").put(NOT_APPLIED_KEY, true))
        return
      }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          Log.e(tag, "retryOriginalRequest: Failed to retry request", e)
          AbsLogger.error(tag, "retryOriginalRequest: Failed to retry request after token refresh for server ${DeviceManager.serverConnectionConfigString} (error: ${e.message})")
          val errorObj = JSObject()
          errorObj.put("error", "Failed to retry request after token refresh")
          if (e.isBeforeRequestSent()) errorObj.put(NOT_APPLIED_KEY, true)
          callback(errorObj)
        }

        override fun onResponse(call: Call, response: Response) {
          response.use {
            if (!it.isSuccessful) {
              Log.e(tag, "retryOriginalRequest: Retry request failed with status ${it.code}")
              AbsLogger.error(tag, "retryOriginalRequest: Retry request failed with status ${it.code} for server ${DeviceManager.serverConnectionConfigString}")
              val errorObj = JSObject()
              errorObj.put("error", "Retry request failed with status ${it.code}")
              if (isRejectedBeforeApplying(it.code)) errorObj.put(NOT_APPLIED_KEY, true)
              callback(errorObj)
              return
            }

            val bodyString = try {
              it.body!!.string()
            } catch (e: IOException) {
              AbsLogger.error(tag, "retryOriginalRequest: Failed to read response (${e.message})")
              it.close()
              client.connectionPool.evictAll()
              callback(JSObject().put("error", RESPONSE_BODY_READ_FAILED))
              return
            }
            if (bodyString == "OK") {
              callback(JSObject())
            } else {
              try {
                var jsonObj = JSObject()
                if (bodyString.startsWith("[")) {
                  val array = JSArray(bodyString)
                  jsonObj.put("value", array)
                } else {
                  jsonObj = JSObject(bodyString)
                }
                callback(jsonObj)
              } catch(je:JSONException) {
                Log.e(tag, "retryOriginalRequest: Invalid JSON response ${je.localizedMessage} from body $bodyString")
                val errorObj = JSObject()
                errorObj.put("error", "Invalid response body")
                callback(errorObj)
              }
            }
          }
        }
      })

    } catch (e: Exception) {
      Log.e(tag, "retryOriginalRequest: Unexpected error during retry", e)
      AbsLogger.error(tag, "retryOriginalRequest: Unexpected error during retry for server ${DeviceManager.serverConnectionConfigString}")
      val errorObj = JSObject()
      errorObj.put("error", "Failed to retry request")
      callback(errorObj)
    }
  }

  fun getCurrentUser(cb: (User?) -> Unit) {
    getRequest("/api/me", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getCurrentUser Failed")
        cb(null)
      } else {
        val user = jacksonMapper.readValue<User>(it.toString())
        cb(user)
      }
    }
  }

  fun getLibraries(cb: (List<Library>) -> Unit) {
    val mapper = jacksonMapper
    getRequest("/api/libraries?include=stats", null,null) {
      val libraries = mutableListOf<Library>()

      var array = JSONArray()
      if (it.has("libraries")) { // TODO: Server 2.2.9 changed to this
        array = it.getJSONArray("libraries")
      } else if (it.has("value")) {
        array = it.getJSONArray("value")
      }

      for (i in 0 until array.length()) {
        libraries.add(mapper.readValue(array.get(i).toString()))
      }
      cb(libraries)
    }
  }

  fun getLibraryPersonalized(libraryItemId:String, cb: (List<LibraryShelfType>?) -> Unit) {
    getRequest("/api/libraries/$libraryItemId/personalized", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryStats Failed")
        cb(null)
      } else {
        val items = mutableListOf<LibraryShelfType>()
        val array = it.getJSONArray("value")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryShelfType>(array.get(i).toString())
          items.add(item)
        }
        cb(items)
      }
    }
  }

  fun getLibraryItem(libraryItemId:String, cb: (LibraryItem?) -> Unit) {
    getRequest("/api/items/$libraryItemId?expanded=1", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryItem Failed")
        cb(null)
      } else {
        val libraryItem = jacksonMapper.readValue<LibraryItem>(it.toString())
        cb(libraryItem)
      }
    }
  }

  fun getLibraryItemWithProgress(libraryItemId:String, episodeId:String?, cb: (LibraryItem?) -> Unit) {
    var requestUrl = "/api/items/$libraryItemId?expanded=1&include=progress"
    if (!episodeId.isNullOrEmpty()) requestUrl += "&episode=$episodeId"
    getRequest(requestUrl, null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryItemWithProgress Failed")
        cb(null)
      } else {
        val libraryItem = jacksonMapper.readValue<LibraryItem>(it.toString())
        cb(libraryItem)
      }
    }
  }

  fun getLibraryItems(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    getRequest("/api/libraries/$libraryId/items?limit=100&minified=1", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibrarySeries(libraryId:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    Log.d(tag, "Getting series")
    getRequest("/api/libraries/$libraryId/series?minified=1&sort=name&limit=10000", null, null) {
      val items = mutableListOf<LibrarySeriesItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibrarySeriesItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibrarySeriesItems(libraryId:String, seriesId:String, cb: (List<LibraryItem>) -> Unit) {
    Log.d(tag, "Getting items for series")
    val seriesIdBase64 = Base64.encodeToString(seriesId.toByteArray(), Base64.DEFAULT)
    getRequest("/api/libraries/$libraryId/items?minified=1&sort=media.metadata.title&filter=series.${seriesIdBase64}&limit=1000", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibraryAuthors(libraryId:String, cb: (List<LibraryAuthorItem>) -> Unit) {
    Log.d(tag, "Getting series")
    getRequest("/api/libraries/$libraryId/authors", null, null) {
      val items = mutableListOf<LibraryAuthorItem>()
      if (it.has("authors")) {
        val array = it.getJSONArray("authors")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryAuthorItem>(array.get(i).toString())
          items.add(item)
        }
      }else{
        Log.e(tag, "No results")
      }
      cb(items)
    }
  }

  fun getLibraryItemsFromAuthor(libraryId:String, authorId:String, cb: (List<LibraryItem>) -> Unit) {
    Log.d(tag, "Getting author items")
    val authorIdBase64 = Base64.encodeToString(authorId.toByteArray(), Base64.DEFAULT)
    getRequest("/api/libraries/$libraryId/items?limit=1000&minified=1&filter=authors.${authorIdBase64}&sort=media.metadata.title&collapseseries=1", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          if (item.collapsedSeries != null) {
            item.collapsedSeries?.libraryId = libraryId
          }
          items.add(item)
        }
      }else{
        Log.e(tag, "No results")
      }
      cb(items)
    }
  }

  fun getLibraryCollections(libraryId:String, cb: (List<LibraryCollection>) -> Unit) {
    Log.d(tag, "Getting collections")
    getRequest("/api/libraries/$libraryId/collections?minified=1&sort=name&limit=1000", null, null) {
      val items = mutableListOf<LibraryCollection>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryCollection>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getSearchResults(libraryId:String, queryString:String, cb: (LibraryItemSearchResultType?) -> Unit) {
    Log.d(tag, "Doing search for library $libraryId")
    getRequest("/api/libraries/$libraryId/search?q=$queryString", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getSearchResults Failed")
        cb(null)
      } else {
        val librarySearchResults = jacksonMapper.readValue<LibraryItemSearchResultType>(it.toString())
        cb(librarySearchResults)
      }
    }
  }

  fun getAllItemsInProgress(cb: (List<ItemInProgress>) -> Unit) {
    getRequest("/api/me/items-in-progress", null, null) {
      val items = mutableListOf<ItemInProgress>()
      if (it.has("libraryItems")) {
        val array = it.getJSONArray("libraryItems")
        for (i in 0 until array.length()) {
          val jsobj = array.get(i) as JSONObject
          val itemInProgress = ItemInProgress.makeFromServerObject(jsobj, jacksonMapper)
          items.add(itemInProgress)
        }
      }
      cb(items)
    }
  }

  fun playLibraryItem(libraryItemId:String, episodeId:String?, playItemRequestPayload:PlayItemRequestPayload, cb: (PlaybackSession?) -> Unit) {
    val payload = JSObject(jacksonMapper.writeValueAsString(playItemRequestPayload))

    val endpoint = if (episodeId.isNullOrEmpty()) "/api/items/$libraryItemId/play" else "/api/items/$libraryItemId/play/$episodeId"
    postRequest(endpoint, payload, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "Play Library Item Failed")
        cb(null)
      } else {
        it.put("serverConnectionConfigId", DeviceManager.serverConnectionConfig?.id)
        it.put("serverAddress", DeviceManager.serverAddress)
        val playbackSession = jacksonMapper.readValue<PlaybackSession>(it.toString())
        cb(playbackSession)
      }
    }
  }

  fun sendProgressSync(sessionId:String, syncData: MediaProgressSyncData, cancellation: RequestCancellation? = null, cb: (SyncOutcome, String?) -> Unit) {
    val payload = JSObject(jacksonMapper.writeValueAsString(syncData))

    postRequest("/api/session/$sessionId/sync", payload, null, cancellation) {
      reportSyncResult(it, cb)
    }
  }

  private fun createPartialPlaybackSession(playbackSession: PlaybackSession): ObjectNode {
    val json = jacksonMapper.createObjectNode()
    json.put("id", playbackSession.id)
    json.put("userId", playbackSession.userId)
    json.put("libraryItemId", playbackSession.libraryItemId)
    json.put("episodeId", playbackSession.episodeId)
    json.put("mediaType", playbackSession.mediaType)
    json.put("displayTitle", playbackSession.displayTitle)
    json.put("displayAuthor", playbackSession.displayAuthor)
    json.put("duration", playbackSession.duration)
    json.put("playMethod", playbackSession.playMethod)
    json.put("startedAt", playbackSession.startedAt)
    json.put("updatedAt", playbackSession.updatedAt)
    json.put("timeListening", playbackSession.timeListening)
    json.put("currentTime", playbackSession.currentTime)
    json.put("mediaPlayer", playbackSession.mediaPlayer)
    return json
  }

  fun sendLocalProgressSync(playbackSession:PlaybackSession, cancellation: RequestCancellation? = null, cb: (SyncOutcome, String?) -> Unit) {
    val partialSession = createPartialPlaybackSession(playbackSession)
    partialSession.set<ObjectNode>("deviceInfo", jacksonMapper.valueToTree(playbackSession.deviceInfo))
    postRequest("/api/session/local", JSObject(partialSession.toString()), null, cancellation) {
      reportSyncResult(it, cb)
    }
  }

  private fun reportSyncResult(response: JSObject, cb: (SyncOutcome, String?) -> Unit) {
    val error = response.getString("error")
    val outcome = syncOutcomeOf(error, response.optBoolean(NOT_APPLIED_KEY))
    cb(outcome, if (outcome == SyncOutcome.APPLIED) null else error)
  }

  fun updateMediaProgress(libraryItemId:String,episodeId:String?,updatePayload:JSObject, cb: () -> Unit) {
    Log.d(tag, "updateMediaProgress $libraryItemId $episodeId $updatePayload")
    val endpoint = if(episodeId.isNullOrEmpty()) "/api/me/progress/$libraryItemId" else "/api/me/progress/$libraryItemId/$episodeId"
    patchRequest(endpoint,updatePayload) {
      Log.d(tag, "updateMediaProgress patched progress")
      cb()
    }
  }

  fun getMediaProgress(libraryItemId:String, episodeId:String?, serverConnectionConfig:ServerConnectionConfig?, cb: (MediaProgress?) -> Unit) {
    val endpoint = if(episodeId.isNullOrEmpty()) "/api/me/progress/$libraryItemId" else "/api/me/progress/$libraryItemId/$episodeId"

    // TODO: Using ping client here allows for shorter timeout (3 seconds), maybe rename or make diff client for requests requiring quicker response
    getRequest(endpoint, pingClient, serverConnectionConfig) {
      if (it.has("error")) {
        Log.e(tag, "getMediaProgress: Failed to get progress")
        cb(null)
      } else {
        // Callers may be waiting on this to start playback, so a parse error must still call back
        val progress = try {
          jacksonMapper.readValue<MediaProgress>(it.toString())
        } catch (e: Exception) {
          AbsLogger.error(tag, "getMediaProgress: Invalid progress response (${e.message})")
          null
        }
        cb(progress)
      }
    }
  }

  fun getPlaybackSession(playbackSessionId:String, cb: (PlaybackSession?) -> Unit) {
    Log.d(tag, "getPlaybackSession for $playbackSessionId for server ${DeviceManager.serverAddress}")
    val endpoint = "/api/session/$playbackSessionId"
    getRequest(endpoint, null, null) {
      val err = it.getString("error")
      if (!err.isNullOrEmpty()) {
        cb(null)
      } else {
        cb(jacksonMapper.readValue<PlaybackSession>(it.toString()))
      }
    }
  }

  fun pingServer(config:ServerConnectionConfig, cb: (Boolean) -> Unit) {
    Log.d(tag, "pingServer: Pinging ${config.address}")
    getRequest("/ping", pingClient, config) {
      val success = it.getString("success")
      if (success.isNullOrEmpty()) {
        Log.d(tag, "pingServer: Ping ${config.address} Failed")
        cb(false)
      } else {
        Log.d(tag, "pingServer: Ping ${config.address} Successful")
        cb(true)
      }
    }
  }

  fun closePlaybackSession(playbackSessionId:String, config:ServerConnectionConfig?, cb: (Boolean) -> Unit) {
    Log.d(tag, "closePlaybackSession: playbackSessionId=$playbackSessionId")
    postRequest("/api/session/$playbackSessionId/close", null, config) {
      cb(true)
    }
  }

  fun authorize(config:ServerConnectionConfig, cb: (MutableList<MediaProgress>?) -> Unit) {
    Log.d(tag, "authorize: Authorizing ${config.address}")
    postRequest("/api/authorize", JSObject(), config) {
      val error = it.getString("error")
      if (!error.isNullOrEmpty()) {
        Log.d(tag, "authorize: Authorize ${config.address} Failed: $error")
        cb(null)
      } else {
        val mediaProgressList:MutableList<MediaProgress> = mutableListOf()
        val user = it.getJSObject("user")
        val mediaProgress = user?.getJSONArray("mediaProgress") ?: JSONArray()
        for (i in 0 until mediaProgress.length()) {
          val mediaProg = jacksonMapper.readValue<MediaProgress>(mediaProgress.getJSONObject(i).toString())
          mediaProgressList.add(mediaProg)
        }
        Log.d(tag, "authorize: Authorize ${config.address} Successful")
        cb(mediaProgressList)
      }
    }
  }

  fun sendSyncLocalSessions(playbackSessions:List<PlaybackSession>, cb: (Boolean, String?) -> Unit) {
    @SuppressLint("HardwareIds")
    val deviceId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
    val deviceInfo = DeviceInfo(deviceId, Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME)

    val json = jacksonMapper.createObjectNode();
    json.putArray("sessions").addAll(playbackSessions.map(::createPartialPlaybackSession))
    json.set<ObjectNode>("deviceInfo", jacksonMapper.valueToTree(deviceInfo))
    AbsLogger.info("ApiHandler", "sendSyncLocalSessions: Sending ${playbackSessions.size} saved local playback sessions to server (${DeviceManager.serverConnectionConfigName})")

    postRequest("/api/session/local-all", JSObject(json.toString()), null) {
      if (!it.getString("error").isNullOrEmpty()) {
        AbsLogger.error("ApiHandler", "sendSyncLocalSessions: Failed to sync local sessions. (${it.getString("error")})")
        cb(false, it.getString("error"))
      } else {
        val response = jacksonMapper.readValue<LocalSessionsSyncResponsePayload>(it.toString())
        response.results.forEach { localSessionSyncResult ->
          Log.d(tag, "Synced session result ${localSessionSyncResult.id}|${localSessionSyncResult.progressSynced}|${localSessionSyncResult.success}")

          playbackSessions.find { ps -> ps.id == localSessionSyncResult.id }?.let { session ->
            if (localSessionSyncResult.progressSynced == true) {
              val syncResult = SyncResult(true, true, "Progress synced on server")
              MediaEventManager.saveEvent(session, syncResult)

              AbsLogger.info("ApiHandler", "sendSyncLocalSessions: Synced session \"${session.displayTitle}\" with server, server progress was updated for item ${session.mediaItemId}")
            } else if (!localSessionSyncResult.success) {
              AbsLogger.error("ApiHandler", "sendSyncLocalSessions: Failed to sync session \"${session.displayTitle}\" with server. Error: ${localSessionSyncResult.error}")
            } else {
              AbsLogger.info("ApiHandler", "sendSyncLocalSessions: Synced session \"${session.displayTitle}\" with server. Server progress was up-to-date for item ${session.mediaItemId}")
            }
          }
        }
        cb(true, null)
      }
    }
  }

  fun syncLocalMediaProgressForUser(cb: () -> Unit) {
    AbsLogger.info("ApiHandler", "[ApiHandler] syncLocalMediaProgressForUser: Server connection ${DeviceManager.serverConnectionConfigName}")

    // Get all local media progress for this server
    val allLocalMediaProgress = DeviceManager.dbManager.getAllLocalMediaProgress().filter { it.serverConnectionConfigId == DeviceManager.serverConnectionConfigId }
    if (allLocalMediaProgress.isEmpty()) {
      AbsLogger.info("ApiHandler", "[ApiHandler] syncLocalMediaProgressForUser: No local media progress to sync")
      return cb()
    }

    AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Found ${allLocalMediaProgress.size} local media progress")

    getCurrentUser { user ->
      if (user == null) {
        AbsLogger.error("ApiHandler", "syncLocalMediaProgressForUser: Failed to load user from server (${DeviceManager.serverConnectionConfigName})")
      } else {
        var numLocalMediaProgressUptToDate = 0
        var numLocalMediaProgressUpdated = 0

        // Compare server user progress with local progress
        user.mediaProgress.forEach { mediaProgress ->
          // Get matching local media progress
          allLocalMediaProgress.find { it.isMatch(mediaProgress) }?.let { localMediaProgress ->
            if (mediaProgress.lastUpdate > localMediaProgress.lastUpdate) {
              val updateLogs = mutableListOf<String>()
              if (mediaProgress.progress != localMediaProgress.progress) {
                updateLogs.add("Updated progress from ${localMediaProgress.progress} to ${mediaProgress.progress}")
              }
              if (mediaProgress.currentTime != localMediaProgress.currentTime) {
                updateLogs.add("Updated currentTime from ${localMediaProgress.currentTime} to ${mediaProgress.currentTime}")
              }
              if (mediaProgress.isFinished != localMediaProgress.isFinished) {
                updateLogs.add("Updated isFinished from ${localMediaProgress.isFinished} to ${mediaProgress.isFinished}")
              }
              if (mediaProgress.ebookProgress != localMediaProgress.ebookProgress) {
                updateLogs.add("Updated ebookProgress from ${localMediaProgress.isFinished} to ${mediaProgress.isFinished}")
              }
              if (updateLogs.isNotEmpty()) {
                AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Server progress for item \"${mediaProgress.mediaItemId}\" is more recent than local (server lastUpdate=${mediaProgress.lastUpdate}, local lastUpdate=${localMediaProgress.lastUpdate}). ${updateLogs.joinToString()}")
              }

              localMediaProgress.updateFromServerMediaProgress(mediaProgress)

              // Only report sync if progress changed
              if (updateLogs.isNotEmpty()) {
                MediaEventManager.syncEvent(mediaProgress, "Sync on server connection")
              }
              DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)
              numLocalMediaProgressUpdated++
            } else if (localMediaProgress.lastUpdate > mediaProgress.lastUpdate && localMediaProgress.ebookLocation != null && localMediaProgress.ebookLocation != mediaProgress.ebookLocation) {
              // Patch ebook progress to server
              AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Local progress for ebook item \"${mediaProgress.mediaItemId}\" is more recent than server progress. Local progress last updated ${localMediaProgress.lastUpdate}, server progress last updated ${mediaProgress.lastUpdate}. Sending server request to update ebook progress from ${mediaProgress.ebookProgress} to ${localMediaProgress.ebookProgress}")
              val endpoint = "/api/me/progress/${localMediaProgress.libraryItemId}"
              val updatePayload = JSObject()
              updatePayload.put("ebookLocation", localMediaProgress.ebookLocation)
              updatePayload.put("ebookProgress", localMediaProgress.ebookProgress)
              updatePayload.put("lastUpdate", localMediaProgress.lastUpdate)
              patchRequest(endpoint,updatePayload) {
                AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Successfully updated server ebook progress for item item \"${mediaProgress.mediaItemId}\"")
              }
            } else {
              numLocalMediaProgressUptToDate++
            }
          }
        }

        AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Finishing syncing local media progress with server. $numLocalMediaProgressUptToDate up-to-date, $numLocalMediaProgressUpdated updated")
      }
      cb()
    }
  }
}
