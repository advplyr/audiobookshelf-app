package com.audiobookshelf.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.media.utils.MediaConstants
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.audiobookshelf.app.player.PLAYMETHOD_LOCAL
import com.google.common.collect.Iterables.removeIf
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
class LocalLibraryItem(
  id:String,
  var folderId:String,
  var basePath:String,
  var absolutePath:String,
  var contentUrl:String,
  var isInvalid:Boolean,
  var mediaType:String,
  var media:MediaType,
  var localFiles:MutableList<LocalFile>,
  var coverContentUrl:String?,
  var coverAbsolutePath:String?,
  var isLocal:Boolean,
  // If local library item is linked to a server item
  var serverConnectionConfigId:String?,
  var serverAddress:String?,
  var serverUserId:String?,
  var libraryItemId:String?
  ) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = media.metadata.title
  @get:JsonIgnore
  val authorName get() = media.metadata.getAuthorDisplayName()
  @get:JsonIgnore
  val isPodcast get() = mediaType == "podcast"

  @JsonIgnore
  fun getCoverUri(ctx:Context): Uri {
    if (coverContentUrl?.startsWith("file:") == true) {
      return FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", Uri.parse(coverContentUrl).toFile())
    }
    return if (coverContentUrl != null) Uri.parse(coverContentUrl) else Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
  }

  @JsonIgnore
  fun getDuration():Double {
    var total = 0.0
    val audioTracks = media.getAudioTracks()
    audioTracks.forEach{ total += it.duration }
    return total
  }

  @JsonIgnore
  fun updateFromScan(audioTracks:MutableList<AudioTrack>, _localFiles:MutableList<LocalFile>) {
    localFiles = _localFiles
    media.setAudioTracks(audioTracks)

    if (coverContentUrl != null) {
      if (localFiles.find { it.contentUrl == coverContentUrl } == null) {
        // Cover was removed
        coverContentUrl = null
        coverAbsolutePath = null
        media.coverPath = null
      }
    }
  }

  @JsonIgnore
  fun getPlaybackSession(episode:PodcastEpisode?, deviceInfo:DeviceInfo):PlaybackSession {
    val localEpisodeId = episode?.id
    val sessionId = "play_local_${UUID.randomUUID()}"

    // Get current progress for local media
    val mediaProgressId = if (localEpisodeId.isNullOrEmpty()) id else "$id-$localEpisodeId"
    val mediaProgress = DeviceManager.dbManager.getLocalMediaProgress(mediaProgressId)
    val currentTime = mediaProgress?.currentTime ?: 0.0


    val mediaMetadata = media.metadata
    var chapters = if (mediaType == "book") (media as Book).chapters else mutableListOf()
    var audioTracks = media.getAudioTracks() as MutableList<AudioTrack>
    val authorName = mediaMetadata.getAuthorDisplayName()
    val displayTitle = episode?.title ?: mediaMetadata.title
    var duration = getDuration()
    if (episode != null) { // Get podcast episode audio track
      episode.audioTrack?.let { at -> mutableListOf(at) }?.let { tracks -> audioTracks = tracks }
      chapters = episode.chapters
      duration = episode.audioTrack?.duration ?: 0.0
      Log.d("LocalLibraryItem", "getPlaybackSession: Got podcast episode audio track ${audioTracks.size}")
    }

    val dateNow = System.currentTimeMillis()
    return PlaybackSession(sessionId,serverUserId,libraryItemId,episode?.serverEpisodeId, mediaType, mediaMetadata, deviceInfo,chapters ?: mutableListOf(), displayTitle, authorName,null,duration,PLAYMETHOD_LOCAL,dateNow,0L,0L, audioTracks,currentTime,null,this,localEpisodeId,serverConnectionConfigId, serverAddress, "exo-player")
  }

  @JsonIgnore
  fun removeLocalFile(localFileId:String) {
    removeIf(localFiles) { it.id == localFileId }
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx:Context): MediaDescriptionCompat {
    val coverUri = getCoverUri(ctx)

    var bitmap:Bitmap? = null
    if (coverContentUrl != null) {
      bitmap = if (Build.VERSION.SDK_INT < 28) {
        MediaStore.Images.Media.getBitmap(ctx.contentResolver, coverUri)
      } else {
        val source: ImageDecoder.Source = ImageDecoder.createSource(ctx.contentResolver, coverUri)
        ImageDecoder.decodeBitmap(source)
      }
    }

    val extras = Bundle()
    extras.putLong(
      MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
      MediaDescriptionCompat.STATUS_DOWNLOADED
    )
    if (progress != null) {
      if (progress.isFinished) {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        )
      } else {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        )
        extras.putDouble(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, progress.progress
        )
      }
    } else if (mediaType != "podcast") {
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
      )
    }

    if (media.metadata.explicit) {
      extras.putLong(MediaConstants.METADATA_KEY_IS_EXPLICIT, MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT)
    }

    val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
      .setMediaId(id)
      .setTitle(title)
      .setIconUri(coverUri)
      .setSubtitle(authorName)
      .setExtras(extras)

    bitmap?.let {
      mediaDescriptionBuilder.setIconBitmap(bitmap)
    }

    return mediaDescriptionBuilder.build()
  }
}
