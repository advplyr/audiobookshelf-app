/*
    Used in Android Auto to represent a podcast episode or an audiobook in progress
 */

package com.audiobookshelf.app.data

import com.audiobookshelf.app.data.MoshiProvider.Companion.fromJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.json.JSONObject

@JsonIgnoreProperties(ignoreUnknown = true)
data class ItemInProgress(
  val libraryItemWrapper: LibraryItemWrapper,
  val episode: PodcastEpisode?,
  val progressLastUpdate: Long,
  val isLocal: Boolean
) {
  companion object {
    fun makeFromServerObject(serverItem: JSONObject):ItemInProgress {
      val libraryItem = fromJson<LibraryItem>(serverItem.toString())!!
      val progressLastUpdate = serverItem.getLong("progressLastUpdate")
      return ItemInProgress(libraryItem, libraryItem.recentEpisode, progressLastUpdate, false)
    }
  }
}
