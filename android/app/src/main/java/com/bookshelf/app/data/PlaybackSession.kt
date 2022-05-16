package com.bookshelf.app.data

import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
<<<<<<< HEAD:android/app/src/main/java/com/bookshelf/app/data/PlaybackSession.kt
import androidx.core.app.NotificationCompat
import com.bookshelf.app.R
import com.bookshelf.app.device.DeviceManager
import com.bookshelf.app.player.MediaProgressSyncData
=======
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.MediaProgressSyncData
>>>>>>> d626686614e0a5a5008927729435b58a9df4a24b:android/app/src/main/java/com/audiobookshelf/app/data/PlaybackSession.kt
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
  var localEpisodeId:String?,
  var serverConnectionConfigId:String?,
  var serverAddress:String?,
  var mediaPlayer:String?
) {

  @get:JsonIgnore
  val isHLS get() = playMethod == PLAYMETHOD_TRANSCODE
  @get:JsonIgnore
  val isDirectPlay get() = playMethod == PLAYMETHOD_DIRECTPLAY
  @get:JsonIgnore
  val isLocal get() = playMethod == PLAYMETHOD_LOCAL
  @get:JsonIgnore
  val currentTimeMs get() = (currentTime * 1000L).toLong()
  @get:JsonIgnore
  val totalDurationMs get() = (getTotalDuration() * 1000L).toLong()
  @get:JsonIgnore
  val localLibraryItemId get() = localLibraryItem?.id ?: ""
  @get:JsonIgnore
  val localMediaProgressId get() = if (episodeId.isNullOrEmpty()) localLibraryItemId else "$localLibraryItemId-$localEpisodeId"
  @get:JsonIgnore
  val progress get() = currentTime / getTotalDuration()

  @JsonIgnore
  fun getCurrentTrackIndex():Int {
    for (i in 0..(audioTracks.size - 1)) {
      val track = audioTracks[i]
      if (currentTimeMs >= track.startOffsetMs && (track.endOffsetMs) > currentTimeMs) {
        return i
      }
    }
    return audioTracks.size - 1
  }

  @JsonIgnore
  fun getCurrentTrackTimeMs():Long {
    val currentTrack = audioTracks[this.getCurrentTrackIndex()]
    val time = currentTime - currentTrack.startOffset
    return (time * 1000L).toLong()
  }

  @JsonIgnore
  fun getTrackStartOffsetMs(index:Int):Long {
    val currentTrack = audioTracks[index]
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
    if (localLibraryItem?.coverContentUrl != null) return Uri.parse(localLibraryItem?.coverContentUrl) ?: Uri.parse("android.resource://com.bookshelf.app/" + R.drawable.icon)

    if (coverPath == null) return Uri.parse("android.resource://com.bookshelf.app/" + R.drawable.icon)
    return Uri.parse("$serverAddress/api/items/$libraryItemId/cover?token=${DeviceManager.token}")
  }

  @JsonIgnore
  fun getContentUri(audioTrack:AudioTrack): Uri {
    if (isLocal) return Uri.parse(audioTrack.contentUrl) // Local content url
    return Uri.parse("$serverAddress${audioTrack.contentUrl}?token=${DeviceManager.token}")
  }

  @JsonIgnore
  fun getMediaMetadataCompat(): MediaMetadataCompat {
    val metadataBuilder = MediaMetadataCompat.Builder()
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
    val metadataBuilder = MediaMetadata.Builder()
      .setTitle(displayTitle)
      .setDisplayTitle(displayTitle)
      .setArtist(displayAuthor)
      .setAlbumArtist(displayAuthor)
      .setSubtitle(displayAuthor)

    val contentUri = this.getContentUri(audioTrack)
    metadataBuilder.setMediaUri(contentUri)

    return metadataBuilder.build()
  }

  @JsonIgnore
  fun getMediaItems():List<MediaItem> {
    val mediaItems:MutableList<MediaItem> = mutableListOf()

    for (audioTrack in audioTracks) {
      val mediaMetadata = this.getExoMediaMetadata(audioTrack)
      val mediaUri = this.getContentUri(audioTrack)
      val mimeType = audioTrack.mimeType

      val queueItem = getQueueItem(audioTrack) // Queue item used in exo player CastManager
      val mediaItem = MediaItem.Builder().setUri(mediaUri).setTag(queueItem).setMediaMetadata(mediaMetadata).setMimeType(mimeType).build()
      mediaItems.add(mediaItem)
    }
    return mediaItems
  }

  @JsonIgnore
  fun getCastMediaMetadata(audioTrack:AudioTrack):com.google.android.gms.cast.MediaMetadata {
    val castMetadata = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_AUDIOBOOK_CHAPTER)

    coverPath?.let {
      castMetadata.addImage(WebImage(Uri.parse("$serverAddress/api/items/$libraryItemId/cover?token=${DeviceManager.token}")))
    }

    castMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, displayTitle ?: "")
    castMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST, displayAuthor ?: "")
    castMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_CHAPTER_TITLE, audioTrack.title)
    castMetadata.putInt(com.google.android.gms.cast.MediaMetadata.KEY_TRACK_NUMBER, audioTrack.index)
    return castMetadata
  }

  @JsonIgnore
  fun getQueueItem(audioTrack:AudioTrack):MediaQueueItem {
    val castMetadata = getCastMediaMetadata(audioTrack)

    val mediaUri = getContentUri(audioTrack)

    val mediaInfo = MediaInfo.Builder(mediaUri.toString()).apply {
      setContentUrl(mediaUri.toString())
      setContentType(audioTrack.mimeType)
      setMetadata(castMetadata)
      setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
    }.build()

    return MediaQueueItem.Builder(mediaInfo).apply {
      setPlaybackDuration(audioTrack.duration)
    }.build()
  }

  @JsonIgnore
  fun clone():PlaybackSession {
    return PlaybackSession(id,userId,libraryItemId,episodeId,mediaType,mediaMetadata,chapters,displayTitle,displayAuthor,coverPath,duration,playMethod,startedAt,updatedAt,timeListening,audioTracks,currentTime,libraryItem,localLibraryItem,localEpisodeId,serverConnectionConfigId,serverAddress, mediaPlayer)
  }

  @JsonIgnore
  fun syncData(syncData:MediaProgressSyncData) {
    timeListening += syncData.timeListened
    updatedAt = System.currentTimeMillis()
    currentTime = syncData.currentTime
  }

  @JsonIgnore
  fun getNewLocalMediaProgress():LocalMediaProgress {
    return LocalMediaProgress(localMediaProgressId,localLibraryItemId,localEpisodeId,getTotalDuration(),progress,currentTime,false,updatedAt,startedAt,null,serverConnectionConfigId,serverAddress,userId,libraryItemId,episodeId)
  }
}
