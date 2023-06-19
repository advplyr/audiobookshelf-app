package com.audiobookshelf.app.data

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import com.audiobookshelf.app.player.*

@JsonIgnoreProperties(ignoreUnknown = true)
class PlaybackSession(
  var id:String,
  var userId:String?,
  var libraryItemId:String?,
  var episodeId:String?,
  var mediaType:String,
  var mediaMetadata:MediaTypeMetadata,
  var deviceInfo:DeviceInfo,
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
  val isPodcastEpisode get() = mediaType == "podcast"
  @get:JsonIgnore
  val currentTimeMs get() = (currentTime * 1000L).toLong()
  @get:JsonIgnore
  val totalDurationMs get() = (getTotalDuration() * 1000L).toLong()
  @get:JsonIgnore
  val localLibraryItemId get() = localLibraryItem?.id ?: ""
  @get:JsonIgnore
  val localMediaProgressId get() = if (localEpisodeId.isNullOrEmpty()) localLibraryItemId else "$localLibraryItemId-$localEpisodeId"
  @get:JsonIgnore
  val progress get() = currentTime / getTotalDuration()
  @get:JsonIgnore
  val isLocalLibraryItemOnly get() = localLibraryItemId != "" && libraryItemId == null
  @get:JsonIgnore
  val mediaItemId get() = if (isLocalLibraryItemOnly) localMediaProgressId else if (episodeId.isNullOrEmpty()) libraryItemId ?: "" else "$libraryItemId-$episodeId"

  @JsonIgnore
  fun getCurrentTrackIndex():Int {
    for (i in 0 until audioTracks.size) {
      val track = audioTracks[i]
      if (currentTimeMs >= track.startOffsetMs && (track.endOffsetMs > currentTimeMs)) {
        return i
      }
    }
    return audioTracks.size - 1
  }

  @JsonIgnore
  fun getNextTrackIndex():Int {
    for (i in 0 until audioTracks.size) {
      val track = audioTracks[i]
      if (currentTimeMs < track.startOffsetMs) {
        return i
      }
    }
    return audioTracks.size - 1
  }

  @JsonIgnore
  fun getChapterForTime(time:Long):BookChapter? {
    if (chapters.isEmpty()) return null
    return chapters.find { time >= it.startMs && it.endMs > time}
  }

  @JsonIgnore
  fun getCurrentTrackEndTime():Long {
    val currentTrack = audioTracks[this.getCurrentTrackIndex()]
    return currentTrack.startOffsetMs + currentTrack.durationMs
  }

  @JsonIgnore
  fun getNextChapterForTime(time:Long):BookChapter? {
    if (chapters.isEmpty()) return null
    return chapters.find { time < it.startMs } // First chapter where start time is > then time
  }

  @JsonIgnore
  fun getNextTrackEndTime():Long {
    val currentTrack = audioTracks[this.getNextTrackIndex()]
    return currentTrack.startOffsetMs + currentTrack.durationMs
  }

  @JsonIgnore
  fun getCurrentTrackTimeMs():Long {
    val currentTrack = audioTracks[this.getCurrentTrackIndex()]
    val time = currentTime - currentTrack.startOffset
    return (time * 1000L).toLong()
  }

  @JsonIgnore
  fun getTrackStartOffsetMs(index:Int):Long {
    if (index < 0 || index >= audioTracks.size) return 0L
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
    if (localLibraryItem?.coverContentUrl != null) return Uri.parse(localLibraryItem?.coverContentUrl) ?: Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)

    if (coverPath == null) return Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
    return Uri.parse("$serverAddress/api/items/$libraryItemId/cover?token=${DeviceManager.token}")
  }

  @JsonIgnore
  fun getContentUri(audioTrack:AudioTrack): Uri {
    if (isLocal) return Uri.parse(audioTrack.contentUrl) // Local content url
    return Uri.parse("$serverAddress${audioTrack.contentUrl}?token=${DeviceManager.token}")
  }

  @JsonIgnore
  fun getMediaMetadataCompat(ctx: Context): MediaMetadataCompat {
    val metadataBuilder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getCoverUri().toString())
      .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, getCoverUri().toString())
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, getCoverUri().toString())

    // Local covers get bitmap
    if (localLibraryItem?.coverContentUrl != null) {
      val bitmap = if (Build.VERSION.SDK_INT < 28) {
        MediaStore.Images.Media.getBitmap(ctx.contentResolver, getCoverUri())
      } else {
        val source: ImageDecoder.Source = ImageDecoder.createSource(ctx.contentResolver, getCoverUri())
        ImageDecoder.decodeBitmap(source)
      }
      metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
      metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
    }

    return metadataBuilder.build()
  }

  @JsonIgnore
  fun getExoMediaMetadata(): MediaMetadata {
    val metadataBuilder = MediaMetadata.Builder()
      .setTitle(displayTitle)
      .setDisplayTitle(displayTitle)
      .setArtist(displayAuthor)
      .setAlbumArtist(displayAuthor)
      .setSubtitle(displayAuthor)
      .setAlbumTitle(displayAuthor)
      .setDescription(displayAuthor)
      .setArtworkUri(getCoverUri())
      .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)

    return metadataBuilder.build()
  }

  @JsonIgnore
  fun getMediaItems():List<MediaItem> {
    val mediaItems:MutableList<MediaItem> = mutableListOf()

    for (audioTrack in audioTracks) {
      val mediaMetadata = this.getExoMediaMetadata()
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
    castMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE, displayAuthor ?: "")
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
    return PlaybackSession(id,userId,libraryItemId,episodeId,mediaType,mediaMetadata,deviceInfo,chapters,displayTitle,displayAuthor,coverPath,duration,playMethod,startedAt,updatedAt,timeListening,audioTracks,currentTime,libraryItem,localLibraryItem,localEpisodeId,serverConnectionConfigId,serverAddress, mediaPlayer)
  }

  @JsonIgnore
  fun syncData(syncData: MediaProgressSyncData) {
    timeListening += syncData.timeListened
    updatedAt = System.currentTimeMillis()
    currentTime = syncData.currentTime
  }

  @JsonIgnore
  fun getNewLocalMediaProgress():LocalMediaProgress {
    return LocalMediaProgress(localMediaProgressId,localLibraryItemId,localEpisodeId,getTotalDuration(),progress,currentTime,false,null,null,updatedAt,startedAt,null,serverConnectionConfigId,serverAddress,userId,libraryItemId,episodeId)
  }
}
