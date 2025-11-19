package com.audiobookshelf.app.data

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class LibraryCollection(
  id:String,
  var libraryId:String,
  var name:String,
  //var userId:String?,
  var description:String?,
  var books:MutableList<LibraryItem>?,
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = name

  @get:JsonIgnore
  val bookCount get() = if (books != null) books!!.size else 0

  @get:JsonIgnore
  val audiobookCount get() = books?.filter { book -> (book.media as Book).getAudioTracks().isNotEmpty() }?.size ?: 0

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    val extras = Bundle()

    val mediaId = "__LIBRARY__${libraryId}__COLLECTION__${id}"
    return MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(title)
      //.setIconUri(getCoverUri())
      .setSubtitle("${bookCount} books")
      .setExtras(extras)
      .build()
  }

  /**
   * The modern, Media3 counterpart to getMediaDescription.
   * A Collection is always a browsable folder, not a playable item.
   */
  @JsonIgnore
  override fun getMediaItem(progress: MediaProgressWrapper?, context: Context): MediaItem {
    val mediaId = "__LIBRARY__${libraryId}__COLLECTION__${id}"
    val subtitle = "${bookCount} books"

    val metadata = MediaMetadata.Builder()
      .setTitle(this.title)
      .setSubtitle(subtitle)
      .setIsBrowsable(true) // A collection is a browsable folder of books
      .setIsPlayable(false)
      .build()

    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(metadata)
      .build()
  }
}
