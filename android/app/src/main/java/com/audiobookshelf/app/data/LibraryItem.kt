package com.audiobookshelf.app.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import androidx.media.utils.MediaConstants as LegacyMediaConstants

@JsonIgnoreProperties(ignoreUnknown = true)
class LibraryItem(
  id:String,
  var ino:String,
  var libraryId:String,
  var folderId:String,
  var path:String,
  var relPath:String,
  var mtimeMs:Long,
  var ctimeMs:Long,
  var birthtimeMs:Long,
  var addedAt:Long,
  var updatedAt:Long,
  var lastScan:Long?,
  var scanVersion:String?,
  var isMissing:Boolean,
  var isInvalid:Boolean,
  var mediaType:String,
  var media:MediaType,
  var libraryFiles:MutableList<LibraryFile>?,
  var userMediaProgress:MediaProgress?, // Only included when requesting library item with progress (for downloads)
  var collapsedSeries: CollapsedSeries?,
  var localLibraryItemId:String?, // For Android Auto
  val recentEpisode: PodcastEpisode?  // Podcast episode shelf uses this
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title: String
    get() {
      if (collapsedSeries != null) {
        return collapsedSeries!!.title
      }
      return media.metadata.title
    }
  @get:JsonIgnore
  val authorName get() = media.metadata.getAuthorDisplayName()

  @JsonIgnore
  fun getCoverUri(): Uri {
    if (media.coverPath == null) {
      return Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
    }

    // As of v2.17.0 token is not needed with cover image requests
    if (DeviceManager.isServerVersionGreaterThanOrEqualTo("2.17.0")) {
      return Uri.parse("${DeviceManager.serverAddress}/api/items/$id/cover")
    }

    return Uri.parse("${DeviceManager.serverAddress}/api/items/$id/cover?token=${DeviceManager.token}")
  }

  @JsonIgnore
  fun checkHasTracks():Boolean {
    return media.checkHasTracks()
  }

  @get:JsonIgnore
  val seriesSequence: String
    get() {
      if (mediaType != "podcast") {
        return ((media as Book).metadata as BookMetadata).series?.get(0)?.sequence.orEmpty()
      } else {
        return ""
      }
    }

  @get:JsonIgnore
  val seriesSequenceParts: List<String>
    get() {
      if (seriesSequence.isEmpty()) {
        return listOf("")
      }
      return seriesSequence.split(".", limit = 2)
    }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, authorId: String?, showSeriesNumber: Boolean?, groupTitle: String?): MediaDescriptionCompat {
    val extras = Bundle()

    if (collapsedSeries == null) {
      if (localLibraryItemId != null) {
        extras.putLong(
          MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
          MediaDescriptionCompat.STATUS_DOWNLOADED
        )
      }

      if (progress != null) {
        if (progress.isFinished) {
          extras.putInt(
            LegacyMediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
            LegacyMediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
          )
        } else {
          extras.putInt(
            LegacyMediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
            LegacyMediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
          )
          extras.putDouble(
            LegacyMediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, progress.progress
          )
        }
      } else if (mediaType != "podcast") {
        extras.putInt(
          LegacyMediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          LegacyMediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
        )
      }

      if (media.metadata.explicit) {
        extras.putLong(
          LegacyMediaConstants.METADATA_KEY_IS_EXPLICIT,
          LegacyMediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT
        )
      }
    }
    if (groupTitle !== null) {
      extras.putString(
        LegacyMediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
        groupTitle
      )
    }

    val mediaId = if (localLibraryItemId != null) {
      localLibraryItemId
    } else if (collapsedSeries != null) {
      if (authorId != null) {
        "__LIBRARY__${libraryId}__AUTHOR_SERIES__${authorId}__${collapsedSeries!!.id}"
      } else {
        "__LIBRARY__${libraryId}__SERIES__${collapsedSeries!!.id}"
      }
    } else {
      id
    }
    var subtitle = authorName
    if (collapsedSeries != null) {
      subtitle = "${collapsedSeries!!.numBooks} books"
    }
    var itemTitle = title
    if (showSeriesNumber == true && seriesSequence != "") {
      itemTitle = "$seriesSequence. $itemTitle"
    }
    return MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(itemTitle)
      .setIconUri(getCoverUri())
      .setSubtitle(subtitle)
      .setExtras(extras)
      .build()
  }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, authorId: String?, showSeriesNumber: Boolean?): MediaDescriptionCompat {
    return getMediaDescription(progress, ctx, authorId, showSeriesNumber, null)
  }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, authorId: String?): MediaDescriptionCompat {
    return getMediaDescription(progress, ctx, authorId, null, null)
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    /*
    This is needed so Android auto library hierarchy for author series can be implemented
     */
    return getMediaDescription(progress, ctx, null, null, null)
  }

  /**
   * The modern, Media3 counterpart to getMediaDescription.
   * This is the most detailed version that all other overloads will call.
   */
  @OptIn(UnstableApi::class)
  @JsonIgnore
  fun getMediaItem(
    progress: MediaProgressWrapper?,
    context: Context,
    authorId: String?,
    showSeriesNumber: Boolean?,
    groupTitle: String?
  ): MediaItem {
    val extras = Bundle()
    if (collapsedSeries == null) {
      if (localLibraryItemId != null) {
        extras.putLong(
          MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS,
          MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED
        )
      }

      if (progress != null) {
        if (progress.isFinished) {
          extras.putInt(
            MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
          )
        } else {
          extras.putInt(
            MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
          )
          extras.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, progress.progress)
        }
      } else if (mediaType != "podcast") {
        extras.putInt(
          MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
        )
      }

      if (media.metadata.explicit) {
        extras.putLong(
          MediaConstants.EXTRAS_KEY_IS_EXPLICIT,
          MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT
        )
      }
    }

    if (groupTitle != null) {
      extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
    }

    val mediaId = if (localLibraryItemId != null) {
      localLibraryItemId
    } else if (collapsedSeries != null) {
      if (authorId != null) {
        "__LIBRARY__${libraryId}__AUTHOR_SERIES__${authorId}__${collapsedSeries!!.id}"
      } else {
        "__LIBRARY__${libraryId}__SERIES__${collapsedSeries!!.id}"
      }
    } else {
      id
    }

    val itemTitle = if (showSeriesNumber == true && seriesSequence.isNotEmpty()) {
      "$seriesSequence. $title"
    } else {
      title
    }
    val subtitle = if (collapsedSeries != null) {
      "${collapsedSeries!!.numBooks} books"
    } else {
      authorName
    }

    val metadata = MediaMetadata.Builder()
      .setTitle(itemTitle)
      .setSubtitle(subtitle)
      .setArtist(authorName) // Always good to have the artist
      .setArtworkUri(getCoverUri())
      .setIsPlayable(collapsedSeries == null) // A series is browsable, a book is playable
      .setIsBrowsable(collapsedSeries != null)
      .setExtras(extras)
      .build()

    return MediaItem.Builder()
      .setMediaId(mediaId.toString())
      .setMediaMetadata(metadata)
      .build()
  }

// --- Create the public overloads that mirror the legacy structure ---

  @JsonIgnore
  fun getMediaItem(
    progress: MediaProgressWrapper?,
    ctx: Context,
    authorId: String?,
    showSeriesNumber: Boolean?
  ): MediaItem {
    return getMediaItem(progress, ctx, authorId, showSeriesNumber, null)
  }

  @JsonIgnore
  fun getMediaItem(progress: MediaProgressWrapper?, ctx: Context, authorId: String?): MediaItem {
    return getMediaItem(progress, ctx, authorId, null, null)
  }

  @JsonIgnore
  override fun getMediaItem(progress: MediaProgressWrapper?, context: Context): MediaItem {
    return getMediaItem(progress, context, null, null, null)
  }







}
