package com.audiobookshelf.app.plugins

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
  name = "AbsNetwork",
  permissions = [
    Permission(strings = [Manifest.permission.ACCESS_FINE_LOCATION], alias = "location")
  ]
)
class AbsNetwork : Plugin() {
  @PluginMethod
  fun getCurrentWifiSsid(call: PluginCall) {
    if (getPermissionState("location") != PermissionState.GRANTED) {
      requestPermissionForAlias("location", call, "locationPermsCallback")
      return
    }

    resolveCurrentWifiSsid(call)
  }

  @PermissionCallback
  private fun locationPermsCallback(call: PluginCall) {
    if (getPermissionState("location") == PermissionState.GRANTED) {
      resolveCurrentWifiSsid(call)
    } else {
      call.resolve(JSObject().put("ssid", null))
    }
  }

  private fun resolveCurrentWifiSsid(call: PluginCall) {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val wifiInfo = wifiManager.connectionInfo
    var ssid: String? = wifiInfo?.ssid

    if (ssid == null || ssid == WifiManager.UNKNOWN_SSID || ssid == "<unknown ssid>") {
      ssid = null
    }

    if (ssid != null && ssid.length >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
      ssid = ssid.substring(1, ssid.length - 1)
    }

    call.resolve(JSObject().put("ssid", ssid))
  }
}
