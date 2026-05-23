package com.audiobookshelf.app.server

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Factory responsible for producing OkHttpClient instances configured for mTLS using an
 * AndroidKeyStore alias. If alias is null or the key material cannot be loaded, a default
 * client (no client cert) is produced.
 */
object MtlsOkHttpClientFactory {

  fun create(alias: String?): OkHttpClient { // removed unused context param
    val builder = OkHttpClient.Builder()
    if (alias.isNullOrBlank()) return builder.build()

    return try {
      val material = loadKeyMaterial(alias)
      if (material == null) {
        builder.build()
      } else {
        val keyManager = SingleAliasKeyManager(alias, material.privateKey, material.chain)
        val trustManagers = buildTrustManagersFromChain(material.chain) ?: systemDefaultTrustManagers()
        val x509Trust = (trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager)
          ?: systemDefaultX509TrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(keyManager), trustManagers, SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, x509Trust).build()
      }
    } catch (e: Exception) {
      e.printStackTrace()
      builder.build() // fallback to default client
    }
  }

  private class KeyMaterial(val privateKey: PrivateKey, val chain: Array<X509Certificate>) // not a data class (Array equality warning removed)

  /**
   * Load private key + full certificate chain from AndroidKeyStore for the given alias.
   */
  private fun loadKeyMaterial(alias: String): KeyMaterial? = try {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val entry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return null
    val chain = entry.certificateChain.map { it as X509Certificate }.toTypedArray()
    KeyMaterial(entry.privateKey, chain)
  } catch (e: Exception) {
    e.printStackTrace(); null
  }

  /**
   * Build trust managers from the provided certificate chain. We add the entire chain so that
   * the trust manager will trust servers presenting certs issued by the chain's roots/intermediates.
   * If anything fails we return null to allow fallback to system defaults.
   */
  private fun buildTrustManagersFromChain(chain: Array<X509Certificate>): Array<TrustManager>? = try {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
    chain.forEachIndexed { index, cert -> keyStore.setCertificateEntry("ca$index", cert) }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keyStore)
    tmf.trustManagers
  } catch (e: Exception) {
    e.printStackTrace(); null
  }

  private fun systemDefaultTrustManagers(): Array<TrustManager> {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as KeyStore?)
    return tmf.trustManagers
  }

  private fun systemDefaultX509TrustManager(): X509TrustManager =
    systemDefaultTrustManagers().first { it is X509TrustManager } as X509TrustManager

  /**
   * KeyManager that exposes only the selected alias.
   */
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
}
