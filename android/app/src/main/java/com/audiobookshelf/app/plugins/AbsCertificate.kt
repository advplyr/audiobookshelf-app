package com.audiobookshelf.app.plugins

import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.server.MtlsManager
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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
   *
   * [call]'s optional "serverConnectionConfigId" identifies the config the frontend is currently
   * presenting this selection for. DeviceManager.serverConnectionConfig is a single global
   * "active config" pointer that a background path (e.g. Android Auto media browsing) can
   * reassign independently of the foreground UI, so the alias is only persisted directly onto it
   * when its id matches - otherwise it's staged as pending, same as when no config exists yet.
   */
  @PluginMethod
  fun selectClientCertificate(call: PluginCall) {
    val targetConfigId = call.getString("serverConnectionConfigId")?.takeIf { it.isNotBlank() }
    MtlsManager.chooseCertificateAlias(mainActivity) { alias ->
      if (alias != null) {
        val config = DeviceManager.serverConnectionConfig
        if (targetConfigId != null && config?.id == targetConfigId) {
          config.clientCertAlias = alias
          // The KeyChain picker's result callback runs on the main thread (see
          // MtlsManager.chooseCertificateAlias), so this disk write must not run inline.
          GlobalScope.launch(Dispatchers.IO) { DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData) }
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

  /**
   * Discards a certificate alias staged via selectClientCertificate before any
   * ServerConnectionConfig existed, without adopting it onto one. Call when an in-progress
   * "add new server" attempt is abandoned (e.g. backing out to the server list, or starting to
   * add a different server), so the staged alias doesn't get attached to whatever config is
   * created/connected next.
   */
  @PluginMethod
  fun clearPendingCertificate(call: PluginCall) {
    MtlsManager.clearPendingAlias()
    call.resolve()
  }

  @PluginMethod
  fun clearClientCertificate(call: PluginCall) {
    val targetConfigId = call.getString("serverConnectionConfigId")?.takeIf { it.isNotBlank() }
    MtlsManager.setPendingAlias(null)
    val config = DeviceManager.serverConnectionConfig
    if (targetConfigId != null && config?.id == targetConfigId) {
      config.clientCertAlias = null
      GlobalScope.launch(Dispatchers.IO) { DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData) }
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
