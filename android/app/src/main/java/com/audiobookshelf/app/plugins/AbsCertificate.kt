package com.audiobookshelf.app.plugins

import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.security.keystore.KeyProperties
import android.util.Log
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "AbsCertificate")
class AbsCertificate : Plugin() {
  private val TAG = "AbsCertificate"

  @PluginMethod
  fun pickCertificate(call: PluginCall) {
    val activity = activity
    // Restrict to common key algorithms to avoid showing incompatible certs
    val keyTypes = arrayOf(KeyProperties.KEY_ALGORITHM_RSA, KeyProperties.KEY_ALGORITHM_EC)
    KeyChain.choosePrivateKeyAlias(activity, object : KeyChainAliasCallback {
      override fun alias(alias: String?) {
        if (alias == null) {
          call.reject("User canceled")
          return
        }
        Log.d(TAG, "Picked alias $alias")
        val ret = JSObject()
        ret.put("alias", alias)
        call.resolve(ret)
      }
    }, null, null, null, -1, null)
  }
}
