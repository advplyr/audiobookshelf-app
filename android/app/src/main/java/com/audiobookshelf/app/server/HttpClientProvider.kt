package com.audiobookshelf.app.server

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Central singleton provider for OkHttpClient instances used by the app.
 *
 * Responsibilities:
 * - Provide shared default client
 * - Provide a low-timeout "ping" client for quick connectivity checks
 * - Support dynamic configuration of mutual TLS (mTLS) using ONLY AndroidKeyStore alias
 * - Rebuild underlying clients when security configuration changes
 */
object HttpClientProvider { // Kotlin 'object' already guarantees singleton semantics
  // Stored alias for client cert (mTLS enabled if not null / not blank)
  @Volatile private var clientCertAlias: String? = null
  @Volatile private var appContext: Context? = null

  @Volatile private var defaultClient: OkHttpClient = buildBaseClient()
  @Volatile private var pingClient: OkHttpClient = buildBaseClient().newBuilder()
    .callTimeout(3, TimeUnit.SECONDS)
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .writeTimeout(3, TimeUnit.SECONDS)
    .build()

  @Volatile private var downloadClient: OkHttpClient? = null
  fun getDefaultClient(): OkHttpClient = defaultClient
  fun getPingClient(): OkHttpClient = pingClient
  fun getDownloadClient(): OkHttpClient {
    return downloadClient ?: synchronized(this) {
      downloadClient ?: defaultClient.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS) // no timeout
        .build().also { downloadClient = it }
    }
  }

  /**
   * Update the currently active mTLS alias. Passing null (or blank) disables mTLS.
   */
  @Synchronized
  fun updateMtlsConfig(alias: String?) {
    val normalized = alias?.takeIf { it.isNotBlank() }
    if (clientCertAlias == normalized) return
    clientCertAlias = normalized
    rebuildClients()
  }

  /**
   * Overload that allows updating (or initially supplying) the application context.
   */
  @Synchronized
  fun updateMtlsConfig(alias: String?, context: Context?) {
    if (context != null) appContext = context.applicationContext
    updateMtlsConfig(alias)
  }

  @Synchronized
  private fun rebuildClients() {
    val base = buildBaseClient()
    defaultClient = base
    pingClient = base.newBuilder()
      .callTimeout(3, TimeUnit.SECONDS)
      .connectTimeout(3, TimeUnit.SECONDS)
      .readTimeout(3, TimeUnit.SECONDS)
      .writeTimeout(3, TimeUnit.SECONDS)
      .build()
    downloadClient = null
  }

  private fun buildBaseClient(): OkHttpClient =
    MtlsOkHttpClientFactory.create(clientCertAlias)
}
