package com.audiobookshelf.app.managers

import com.audiobookshelf.app.data.DownloadUsingCellularSetting.ALWAYS
import com.audiobookshelf.app.data.DownloadUsingCellularSetting.ASK
import com.audiobookshelf.app.data.DownloadUsingCellularSetting.NEVER
import com.audiobookshelf.app.managers.DownloadNetworkPolicy.Block
import com.audiobookshelf.app.models.DownloadItem.Companion.NETWORK_MODE_CELLULAR
import com.audiobookshelf.app.models.DownloadItem.Companion.NETWORK_MODE_WIFI_ONLY
import com.audiobookshelf.app.models.DownloadItem.Companion.NETWORK_MODE_WIFI_PREFERRED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNetworkPolicyTest {
  private val allModes = listOf(NETWORK_MODE_CELLULAR, NETWORK_MODE_WIFI_PREFERRED, NETWORK_MODE_WIFI_ONLY, null)

  @Test
  fun offlineBlocksEveryDownload() {
    for (setting in listOf(ALWAYS, ASK, NEVER)) for (mode in allModes) {
      assertEquals(Block.OFFLINE, DownloadNetworkPolicy.block(setting, connected = false, metered = false, networkMode = mode))
    }
  }

  @Test
  fun unmeteredNetworkAllowsEveryDownload() {
    for (setting in listOf(ALWAYS, ASK, NEVER)) for (mode in allModes) {
      assertNull(DownloadNetworkPolicy.block(setting, connected = true, metered = false, networkMode = mode))
    }
  }

  @Test
  fun wifiOnlySettingNeverUsesMeteredNetworks() {
    for (mode in allModes) {
      assertEquals(Block.WIFI_REQUIRED, DownloadNetworkPolicy.block(NEVER, connected = true, metered = true, networkMode = mode))
    }
  }

  @Test
  fun allowMobileDataSettingUsesMeteredNetworks() {
    for (mode in allModes) {
      assertNull(DownloadNetworkPolicy.block(ALWAYS, connected = true, metered = true, networkMode = mode))
    }
  }

  @Test
  fun askSettingOnMeteredNetworkFollowsEachDownloadsMode() {
    assertNull(DownloadNetworkPolicy.block(ASK, true, true, NETWORK_MODE_CELLULAR))
    assertEquals(Block.WIFI_REQUIRED, DownloadNetworkPolicy.block(ASK, true, true, NETWORK_MODE_WIFI_PREFERRED))
    assertEquals(Block.WIFI_REQUIRED, DownloadNetworkPolicy.block(ASK, true, true, NETWORK_MODE_WIFI_ONLY))
    // Items queued before network modes existed wait for Wi-Fi rather than silently using data.
    assertEquals(Block.WIFI_REQUIRED, DownloadNetworkPolicy.block(ASK, true, true, null))
  }

  @Test
  fun onlyWifiPreferredDownloadsAskToUseMobileData() {
    assertTrue(DownloadNetworkPolicy.needsCellularApproval(ASK, true, true, NETWORK_MODE_WIFI_PREFERRED))
    assertTrue(DownloadNetworkPolicy.needsCellularApproval(ASK, true, true, null))
    assertFalse(DownloadNetworkPolicy.needsCellularApproval(ASK, true, true, NETWORK_MODE_WIFI_ONLY))
    assertFalse(DownloadNetworkPolicy.needsCellularApproval(ASK, true, true, NETWORK_MODE_CELLULAR))
  }

  @Test
  fun noApprovalRequestUnlessAskSettingOnConnectedMeteredNetwork() {
    for (mode in allModes) {
      assertFalse(DownloadNetworkPolicy.needsCellularApproval(ALWAYS, true, true, mode))
      assertFalse(DownloadNetworkPolicy.needsCellularApproval(NEVER, true, true, mode))
      assertFalse(DownloadNetworkPolicy.needsCellularApproval(ASK, true, false, mode))
      assertFalse(DownloadNetworkPolicy.needsCellularApproval(ASK, false, true, mode))
    }
  }

  @Test
  fun initialModeAsksOnlyForAskSettingOnMeteredNetwork() {
    assertEquals(NETWORK_MODE_WIFI_ONLY, DownloadNetworkPolicy.initialMode(NEVER, metered = true))
    assertEquals(NETWORK_MODE_WIFI_ONLY, DownloadNetworkPolicy.initialMode(NEVER, metered = false))
    assertEquals(NETWORK_MODE_CELLULAR, DownloadNetworkPolicy.initialMode(ALWAYS, metered = true))
    assertEquals(NETWORK_MODE_CELLULAR, DownloadNetworkPolicy.initialMode(ALWAYS, metered = false))
    assertEquals(NETWORK_MODE_WIFI_PREFERRED, DownloadNetworkPolicy.initialMode(ASK, metered = false))
    assertNull(DownloadNetworkPolicy.initialMode(ASK, metered = true))
  }

  @Test
  fun defaultModeAssumesTheUserAllowedTheCurrentNetwork() {
    assertEquals(NETWORK_MODE_CELLULAR, DownloadNetworkPolicy.defaultMode(metered = true))
    assertEquals(NETWORK_MODE_WIFI_PREFERRED, DownloadNetworkPolicy.defaultMode(metered = false))
  }
}
