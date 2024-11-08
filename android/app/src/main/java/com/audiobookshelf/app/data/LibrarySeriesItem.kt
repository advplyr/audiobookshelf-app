package com.audiobookshelf.app.data

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.utils.MediaConstants
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class LibrarySeriesItem(
  id:String,
  var libraryId:String,
  var name:String,
  var description:String?,
  var addedAt:Long,
  var updatedAt:Long,
  var books:MutableList<LibraryItem>?,
  var localLibraryItemId:String? // For Android Auto
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = name

  @get:JsonIgnore
  val audiobookCount: Int
    get() {
      if (books == null) return 0
      val booksWithAudio = books?.filter { b -> (b.media as Book).numTracks != 0 }
      return booksWithAudio?.size ?: 0
    }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, groupTitle: String?): MediaDescriptionCompat {
    val extras = Bundle()

    if (localLibraryItemId != null) {
      extras.putLong(
        MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
        MediaDescriptionCompat.STATUS_DOWNLOADED
      )
    }
    if (groupTitle !== null) {
      extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
    }

    val mediaId = "__LIBRARY__${libraryId}__SERIES__${id}"
    return MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(title)
      //.setIconUri(getCoverUri())
      .setSubtitle("$audiobookCount books")
      .setExtras(extras)
      .build()
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    return getMediaDescription(progress, ctx, null)
  }
}
