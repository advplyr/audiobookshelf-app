package com.audiobookshelf.app.data

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import androidx.media.utils.MediaConstants as LegacyMediaConstants

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
      val booksWithAudio = books?.filter { b -> b.media.checkHasTracks() }
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
      extras.putString(
        LegacyMediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
        groupTitle
      )
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

  /**
   * detailed implementation for creating a MediaItem, including the groupTitle hint.
   */
  @OptIn(UnstableApi::class)
  @JsonIgnore
  fun getMediaItem(
    progress: MediaProgressWrapper?,
    context: Context,
    groupTitle: String?
  ): MediaItem {
    val extras = Bundle()

    // This is the correct, modern way to provide the group title hint for Media3.
    if (groupTitle != null) {
      extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
    }

    val mediaId = "__LIBRARY__${libraryId}__SERIES__${id}"
    val subtitle = "$audiobookCount books"

    val metadata = MediaMetadata.Builder()
      .setTitle(this.title)
      .setSubtitle(subtitle)
      .setIsBrowsable(true) // A Series is always a browsable folder of books
      .setIsPlayable(false)
      .setExtras(extras)
      .build()

    return MediaItem.Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(metadata)
      .build()
  }

  /**
   * The modern, public override for getMediaItem. It calls the detailed
   * implementation with a null groupTitle, perfectly mirroring the ExoPlayer pattern.
   */
  @JsonIgnore
  override fun getMediaItem(progress: MediaProgressWrapper?, context: Context): MediaItem {
    return getMediaItem(progress, context, null)
  }
}
