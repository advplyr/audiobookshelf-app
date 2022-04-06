package com.audiobookshelf.app.data

import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import com.audiobookshelf.app.R
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata

// TODO: enum or something in kotlin?
val PLAYMETHOD_DIRECTPLAY = 0
val PLAYMETHOD_DIRECTSTREAM = 1
val PLAYMETHOD_TRANSCODE = 2
val PLAYMETHOD_LOCAL = 3

@JsonIgnoreProperties(ignoreUnknown = true)
class PlaybackSession(
  var id:String,
  var userId:String?,
  var libraryItemId:String?,
  var episodeId:String?,
  var mediaType:String,
  var mediaMetadata:MediaTypeMetadata,
  var chapters:List<BookChapter>,
  var displayTitle: String?,
  var displayAuthor: String?,
  var coverPath:String?,
  var duration:Double,
  var playMethod:Int,
  var audioTracks:MutableList<AudioTrack>,
  var currentTime:Double,
  var libraryItem:LibraryItem?,
  var localLibraryItem:LocalLibraryItem?,
  var serverUrl:String?,
  var token:String?
) {

  val isHLS get() = playMethod == PLAYMETHOD_TRANSCODE
  val isLocal get() = playMethod == PLAYMETHOD_LOCAL
  val currentTimeMs get() = (currentTime * 1000L).toLong()

  @JsonIgnore
  fun getCurrentTrackIndex():Int {
    for (i in 0..(audioTracks.size - 1)) {
      var track = audioTracks[i]
      if (currentTimeMs >= track.startOffsetMs && (track.endOffsetMs) > currentTimeMs) {
        return i
      }
    }
    return audioTracks.size - 1
  }

  @JsonIgnore
  fun getCurrentTrackTimeMs():Long {
    var currentTrack = audioTracks[this.getCurrentTrackIndex()]
    var time = currentTime - currentTrack.startOffset
    return (time * 1000L).toLong()
  }

  @JsonIgnore
  fun getTrackStartOffsetMs(index:Int):Long {
    var currentTrack = audioTracks[index]
    return (currentTrack.startOffset * 1000L).toLong()
  }

  @JsonIgnore
  fun getTotalDuration():Double {
    var total = 0.0
    audioTracks.forEach { total += it.duration }
    return total
  }

  @JsonIgnore
  fun getCoverUri(): Uri {
    if (localLibraryItem?.coverContentUrl != null) return Uri.parse(localLibraryItem?.coverContentUrl) ?: Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)

    if (coverPath == null) return Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)
    return Uri.parse("$serverUrl/api/items/$libraryItemId/cover?token=$token")
  }

  @JsonIgnore
  fun getContentUri(audioTrack:AudioTrack): Uri {
    if (isLocal) return Uri.parse(audioTrack.contentUrl) // Local content url
    return Uri.parse("$serverUrl${audioTrack.contentUrl}?token=$token")
  }

  @JsonIgnore
  fun getMediaMetadataCompat(): MediaMetadataCompat {
    var metadataBuilder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "series")
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
    return metadataBuilder.build()
  }

  @JsonIgnore
  fun getExoMediaMetadata(audioTrack:AudioTrack): MediaMetadata {
    var metadataBuilder = MediaMetadata.Builder()
      .setTitle(displayTitle)
      .setDisplayTitle(displayTitle)
      .setArtist(displayAuthor)
      .setAlbumArtist(displayAuthor)
      .setSubtitle(displayAuthor)

    var contentUri = this.getContentUri(audioTrack)
    metadataBuilder.setMediaUri(contentUri)

    return metadataBuilder.build()
  }

  @JsonIgnore
  fun getMediaItems():List<MediaItem> {
    var mediaItems:MutableList<MediaItem> = mutableListOf()

    for (audioTrack in audioTracks) {
      var mediaMetadata = this.getExoMediaMetadata(audioTrack)
      var mediaUri = this.getContentUri(audioTrack)
      var mimeType = audioTrack.mimeType
      var mediaItem = MediaItem.Builder().setUri(mediaUri).setMediaMetadata(mediaMetadata).setMimeType(mimeType).build()
      mediaItems.add(mediaItem)
    }
    return mediaItems
  }
}
