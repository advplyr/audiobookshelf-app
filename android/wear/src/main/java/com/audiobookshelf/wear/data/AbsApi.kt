package com.audiobookshelf.wear.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.audiobookshelf.wear.sync.PhoneLink
import com.audiobookshelf.wear.sync.PhoneRelayInterceptor
import com.audiobookshelf.wear.sync.PhoneSignedOutException
import com.audiobookshelf.wear.sync.ServerRoute
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * Talks to the Audiobookshelf server, directly from the watch (Wi-Fi, LTE, Bluetooth) or through
 * the phone app when the watch can't reach it.
 */
class AbsApi(private val auth: AuthStore, private val phoneLink: PhoneLink, route: ServerRoute) {
  private val json = "application/json; charset=utf-8".toMediaType()

  /** No credentials, but goes through the phone when needed. Used to sign in and refresh tokens. */
  private val baseClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .addInterceptor(PhoneRelayInterceptor(route, phoneLink))
    .build()

  /** Shared client that adds auth headers and renews expired access tokens. Also used by the player and downloader. */
  val client: OkHttpClient = baseClient.newBuilder()
    .apply { interceptors().add(0, AuthInterceptor()) }
    .build()

  private val shortClient = client.newBuilder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()

  private val baseUrl get() = auth.current?.serverAddress ?: throw IOException("Not signed in")

  fun absoluteUrl(path: String) = "$baseUrl$path"

  fun coverUrl(itemId: String, width: Int = 300) = "$baseUrl/api/items/$itemId/cover?width=$width&format=jpeg"

  suspend fun login(address: String, username: String, password: String, customHeaders: Map<String, String>): Account {
    val serverAddress = normalizeAddress(address)
    val body = JSONObject().put("username", username).put("password", password)
    val request = Request.Builder()
      .url("$serverAddress/login")
      .header("x-return-tokens", "true")
      .apply { customHeaders.forEach { (k, v) -> header(k, v) } }
      .post(body.toString().toRequestBody(json))
      .build()
    val response = execute(baseClient, request)
    val user = response.getJSONObject("user")
    return Account(
      serverAddress = serverAddress,
      username = user.optString("username", username),
      userId = user.optString("id"),
      // Servers before v2.26 only return the legacy non-expiring token
      accessToken = user.optStringOrNull("accessToken") ?: user.getString("token"),
      refreshToken = user.optStringOrNull("refreshToken"),
      customHeaders = customHeaders,
      serverVersion = response.optJSONObject("serverSettings")?.optStringOrNull("version")
    )
  }

  /**
   * Book libraries that contain audio, and podcast libraries. Ebook-only libraries have nothing to
   * play on the watch.
   */
  suspend fun audioLibraries(): List<Library> {
    val response = get("/api/libraries?include=stats")
    return response.getJSONArray("libraries").objects()
      .filter {
        when (it.optString("mediaType")) {
          MEDIA_BOOK -> (it.optJSONObject("stats")?.optInt("numAudioFiles", 1) ?: 1) > 0
          MEDIA_PODCAST -> true
          else -> false
        }
      }
      .map { Library(it.getString("id"), it.optString("name"), it.optString("mediaType")) }
  }

  /** Books, or podcasts in a podcast library. */
  suspend fun libraryItems(libraryId: String, page: Int, limit: Int = 30): Pair<List<BookSummary>, Int> {
    val response = get("/api/libraries/$libraryId/items?limit=$limit&page=$page&minified=1&sort=media.metadata.title")
    return response.getJSONArray("results").objects().map(::summary) to response.optInt("total")
  }

  suspend fun podcast(itemId: String): PodcastDetail = PodcastDetail.fromServerJson(get("/api/items/$itemId?expanded=1"))

  /** A single episode, ready to play or download. */
  suspend fun episode(itemId: String, episodeId: String): BookDetail {
    val item = get("/api/items/$itemId?expanded=1")
    val episode = item.optJSONObject("media")?.optJSONArray("episodes")?.objects()?.firstOrNull { it.optString("id") == episodeId }
      ?: throw ApiException(404, "Episode not found")
    return BookDetail.fromEpisodeJson(item, episode)
  }

  /** A book, or a podcast episode when [key] has an episode id. */
  suspend fun playable(key: MediaKey): BookDetail =
    if (key.episodeId == null) item(key.itemId) else episode(key.itemId, key.episodeId)

  suspend fun librarySeries(libraryId: String, page: Int, limit: Int = 30): Pair<List<SeriesSummary>, Int> {
    val response = get("/api/libraries/$libraryId/series?minified=1&sort=name&limit=$limit&page=$page")
    val series = response.getJSONArray("results").objects().map {
      SeriesSummary(it.getString("id"), it.optString("name"), it.optJSONArray("books")?.objects()?.map(::summary) ?: emptyList())
    }
    return series to response.optInt("total")
  }

  /** One series' books in reading order. */
  suspend fun seriesBooks(libraryId: String, seriesId: String): List<BookSummary> {
    val filter = URLEncoder.encode(Base64.encodeToString(seriesId.toByteArray(), Base64.NO_WRAP), "UTF-8")
    val response = get("/api/libraries/$libraryId/items?minified=1&filter=series.$filter&sort=sequence")
    return response.getJSONArray("results").objects().map(::summary)
  }

  suspend fun search(libraryId: String, query: String): List<BookSummary> {
    val q = URLEncoder.encode(query, "UTF-8")
    val response = get("/api/libraries/$libraryId/search?q=$q&limit=20")
    return listOf("book", "podcast").flatMap { kind ->
      response.optJSONArray(kind)?.objects()?.mapNotNull { it.optJSONObject("libraryItem") }?.map(::summary) ?: emptyList()
    }
  }

  /** Books and podcast episodes the user is partway through, latest first. */
  suspend fun itemsInProgress(): List<BookSummary> {
    val response = get("/api/me/items-in-progress")
    return response.getJSONArray("libraryItems").objects().mapNotNull { item ->
      when (item.optString("mediaType")) {
        MEDIA_BOOK -> summary(item)
        // Podcasts come with the episode in progress
        MEDIA_PODCAST -> item.optJSONObject("recentEpisode")?.let { episode -> episodeSummary(item, episode) }
        else -> null
      }
    }
  }

  /** Progress for every book and episode the user has started, keyed by [MediaKey.key]. */
  suspend fun allProgress(): Map<String, ServerProgress> {
    val response = get("/api/me")
    return (response.optJSONArray("mediaProgress") ?: JSONArray()).objects()
      .map(ServerProgress::fromJson)
      .associateBy { it.mediaKey.key }
  }

  suspend fun item(itemId: String): BookDetail = BookDetail.fromServerJson(get("/api/items/$itemId?expanded=1"))

  /** Returns null when the user has no progress for the item; throws when the server can't be reached. */
  suspend fun progress(itemId: String, episodeId: String? = null): ServerProgress? {
    return try {
      ServerProgress.fromJson(get("/api/me/progress/${progressPath(MediaKey(itemId, episodeId))}", shortClient))
    } catch (e: ApiException) {
      if (e.code == 404) null else throw e
    }
  }

  suspend fun progress(key: MediaKey): ServerProgress? = progress(key.itemId, key.episodeId)

  /** The raw `/api/me/listening-stats` response; see [ListeningStats.from]. */
  suspend fun listeningStats(): JSONObject = get("/api/me/listening-stats")

  /** Marks a book or episode finished, or not finished (which also starts it over). */
  suspend fun setFinished(key: MediaKey, finished: Boolean) {
    val body = JSONObject().put("isFinished", finished)
    if (!finished) body.put("currentTime", 0).put("progress", 0)
    execute(client, Request.Builder().url(absoluteUrl("/api/me/progress/${progressPath(key)}")).patch(body.toString().toRequestBody(json)).build())
  }

  private fun progressPath(key: MediaKey) = if (key.episodeId == null) key.itemId else "${key.itemId}/${key.episodeId}"

  suspend fun syncLocalSession(session: JSONObject) {
    post("/api/session/local", session)
  }

  suspend fun syncLocalSessions(sessions: List<JSONObject>, deviceInfo: JSONObject) {
    post("/api/session/local-all", JSONObject().put("sessions", JSONArray(sessions)).put("deviceInfo", deviceInfo))
  }

  private fun summary(item: JSONObject): BookSummary {
    val media = item.optJSONObject("media") ?: JSONObject()
    val metadata = media.optJSONObject("metadata") ?: JSONObject()
    return BookSummary(
      id = item.getString("id"),
      title = metadata.optString("title"),
      author = metadata.optString("authorName"),
      duration = media.optDouble("duration", 0.0),
      seriesName = metadata.optString("seriesName"),
      mediaType = item.optString("mediaType", MEDIA_BOOK)
    )
  }

  private fun episodeSummary(item: JSONObject, episode: JSONObject): BookSummary {
    val podcastMetadata = item.optJSONObject("media")?.optJSONObject("metadata") ?: JSONObject()
    return BookSummary(
      id = item.getString("id"),
      title = episode.optString("title"),
      author = podcastMetadata.optString("title"),
      duration = EpisodeSummary.fromServerJson(episode).duration,
      mediaType = MEDIA_PODCAST,
      episodeId = episode.getString("id")
    )
  }

  private suspend fun get(path: String, httpClient: OkHttpClient = client): JSONObject =
    execute(httpClient, Request.Builder().url(absoluteUrl(path)).get().build())

  private suspend fun post(path: String, body: JSONObject): JSONObject =
    execute(client, Request.Builder().url(absoluteUrl(path)).post(body.toString().toRequestBody(json)).build())

  private suspend fun execute(httpClient: OkHttpClient, request: Request): JSONObject = withContext(Dispatchers.IO) {
    httpClient.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw ApiException(response.code, text.ifEmpty { "Request failed (${response.code})" })
      }
      if (text.startsWith("{")) JSONObject(text) else JSONObject()
    }
  }

  private inner class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
      val account = auth.current
      val request = chain.request()
      // Only attach credentials to requests for the signed in server
      if (account == null || !isForServer(request.url, account.serverAddress)) {
        return chain.proceed(request)
      }
      val builder = request.newBuilder()
      account.customHeaders.forEach { (k, v) -> builder.header(k, v) }
      if (request.header("Authorization") != null) return chain.proceed(builder.build())

      val response = chain.proceed(builder.header("Authorization", "Bearer ${account.accessToken}").build())
      // Handled here rather than with an Authenticator so requests relayed through the phone are covered too
      if (response.code != 401) return response
      val newToken = renewAccessToken(account.accessToken) ?: return response
      response.close()
      return chain.proceed(builder.header("Authorization", "Bearer $newToken").build())
    }
  }

  /** Gets a new access token after the server rejected [failedToken], or null if that's not possible. */
  @Synchronized
  private fun renewAccessToken(failedToken: String): String? {
    val account = auth.current ?: return null
    // Another request already renewed the token while this one was in flight
    if (account.accessToken != failedToken) return account.accessToken
    return if (account.linkedToPhone) renewFromPhone(account) else refreshWithServer(account)
  }

  private fun renewFromPhone(account: Account): String? = try {
    val fresh = phoneLink.requestAccountBlocking(staleToken = account.accessToken)
    auth.save(fresh)
    fresh.accessToken.takeIf { fresh.serverAddress == account.serverAddress }
  } catch (e: PhoneSignedOutException) {
    Log.w(TAG, "Phone app is signed out, signing out")
    auth.clear()
    null
  } catch (e: Exception) {
    Log.w(TAG, "Could not get a new token from the phone: ${e.message}")
    null
  }

  private fun refreshWithServer(account: Account): String? {
    val refreshToken = account.refreshToken ?: return null
    val refreshRequest = Request.Builder()
      .url("${account.serverAddress}/auth/refresh")
      .header("x-refresh-token", refreshToken)
      .apply { account.customHeaders.forEach { (k, v) -> header(k, v) } }
      .post(ByteArray(0).toRequestBody(null))
      .build()
    return try {
      baseClient.newCall(refreshRequest).execute().use { refreshResponse ->
        if (refreshResponse.code == 401 || refreshResponse.code == 403) {
          Log.w(TAG, "Refresh token rejected, signing out")
          auth.clear()
          return null
        }
        if (!refreshResponse.isSuccessful) return null
        val user = JSONObject(refreshResponse.body?.string().orEmpty()).getJSONObject("user")
        val newAccessToken = user.getString("accessToken")
        auth.updateTokens(newAccessToken, user.optStringOrNull("refreshToken"))
        newAccessToken
      }
    } catch (e: Exception) {
      Log.e(TAG, "Token refresh failed", e)
      null
    }
  }

  companion object {
    private const val TAG = "AbsApi"

    fun normalizeAddress(address: String): String {
      var normalized = address.trim().trimEnd('/')
      if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
        normalized = "https://$normalized"
      }
      return normalized
    }

    /**
     * True if [url] is on the signed in server: same scheme, host and port, and under its path. A plain
     * prefix check would also match e.g. "https://abs.example.com.evil.net" or port 13378 for 1337.
     */
    fun isForServer(url: HttpUrl, serverAddress: String): Boolean {
      val server = serverAddress.toHttpUrlOrNull() ?: return false
      if (url.scheme != server.scheme || url.host != server.host || url.port != server.port) return false
      val basePath = server.encodedPath.trimEnd('/')
      return basePath.isEmpty() || url.encodedPath == basePath || url.encodedPath.startsWith("$basePath/")
    }
  }
}
