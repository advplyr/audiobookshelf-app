package com.audiobookshelf.wear.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the watch's server session: either borrowed from the phone app (an access token only,
 * renewed by asking the phone) or the watch's own sign in. The phone's refresh token is never
 * shared because the server rotates it, which would log one of the devices out.
 * Tokens are encrypted with a key kept in the Android Keystore.
 */
class AuthStore(context: Context) {
  private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
  private val _account = MutableStateFlow(load())
  val account: StateFlow<Account?> = _account.asStateFlow()

  val current get() = _account.value

  @Synchronized
  fun save(account: Account) {
    prefs.edit().putString(KEY_ACCOUNT, encrypt(account.toJson().toString())).putBoolean(KEY_SIGNED_OUT, false).apply()
    _account.value = account
  }

  @Synchronized
  fun updateTokens(accessToken: String, refreshToken: String?) {
    val account = _account.value ?: return
    save(account.copy(accessToken = accessToken, refreshToken = refreshToken ?: account.refreshToken))
  }

  @Synchronized
  fun clear() {
    prefs.edit().remove(KEY_ACCOUNT).apply()
    _account.value = null
  }

  /** Signs out at the user's request; the watch then waits for them before signing in through the phone again. */
  @Synchronized
  fun signOut() {
    clear()
    prefs.edit().putBoolean(KEY_SIGNED_OUT, true).apply()
  }

  /** True after the user signed out on the watch. */
  val signedOutByUser get() = prefs.getBoolean(KEY_SIGNED_OUT, false)

  private fun load(): Account? {
    val stored = prefs.getString(KEY_ACCOUNT, null) ?: return null
    return try {
      Account.fromJson(JSONObject(decrypt(stored)))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load account", e)
      null
    }
  }

  private fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val combined = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(combined, Base64.NO_WRAP)
  }

  private fun decrypt(encoded: String): String {
    val combined = Base64.decode(encoded, Base64.NO_WRAP)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, combined, 0, IV_LENGTH))
    return String(cipher.doFinal(combined, IV_LENGTH, combined.size - IV_LENGTH), Charsets.UTF_8)
  }

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build()
    )
    return generator.generateKey()
  }

  companion object {
    private const val TAG = "AuthStore"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_SIGNED_OUT = "signedOutByUser"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "AbsWearAccount"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
  }
}
