package com.audiobookshelf.app.data

import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import com.audiobookshelf.app.R
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
  var mediaEntityId:String?,
  var mediaType:String,
  var mediaMetadata:MediaTypeMetadata,
  var coverPath:String?,
  var duration:Double,
  var playMethod:Int,
  var audioTracks:MutableList<AudioTrack>,
  var currentTime:Double,
  var libraryItem:LibraryItem?,
  var localMediaItem:LocalMediaItem?,
  var serverUrl:String?,
  var token:String?
) {

  val isHLS get() = playMethod == PLAYMETHOD_TRANSCODE
  val isLocal get() = playMethod == PLAYMETHOD_LOCAL
  val currentTimeMs get() = (currentTime * 1000L).toLong()

  @JsonIgnore
  fun getTitle():String {
    if (mediaMetadata == null) return "Unset"
    var metadata = mediaMetadata as BookMetadata
    return metadata.title
  }

  @JsonIgnore
  fun getAuthor():String {
    if (mediaMetadata == null) return "Unset"
    var metadata = mediaMetadata as BookMetadata
    return metadata.authorName ?: "Unset"
  }

  @JsonIgnore
  fun getCoverUri(): Uri {
    if (localMediaItem?.coverPath != null) return Uri.parse(localMediaItem?.coverPath) ?: Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)

    if (coverPath == null) return Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)
    return Uri.parse("$serverUrl/api/items/$libraryItemId/cover?token=$token")
  }

  @JsonIgnore
  fun getContentUri(): Uri {
    var audioTrack = audioTracks[0]
    if (isLocal) return Uri.parse(audioTrack.contentUrl) // Local content url
    return Uri.parse("$serverUrl${audioTrack.contentUrl}?token=$token")
  }

  @JsonIgnore
  fun getMimeType():String {
    var audioTrack = audioTracks[0]
    return audioTrack.mimeType
  }

  @JsonIgnore
  fun getMediaMetadataCompat(): MediaMetadataCompat {
    var metadataBuilder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, this.getTitle())
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, getTitle())
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, this.getAuthor())
      .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, this.getAuthor())
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, this.getAuthor())
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "series")
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
    return metadataBuilder.build()
  }

  @JsonIgnore
  fun getExoMediaMetadata(): MediaMetadata {
    var authorName = this.getAuthor()
    var metadataBuilder = MediaMetadata.Builder()
      .setTitle(this.getTitle())
      .setDisplayTitle(this.getTitle())
      .setArtist(authorName)
      .setAlbumArtist(authorName)
      .setSubtitle(authorName)

    var contentUri = this.getContentUri()
    metadataBuilder.setMediaUri(contentUri)

    return metadataBuilder.build()
  }
}
