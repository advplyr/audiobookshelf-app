package com.audiobookshelf.app.managers

import com.audiobookshelf.app.data.DownloadUsingCellularSetting
import com.audiobookshelf.app.models.DownloadItem

/**
 * Decides which networks a download may use. The "Mobile data downloads" setting applies to
 * metered networks; under "ask first" each download's own network mode decides.
 */
object DownloadNetworkPolicy {
  enum class Block {
    OFFLINE,
    WIFI_REQUIRED
  }

  /** Why [networkMode] may not transfer on the current network, or null when it may. */
  fun block(
          setting: DownloadUsingCellularSetting,
          connected: Boolean,
          metered: Boolean,
          networkMode: String?
  ): Block? {
    if (!connected) return Block.OFFLINE
    if (!metered) return null
    return when (setting) {
      DownloadUsingCellularSetting.ALWAYS -> null
      DownloadUsingCellularSetting.NEVER -> Block.WIFI_REQUIRED
      DownloadUsingCellularSetting.ASK ->
              if (networkMode == DownloadItem.NETWORK_MODE_CELLULAR) null else Block.WIFI_REQUIRED
    }
  }

  /**
   * True when a paused download should be offered mobile data. Downloads the user explicitly
   * queued for Wi-Fi are left alone; items saved before network modes existed (null) are asked.
   */
  fun needsCellularApproval(
          setting: DownloadUsingCellularSetting,
          connected: Boolean,
          metered: Boolean,
          networkMode: String?
  ): Boolean =
          setting == DownloadUsingCellularSetting.ASK &&
                  connected &&
                  metered &&
                  networkMode != DownloadItem.NETWORK_MODE_CELLULAR &&
                  networkMode != DownloadItem.NETWORK_MODE_WIFI_ONLY

  /**
   * Mode for a download the user is starting, or null when the user must choose (ask-first
   * setting on a metered network).
   */
  fun initialMode(setting: DownloadUsingCellularSetting, metered: Boolean): String? =
          when {
            setting == DownloadUsingCellularSetting.NEVER -> DownloadItem.NETWORK_MODE_WIFI_ONLY
            setting == DownloadUsingCellularSetting.ALWAYS -> DownloadItem.NETWORK_MODE_CELLULAR
            !metered -> DownloadItem.NETWORK_MODE_WIFI_PREFERRED
            else -> null
          }

  /**
   * Mode when the caller did not pass one. The frontend only starts a download on a metered
   * network once the setting or the user allowed it.
   */
  fun defaultMode(metered: Boolean): String =
          if (metered) DownloadItem.NETWORK_MODE_CELLULAR else DownloadItem.NETWORK_MODE_WIFI_PREFERRED
}
