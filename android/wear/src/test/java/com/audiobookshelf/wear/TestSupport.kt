package com.audiobookshelf.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audiobookshelf.wear.data.AbsApi
import com.audiobookshelf.wear.data.Account
import com.audiobookshelf.wear.data.AuthStore
import com.audiobookshelf.wear.data.TokenCrypto
import com.audiobookshelf.wear.sync.PhoneLink
import com.audiobookshelf.wear.sync.ServerRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject

/** Reads a server response saved under src/test/resources/fixtures. */
fun fixture(name: String): String =
  checkNotNull(object {}.javaClass.classLoader?.getResource("fixtures/$name")) { "Missing fixture $name" }.readText()

fun fixtureJson(name: String) = JSONObject(fixture(name))

fun jsonResponse(body: String, code: Int = 200): MockResponse =
  MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

/** Robolectric has no Android Keystore, so tests store the account unencrypted. */
object PlainTokenCrypto : TokenCrypto {
  override fun encrypt(plain: String) = plain
  override fun decrypt(encoded: String) = encoded
}

val testContext: Context get() = ApplicationProvider.getApplicationContext()

/** An [AbsApi] talking to [server] directly, signed in with its own (non-phone) account. */
class ApiHarness(private val server: MockWebServer) {
  val auth = AuthStore(testContext, PlainTokenCrypto)
  private val phoneLink = PhoneLink(testContext)
  private val route = ServerRoute(testContext, auth, phoneLink, CoroutineScope(Dispatchers.Unconfined))
  val api = AbsApi(auth, phoneLink, route)

  fun signIn(accessToken: String = "token1", refreshToken: String? = "refresh1"): Account =
    Account(
      serverAddress = server.url("/").toString().trimEnd('/'),
      username = "demo",
      userId = "user1",
      accessToken = accessToken,
      refreshToken = refreshToken,
      customHeaders = emptyMap(),
      serverVersion = "2.37.1"
    ).also(auth::save)
}
