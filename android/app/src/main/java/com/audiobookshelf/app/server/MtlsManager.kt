package com.audiobookshelf.app.server

import android.app.Activity
import android.content.Context
import android.security.KeyChain
import android.util.Log
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.WeakHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Wires the client certificate a user has selected from the Android system KeyChain (Settings >
 * Encryption & credentials > Install a certificate) into every HTTP path the app uses to talk to
 * an Audiobookshelf server, so that a server requesting mTLS during the TLS handshake gets it.
 *
 * The certificate/private key never leave the system KeyChain - only a KeyChain alias is
 * persisted (on [com.audiobookshelf.app.data.ServerConnectionConfig]), and the private key is
 * fetched from KeyChain on demand for each handshake.
 *
 * This covers three of the app's four network stacks:
 *  - OkHttp clients built in Kotlin (ApiHandler, InternalDownloadManager) via [wrap]
 *  - CapacitorHttp (plugins/nativeHttp.js, almost all frontend REST calls) and ExoPlayer's
 *    DefaultHttpDataSource (audio/podcast streaming) - both built on HttpURLConnection - via the
 *    process-wide default set in [refreshGlobalDefault]
 * The fourth (the WebView's own Chromium network stack, used for direct resource loads like cover
 * images) is handled separately in MainActivity's WebViewClient, using [getPrivateKeyAndChain].
 */
object MtlsManager {
  private const val TAG = "MtlsManager"

  private lateinit var appContext: Context

  // The JVM's original default SSL socket factory, captured once before we ever override it, so
  // it can be restored if the user clears their selected certificate.
  private var systemDefaultSslSocketFactory: SSLSocketFactory? = null

  private var cachedAlias: String? = null
  private var cachedSslContext: SSLContext? = null

  // Holds an alias selected via AbsCertificate before any ServerConnectionConfig exists yet (e.g.
  // while still on the "add new server" form). Adopted onto the config once one is created/set.
  private var pendingAlias: String? = null

  // Caches one mTLS-wrapped OkHttpClient per base client, so connections/pooling are reused
  // across requests as long as the selected certificate alias hasn't changed.
  private val wrappedClients = WeakHashMap<OkHttpClient, Pair<String?, OkHttpClient>>()

  fun initialize(context: Context) {
    appContext = context.applicationContext
  }

  private fun currentAlias(): String? = DeviceManager.serverConnectionConfig?.clientCertAlias ?: pendingAlias

  /** Alias currently in effect, for display purposes (e.g. the settings/connect UI). */
  fun getEffectiveAlias(): String? = currentAlias()

  /** Records a certificate chosen before any ServerConnectionConfig exists yet. */
  fun setPendingAlias(alias: String?) {
    pendingAlias = alias
  }

  /**
   * Moves a pending alias (selected before [config] existed) onto it. Returns true if [config]
   * was changed, so the caller knows whether it needs persisting.
   */
  fun adoptPendingAlias(config: ServerConnectionConfig): Boolean {
    val alias = pendingAlias ?: return false
    pendingAlias = null
    if (config.clientCertAlias == alias) return false
    config.clientCertAlias = alias
    return true
  }

  private fun defaultTrustManager(): X509TrustManager {
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(null as KeyStore?)
    return trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().first()
  }

  private fun buildKeyManager(alias: String): X509ExtendedKeyManager {
    return object : X509ExtendedKeyManager() {
      override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?) = alias
      override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
      override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
      override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = null
      override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

      override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        return try {
          KeyChain.getCertificateChain(appContext, alias ?: return null)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load certificate chain for alias $alias", e)
          null
        }
      }

      override fun getPrivateKey(alias: String?): PrivateKey? {
        return try {
          KeyChain.getPrivateKey(appContext, alias ?: return null)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load private key for alias $alias", e)
          null
        }
      }
    }
  }

  @Synchronized
  private fun getSslContext(): SSLContext? {
    val alias = currentAlias() ?: run {
      cachedAlias = null
      cachedSslContext = null
      return null
    }
    cachedSslContext?.let { if (alias == cachedAlias) return it }

    return try {
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(arrayOf(buildKeyManager(alias)), arrayOf<TrustManager>(defaultTrustManager()), SecureRandom())
      cachedAlias = alias
      cachedSslContext = sslContext
      sslContext
    } catch (e: Exception) {
      Log.e(TAG, "Failed to build mTLS SSLContext for alias $alias", e)
      null
    }
  }

  /**
   * Returns [base] unchanged if no client certificate is configured, otherwise a client cloned
   * from [base] with the client certificate wired in. Cached per [base] instance so connection
   * pooling is preserved across calls while the selected alias doesn't change.
   */
  @Synchronized
  fun wrap(base: OkHttpClient): OkHttpClient {
    if (!::appContext.isInitialized) return base
    val alias = currentAlias()
    wrappedClients[base]?.let { (cachedForAlias, client) -> if (cachedForAlias == alias) return client }

    val result = if (alias == null) {
      base
    } else {
      val sslContext = getSslContext() ?: return base
      base.newBuilder().sslSocketFactory(sslContext.socketFactory, defaultTrustManager()).build()
    }
    wrappedClients[base] = Pair(alias, result)
    return result
  }

  /**
   * CapacitorHttp (used for most frontend REST calls) and ExoPlayer's DefaultHttpDataSource
   * (streaming playback) are both built on HttpURLConnection, which isn't configurable per
   * instance - so the client certificate is applied via the JVM-wide default SSL socket factory.
   * Safe for this app since it only ever talks to a single Audiobookshelf server at a time.
   */
  @Synchronized
  fun refreshGlobalDefault() {
    if (!::appContext.isInitialized) return
    if (systemDefaultSslSocketFactory == null) {
      systemDefaultSslSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
    }
    val sslContext = getSslContext()
    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext?.socketFactory ?: systemDefaultSslSocketFactory)
  }

  /** Call after the selected certificate alias changes (set or cleared) for the current server. */
  fun onCertificateAliasChanged() {
    synchronized(this) {
      cachedAlias = null
      cachedSslContext = null
      wrappedClients.clear()
    }
    refreshGlobalDefault()
  }

  /**
   * Opens the system certificate picker (Android's KeyChain UI) so the user can select one of
   * their installed certificates. Must be called with a foreground [activity]; the callback is
   * always delivered on the main thread.
   */
  fun chooseCertificateAlias(activity: Activity, callback: (String?) -> Unit) {
    val serverUri = DeviceManager.serverConnectionConfig?.address?.let {
      try {
        android.net.Uri.parse(it)
      } catch (e: Exception) {
        null
      }
    }
    // choosePrivateKeyAlias launches an Activity internally and must be invoked from the UI thread.
    activity.runOnUiThread {
      KeyChain.choosePrivateKeyAlias(
        activity,
        { alias -> activity.runOnUiThread { callback(alias) } },
        null,
        null,
        serverUri?.host,
        serverUri?.port ?: -1,
        currentAlias()
      )
    }
  }

  /** Resolves KeyChain credentials for [alias]. Blocking - must not be called on the main thread. */
  fun getPrivateKeyAndChain(alias: String): Pair<PrivateKey, Array<X509Certificate>>? {
    if (!::appContext.isInitialized) return null
    return try {
      val privateKey = KeyChain.getPrivateKey(appContext, alias) ?: return null
      val chain = KeyChain.getCertificateChain(appContext, alias) ?: return null
      Pair(privateKey, chain)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to resolve KeyChain credentials for alias $alias", e)
      null
    }
  }
}
