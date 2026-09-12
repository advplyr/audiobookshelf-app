package com.audiobookshelf.app.plugins

import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.server.MtlsManager
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Lets the frontend manage the mTLS client certificate (from the Android system KeyChain) used
 * for the currently connected server. See [MtlsManager] for how the selected certificate is
 * actually applied to outgoing requests.
 */
@CapacitorPlugin(name = "AbsCertificate")
class AbsCertificate : Plugin() {
  private lateinit var mainActivity: MainActivity

  override fun load() {
    mainActivity = activity as MainActivity
  }

  @PluginMethod
  fun getClientCertificateAlias(call: PluginCall) {
    val ret = JSObject()
    ret.put("alias", MtlsManager.getEffectiveAlias())
    call.resolve(ret)
  }

  /**
   * Opens the Android system certificate picker. Usable both before a server connection exists
   * (e.g. on the "add new server" form - the alias is held pending until the connection config is
   * created) and afterwards (persisted directly onto the current config).
   */
  @PluginMethod
  fun selectClientCertificate(call: PluginCall) {
    MtlsManager.chooseCertificateAlias(mainActivity) { alias ->
      if (alias != null) {
        val config = DeviceManager.serverConnectionConfig
        if (config != null) {
          config.clientCertAlias = alias
          DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
        } else {
          MtlsManager.setPendingAlias(alias)
        }
        MtlsManager.onCertificateAliasChanged()
      }
      val ret = JSObject()
      ret.put("alias", alias)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun clearClientCertificate(call: PluginCall) {
    MtlsManager.setPendingAlias(null)
    DeviceManager.serverConnectionConfig?.let { config ->
      config.clientCertAlias = null
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    }
    MtlsManager.onCertificateAliasChanged()
    call.resolve()
  }

  /**
   * Preloads the certificate alias already saved for a server connection config, before the
   * frontend pings it, so the very first request already presents the right certificate instead
   * of failing once and relying on the reactive "select a certificate" prompt. Safe to call with
   * no alias (e.g. a config that never needed one) - this deliberately overwrites any pending
   * alias, since it represents "this is the known-correct alias for the config about to be
   * tested," unlike selectClientCertificate/clearClientCertificate which manage a
   * still-in-progress user selection on the add-server form.
   */
  @PluginMethod
  fun setActiveCertificateAlias(call: PluginCall) {
    val alias = call.getString("alias")?.takeIf { it.isNotBlank() }
    MtlsManager.setPendingAlias(alias)
    MtlsManager.onCertificateAliasChanged()
    call.resolve()
  }
}
