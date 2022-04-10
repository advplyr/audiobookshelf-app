package com.audiobookshelf.app.data

import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.MediaProgressSyncData
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage

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
  var startedAt:Long,
  var updatedAt:Long,
  var timeListening:Long,
  var audioTracks:MutableList<AudioTrack>,
  var currentTime:Double,
  var libraryItem:LibraryItem?,
  var localLibraryItem:LocalLibraryItem?,
  var serverConnectionConfigId:String?,
  var serverAddress:String?
) {

  @get:JsonIgnore
  val isHLS get() = playMethod == PLAYMETHOD_TRANSCODE
  @get:JsonIgnore
  val isLocal get() = playMethod == PLAYMETHOD_LOCAL
  @get:JsonIgnore
  val currentTimeMs get() = (currentTime * 1000L).toLong()
  @get:JsonIgnore
  val localLibraryItemId get() = localLibraryItem?.id ?: ""
  @get:JsonIgnore
  val localMediaProgressId get() = if (episodeId.isNullOrEmpty()) localLibraryItemId else "$localLibraryItemId-$episodeId"
  @get:JsonIgnore
  val progress get() = currentTime / getTotalDuration()

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
    return Uri.parse("$serverAddress/api/items/$libraryItemId/cover?token=${DeviceManager.token}")
  }

  @JsonIgnore
  fun getContentUri(audioTrack:AudioTrack): Uri {
    if (isLocal) return Uri.parse(audioTrack.contentUrl) // Local content url
    return Uri.parse("$serverAddress${audioTrack.contentUrl}?token=${DeviceManager.token}")
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

      var queueItem = getQueueItem(audioTrack) // Queue item used in exo player CastManager
      var mediaItem = MediaItem.Builder().setUri(mediaUri).setTag(queueItem).setMediaMetadata(mediaMetadata).setMimeType(mimeType).build()
      mediaItems.add(mediaItem)
    }
    return mediaItems
  }

  @JsonIgnore
  fun getCastMediaMetadata(audioTrack:AudioTrack):com.google.android.gms.cast.MediaMetadata {
    var castMetadata = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_AUDIOBOOK_CHAPTER)
    castMetadata.addImage(WebImage(getCoverUri()))
    castMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, displayTitle)
    castMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST, displayAuthor)
    castMetadata.putInt(com.google.android.gms.cast.MediaMetadata.KEY_TRACK_NUMBER, audioTrack.index)
    return castMetadata
  }

  @JsonIgnore
  fun getQueueItem(audioTrack:AudioTrack):MediaQueueItem {
    var castMetadata = getCastMediaMetadata(audioTrack)

    var mediaUri = getContentUri(audioTrack)
    var mediaInfoBuilder = MediaInfo.Builder(mediaUri.toString())
    mediaInfoBuilder.setContentUrl(mediaUri.toString())
    mediaInfoBuilder.setMetadata(castMetadata)
    mediaInfoBuilder.setContentType(audioTrack.mimeType)
    var mediaInfo = mediaInfoBuilder.build()

    var queueItem = MediaQueueItem.Builder(mediaInfo)
    queueItem.setItemId(audioTrack.index)
    queueItem.setPlaybackDuration(audioTrack.duration)
    return queueItem.build()
  }

  @JsonIgnore
  fun clone():PlaybackSession {
    return PlaybackSession(id,userId,libraryItemId,episodeId,mediaType,mediaMetadata,chapters,displayTitle,displayAuthor,coverPath,duration,playMethod,startedAt,updatedAt,timeListening,audioTracks,currentTime,libraryItem,localLibraryItem,serverConnectionConfigId,serverAddress)
  }

  @JsonIgnore
  fun syncData(syncData:MediaProgressSyncData) {
    timeListening += syncData.timeListened
    updatedAt = System.currentTimeMillis()
    currentTime = syncData.currentTime
  }

  @JsonIgnore
  fun getNewLocalMediaProgress():LocalMediaProgress {
    return LocalMediaProgress(localMediaProgressId,localLibraryItemId,episodeId,getTotalDuration(),progress,currentTime,false,updatedAt,startedAt,null,serverConnectionConfigId,serverAddress,userId,libraryItemId)
  }
}
