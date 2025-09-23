package com.audiobookshelf.app.server

import android.content.Context
import android.security.KeyChain
import android.security.KeyChainException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import okhttp3.OkHttpClient
import java.security.Principal
import java.net.Socket

/**
 * Central provider for OkHttpClient instances used by the app.
 *
 * Responsibilities:
 * - Provide shared default client
 * - Provide a low-timeout "ping" client for quick connectivity checks
 * - Support dynamic configuration of mutual TLS (mTLS) using ONLY AndroidKeyStore / KeyChain alias
 * - Rebuild underlying clients when security configuration changes
 */
object HttpClientProvider {
  // Stored alias for client cert (mTLS enabled iff not null)
  @Volatile private var clientCertAlias: String? = null
  @Volatile private var appContext: Context? = null

  @Volatile private var defaultClient: OkHttpClient = buildBaseClient()
  @Volatile private var pingClient: OkHttpClient = buildBaseClient().newBuilder()
    .callTimeout(3, TimeUnit.SECONDS)
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .writeTimeout(3, TimeUnit.SECONDS)
    .build()

  fun getDefaultClient(): OkHttpClient = defaultClient
  fun getPingClient(): OkHttpClient = pingClient

  @Synchronized
  fun updateMtlsConfig(alias: String?) {
    if (clientCertAlias == alias) return
    clientCertAlias = alias?.takeIf { it.isNotBlank() }
    rebuildClients()
  }

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
  }

  private class AliasMaterial(val privateKey: PrivateKey?, val chain: Array<X509Certificate>?)

  private fun fetchAliasMaterial(alias: String): AliasMaterial? {
    // Try AndroidKeyStore first
    try {
      val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      val entry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
      if (entry != null) {
        return AliasMaterial(entry.privateKey, entry.certificateChain.map { it as X509Certificate }.toTypedArray())
      }
    } catch (e: Exception) { e.printStackTrace() }

    // Fallback to KeyChain
    val ctx = appContext ?: return null
    try {
      val privateKey: PrivateKey? = KeyChain.getPrivateKey(ctx, alias)
      val chain: Array<X509Certificate>? = KeyChain.getCertificateChain(ctx, alias)
      if (privateKey != null && chain != null) {
        return AliasMaterial(privateKey, chain)
      }
    } catch (e: KeyChainException) { e.printStackTrace() }
      catch (_: InterruptedException) { Thread.currentThread().interrupt() }
      catch (e: Exception) { e.printStackTrace() }
    return null
  }

  private fun buildBaseClient(): OkHttpClient {
    val builder = OkHttpClient.Builder()
    val alias = clientCertAlias

    if (alias != null) {
      try {
        val material = fetchAliasMaterial(alias)
        if (material?.privateKey != null && material.chain != null) {
          val km = SingleAliasKeyManager(alias, material.privateKey, material.chain)
          var trustManagers: Array<TrustManager>? = null
          try {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            material.chain.forEachIndexed { index, cert -> keyStore.setCertificateEntry("ca$index", cert) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            trustManagers = tmf.trustManagers
          } catch (e: Exception) {
            e.printStackTrace()
            trustManagers = null
          }
          val sslContext = SSLContext.getInstance("TLS")
          sslContext.init(arrayOf(km), trustManagers ?: systemDefaultTrustManagers(), SecureRandom())
          val trustManagerForSocketFactory = (trustManagers?.firstOrNull() as? X509TrustManager) ?: systemDefaultX509TrustManager()
          builder.sslSocketFactory(sslContext.socketFactory, trustManagerForSocketFactory)
        }
      } catch (e: Exception) {
        e.printStackTrace() // fallback to system defaults
      }
    }

    return builder.build()
  }

  private class SingleAliasKeyManager(
    private val alias: String,
    private val privateKey: PrivateKey,
    private val certChain: Array<X509Certificate>
  ) : X509KeyManager {
    override fun getClientAliases(keyType: String?, issuers: Array<Principal>): Array<String>? =
      if (keyType != null && keyType.equals(privateKey.algorithm, ignoreCase = true)) arrayOf(alias) else null

    override fun chooseClientAlias(keyTypes: Array<String>, issuers: Array<Principal>, socket: Socket): String? =
      keyTypes.firstOrNull { it.equals(privateKey.algorithm, ignoreCase = true) }?.let { alias }

    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>, socket: Socket): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (this.alias == alias) certChain else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (this.alias == alias) privateKey else null
  }

  private fun systemDefaultTrustManagers(): Array<TrustManager> {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as KeyStore?)
    return tmf.trustManagers
  }

  private fun systemDefaultX509TrustManager(): X509TrustManager {
    val trustManagers = systemDefaultTrustManagers()
    return trustManagers.first { it is X509TrustManager } as X509TrustManager
  }
}
