package com.audiobookshelf.app.models

import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.MediaType
import com.fasterxml.jackson.annotation.JsonIgnore

data class DownloadItem(
  val id: String,
  val libraryItemId:String,
  val episodeId:String?,
  val userMediaProgress: MediaProgress?,
  val serverConnectionConfigId:String,
  val serverAddress:String,
  val serverUserId:String,
  val mediaType: String,
  val itemFolderPath:String,
  val localFolder: LocalFolder,
  val itemTitle: String,
  val itemSubfolder: String,
  val media: MediaType,
  val downloadItemParts: MutableList<DownloadItemPart>
) {
  @get:JsonIgnore
  val isInternalStorage get() = localFolder.id.startsWith("internal-")

  @get:JsonIgnore
  val isDownloadFinished get() = !downloadItemParts.any { !it.completed || it.isMoving }

  /**
   * Whether every part actually arrived. `isDownloadFinished` only means nothing is still running —
   * a permanently failed part is "completed" too — so finishing must be checked separately from
   * succeeding, or a partially downloaded book gets scanned in and reported as available offline.
   */
  @get:JsonIgnore
  val didDownloadSuccessfully get() = downloadItemParts.none { it.failed }

  /**
   * @param isEligible lets the caller exclude parts that are waiting out a retry backoff, so a failing
   * part cannot be re-selected on the very next queue tick.
   */
  @JsonIgnore
  fun getNextDownloadItemParts(limit:Int, isEligible: (DownloadItemPart) -> Boolean = { true }): MutableList<DownloadItemPart> {
    val itemParts = mutableListOf<DownloadItemPart>()
    if (limit == 0) return itemParts

    for (it in downloadItemParts) {
      if (!it.completed && it.downloadId == null && isEligible(it)) {
        itemParts.add(it)
        if (itemParts.size >= limit) break
      }
    }

    return itemParts
  }
}
