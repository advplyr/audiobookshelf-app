package com.audiobookshelf.app.managers

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.security.KeyChain
import android.util.Log
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Manages mutual TLS (mTLS) client certificates for server connections.
 *
 * Certificate aliases are stored per server connection config ID in SharedPreferences.
 * The actual private key and certificate chain are retrieved from the Android KeyStore
 * via KeyChain, respecting the system's certificate access controls.
 *
 * All methods that call KeyChain.getPrivateKey() or KeyChain.getCertificateChain() are
 * BLOCKING and MUST be called from a background thread, never from the main thread.
 */
object MtlsManager {
  private const val tag = "MtlsManager"
  private const val PREFS_NAME = "mtls_prefs"
  private const val ALIAS_PREFIX = "alias_"

  lateinit var applicationContext: Context

  // Saved so we can restore it when mTLS is disabled
  private var originalDefaultSocketFactory: SSLSocketFactory? = null

  // --- Centralized client cache ---
  /** The server config ID for which the cached clients were built, or null if plain. */
  @Volatile private var configuredForServer: String? = null
  /** Cached SSLSocketFactory built from the current server's cert, or null if plain. */
  @Volatile private var cachedSslSocketFactory: SSLSocketFactory? = null
  /** Cached TrustManager for the current mTLS context. */
  @Volatile private var cachedTrustManager: X509TrustManager? = null

  fun initialize(context: Context) {
    applicationContext = context.applicationContext
  }

  /**
   * Returns an OkHttpClient configured for the current server connection.
   * If the current server has an mTLS certificate, the client will present it.
   * If not, a plain OkHttpClient is returned.
   *
   * Thread-safe. The underlying SSLSocketFactory is cached and reused across calls.
   * Call [refreshForServer] when the server connection or certificate changes.
   */
  fun getClient(
    connectTimeout: Long = 0,
    callTimeout: Long = 0
  ): OkHttpClient {
    val builder = OkHttpClient.Builder()

    // Apply cached mTLS SSLSocketFactory if available
    val sslFactory = cachedSslSocketFactory
    val trustMgr = cachedTrustManager
    if (sslFactory != null && trustMgr != null) {
      builder.sslSocketFactory(sslFactory, trustMgr)
    }

    if (connectTimeout > 0) builder.connectTimeout(connectTimeout, TimeUnit.SECONDS)
    if (callTimeout > 0) builder.callTimeout(callTimeout, TimeUnit.SECONDS)
    return builder.build()
  }

  /**
   * Rebuilds the cached SSL context for the given server and applies the global default
   * SSLSocketFactory (for HttpURLConnection / CapacitorHttp / ExoPlayer).
   *
   * If no certificate alias is configured for this server, clears any cached mTLS state
   * and restores the original global default — resulting in plain TLS clients.
   *
   * BLOCKING — must be called from a background thread.
   */
  fun refreshForServer(serverConfigId: String) {
    val alias = getAliasForServer(serverConfigId)
    if (alias != null) {
      val pair = getKeyAndChain(alias)
      if (pair != null) {
        val (privateKey, certChain) = pair
        try {
          val keyManager = makeKeyManager(alias, privateKey, certChain)
          val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
          tmf.init(null as java.security.KeyStore?)
          val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
          val sslContext = SSLContext.getInstance("TLS")
          sslContext.init(arrayOf(keyManager), null, null)

          // Cache for getClient()
          cachedSslSocketFactory = sslContext.socketFactory
          cachedTrustManager = trustManager
          configuredForServer = serverConfigId

          // Apply globally for HttpURLConnection (CapacitorHttp, ExoPlayer DefaultHttpDataSource)
          if (originalDefaultSocketFactory == null) {
            originalDefaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
          }
          HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
          Log.d(tag, "refreshForServer: mTLS configured for server $serverConfigId (alias=$alias)")
          return
        } catch (e: Exception) {
          Log.e(tag, "refreshForServer: failed to build mTLS context for alias=$alias", e)
        }
      }
    }

    // No cert or failed — clear mTLS state
    cachedSslSocketFactory = null
    cachedTrustManager = null
    configuredForServer = null
    resetGlobalDefault()
    Log.d(tag, "refreshForServer: plain TLS for server $serverConfigId (no cert)")
  }

  /**
   * Returns the server config ID for which mTLS clients are currently configured, or null.
   */
  fun getConfiguredServer(): String? = configuredForServer

  fun getAliasForServer(serverConfigId: String): String? {
    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString("$ALIAS_PREFIX$serverConfigId", null)
  }

  fun setAliasForServer(serverConfigId: String, alias: String?) {
    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (alias == null) {
      prefs.edit().remove("$ALIAS_PREFIX$serverConfigId").apply()
    } else {
      prefs.edit().putString("$ALIAS_PREFIX$serverConfigId", alias).apply()
    }
  }

  fun clearAliasForServer(serverConfigId: String) {
    setAliasForServer(serverConfigId, null)
    Log.d(tag, "Cleared mTLS certificate alias for server: $serverConfigId")
  }

  /**
   * Shows the Android system certificate picker so the user can select a client certificate.
   * Stores the chosen alias and calls [callback] with it (or null if cancelled).
   * Must be called from an Activity (can be on any thread).
   */
  fun chooseAlias(
    activity: Activity,
    serverConfigId: String,
    serverAddress: String,
    callback: (String?) -> Unit
  ) {
    val host = try { Uri.parse(serverAddress).host } catch (e: Exception) { null }
    val existingAlias = getAliasForServer(serverConfigId)
    // KeyChain.choosePrivateKeyAlias must be called from the main/UI thread; otherwise the
    // system certificate picker dialog is never displayed.
    activity.runOnUiThread {
      KeyChain.choosePrivateKeyAlias(
        activity,
        { alias ->
          setAliasForServer(serverConfigId, alias)
          callback(alias)
        },
        arrayOf("RSA", "EC"),
        null,
        host,
        -1,
        existingAlias
      )
    }
  }

  /**
   * Builds an OkHttpClient with mTLS configured for the given server config ID.
   *
   * BLOCKING — must be called from a background thread.
   * Returns null if no certificate alias is configured for this server.
   */
  fun buildOkHttpClient(
    serverConfigId: String,
    connectTimeout: Long = 0,
    callTimeout: Long = 0
  ): OkHttpClient? {
    val alias = getAliasForServer(serverConfigId) ?: return null
    return buildOkHttpClientForAlias(alias, connectTimeout, callTimeout)
  }

  /**
   * Builds an OkHttpClient configured to present the certificate identified by [alias].
   *
   * BLOCKING — must be called from a background thread.
   */
  fun buildOkHttpClientForAlias(
    alias: String,
    connectTimeout: Long = 0,
    callTimeout: Long = 0
  ): OkHttpClient? {
    val (privateKey, certChain) = getKeyAndChain(alias) ?: return null
    return try {
      val keyManager = makeKeyManager(alias, privateKey, certChain)
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      tmf.init(null as java.security.KeyStore?)
      val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(arrayOf(keyManager), null, null)
      val builder = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
      if (connectTimeout > 0) builder.connectTimeout(connectTimeout, TimeUnit.SECONDS)
      if (callTimeout > 0) builder.callTimeout(callTimeout, TimeUnit.SECONDS)
      Log.d(tag, "mTLS OkHttpClient built for alias: $alias")
      builder.build()
    } catch (e: Exception) {
      Log.e(tag, "Failed to build mTLS OkHttpClient for alias: $alias", e)
      null
    }
  }

  /**
   * Returns the private key and certificate chain for the given alias.
   *
   * BLOCKING — must be called from a background thread.
   */
  fun getKeyAndChain(alias: String): Pair<PrivateKey, Array<X509Certificate>>? {
    return try {
      val privateKey = KeyChain.getPrivateKey(applicationContext, alias) ?: run {
        Log.e(tag, "No private key for alias: $alias")
        return null
      }
      val certChain = KeyChain.getCertificateChain(applicationContext, alias) ?: run {
        Log.e(tag, "No certificate chain for alias: $alias")
        return null
      }
      Pair(privateKey, certChain)
    } catch (e: Exception) {
      Log.e(tag, "Failed to retrieve key/chain for alias: $alias", e)
      null
    }
  }

  /**
   * Applies the certificate for a specific alias as the global default SSLSocketFactory.
   * Use this when you have the alias but not yet the server config ID (e.g. before first login).
   *
   * BLOCKING — must be called from a background thread.
   */
  fun applyAsGlobalDefaultForAlias(alias: String) {
    val (privateKey, certChain) = getKeyAndChain(alias) ?: return
    try {
      val keyManager = makeKeyManager(alias, privateKey, certChain)
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(arrayOf(keyManager), null, null)
      if (originalDefaultSocketFactory == null) {
        originalDefaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
      }
      HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
      Log.d(tag, "applyAsGlobalDefaultForAlias: installed mTLS socket factory for alias=$alias")
    } catch (e: Exception) {
      Log.e(tag, "applyAsGlobalDefaultForAlias: failed for alias=$alias", e)
    }
  }

  /**
   * Installs the mTLS client certificate as the global default SSLSocketFactory so that
   * ALL HTTPS connections in the process (including Capacitor's CapacitorHttp plugin which
   * uses HttpURLConnection internally) will present the certificate when the server requests one.
   *
   * BLOCKING — must be called from a background thread.
   * No-op if no certificate alias is configured for [serverConfigId].
   */
  fun applyAsGlobalDefault(serverConfigId: String) {
    val alias = getAliasForServer(serverConfigId) ?: run {
      Log.d(tag, "applyAsGlobalDefault: no alias for $serverConfigId, skipping")
      return
    }
    val (privateKey, certChain) = getKeyAndChain(alias) ?: return
    try {
      val keyManager = makeKeyManager(alias, privateKey, certChain)
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(arrayOf(keyManager), null, null) // null = system trust managers
      // Save the original factory the first time so we can restore it later
      if (originalDefaultSocketFactory == null) {
        originalDefaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
      }
      HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
      Log.d(tag, "applyAsGlobalDefault: installed mTLS socket factory for alias=$alias")
    } catch (e: Exception) {
      Log.e(tag, "applyAsGlobalDefault: failed for alias=$alias", e)
    }
  }

  /**
   * Restores the original default SSLSocketFactory, removing any previously installed
   * mTLS configuration from [applyAsGlobalDefault].
   */
  fun resetGlobalDefault() {
    originalDefaultSocketFactory?.let {
      HttpsURLConnection.setDefaultSSLSocketFactory(it)
      Log.d(tag, "resetGlobalDefault: restored original SSLSocketFactory")
    }
  }

  private fun makeKeyManager(
    alias: String,
    privateKey: PrivateKey,
    certChain: Array<X509Certificate>
  ): X509KeyManager {
    return object : X509KeyManager {
      override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
      override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = alias
      override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
      override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
      override fun getCertificateChain(alias: String?): Array<X509Certificate> = certChain
      override fun getPrivateKey(alias: String?): PrivateKey = privateKey
    }
  }
}
