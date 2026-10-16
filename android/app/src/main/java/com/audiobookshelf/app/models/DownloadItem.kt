package com.audiobookshelf.app.models

import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.MediaType
import com.fasterxml.jackson.annotation.JsonIgnore

data class DownloadItem(
        val id: String,
        val libraryItemId: String,
        val episodeId: String?,
        val userMediaProgress: MediaProgress?,
        val serverConnectionConfigId: String,
        val serverAddress: String,
        val serverUserId: String,
        val mediaType: String,
        val itemFolderPath: String,
        val localFolder: LocalFolder,
        val itemTitle: String,
        val itemSubfolder: String,
        val media: MediaType,
        val downloadItemParts: MutableList<DownloadItemPart>,
        @JsonIgnore var terminalFailureAt: Long? = null,
        @JsonIgnore var stagingCleanupAt: Long? = null,
        /** One of the NETWORK_MODE_* values; null for items queued before modes existed. */
        @JsonIgnore var networkMode: String? = null
) {
  companion object {
    /** May use metered networks. */
    const val NETWORK_MODE_CELLULAR = "cellular"
    /** Started on Wi-Fi in "ask first" mode: pauses on metered networks and asks to continue. */
    const val NETWORK_MODE_WIFI_PREFERRED = "wifiPreferred"
    /** The user chose to wait for Wi-Fi: pauses on metered networks without asking. */
    const val NETWORK_MODE_WIFI_ONLY = "wifiOnly"
  }

  @get:JsonIgnore
  val isInternalStorage
    get() = localFolder.id.startsWith("internal-")

  @get:JsonIgnore
  val isDownloadFinished
    get() = downloadItemParts.isNotEmpty() && downloadItemParts.all {
      it.completed && it.moved && !it.isMoving && !it.failed
    }

  @JsonIgnore
  fun getNextDownloadItemParts(limit: Int, now: Long): MutableList<DownloadItemPart> {
    val itemParts = mutableListOf<DownloadItemPart>()
    if (limit <= 0) return itemParts

    for (it in downloadItemParts) {
      if (!it.completed && !it.failed && it.downloadId == null && it.nextAttemptAt <= now) {
        itemParts.add(it)
        if (itemParts.size >= limit) break
      }
    }

    return itemParts
  }
}
