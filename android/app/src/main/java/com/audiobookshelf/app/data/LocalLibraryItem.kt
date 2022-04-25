package com.audiobookshelf.app.data

import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalLibraryItem(
  var id:String,
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
  ) : LibraryItemWrapper() {
  @get:JsonIgnore
  val title get() = media.metadata.title
  @get:JsonIgnore
  val authorName get() = media.metadata.getAuthorDisplayName()
  @get:JsonIgnore
  val isPodcast get() = mediaType == "podcast"

  @JsonIgnore
  fun getCoverUri(): Uri {
    return if (coverContentUrl != null) Uri.parse(coverContentUrl) else Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)
  }

  @JsonIgnore
  fun getDuration():Double {
    var total = 0.0
    var audioTracks = media.getAudioTracks()
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
  fun getPlaybackSession(episode:PodcastEpisode?):PlaybackSession {
    var localEpisodeId = episode?.id
    var sessionId = "play_local_${UUID.randomUUID()}"

    val mediaProgressId = if (localEpisodeId.isNullOrEmpty()) id else "$id-$localEpisodeId"
    var mediaProgress = DeviceManager.dbManager.getLocalMediaProgress(mediaProgressId)
    var currentTime = mediaProgress?.currentTime ?: 0.0

    // TODO: Clean up add mediaType methods for displayTitle and displayAuthor
    var mediaMetadata = media.metadata
    var chapters = if (mediaType == "book") (media as Book).chapters else mutableListOf()
    var audioTracks = media.getAudioTracks() as MutableList<AudioTrack>
    var authorName = mediaMetadata.getAuthorDisplayName()
    if (episode != null) { // Get podcast episode audio track
      episode.audioTrack?.let { at -> mutableListOf(at) }?.let { tracks -> audioTracks = tracks }
      Log.d("LocalLibraryItem", "getPlaybackSession: Got podcast episode audio track ${audioTracks.size}")
    }

    var dateNow = System.currentTimeMillis()
    return PlaybackSession(sessionId,serverUserId,libraryItemId,episode?.serverEpisodeId, mediaType, mediaMetadata, chapters ?: mutableListOf(), mediaMetadata.title, authorName,null,getDuration(),PLAYMETHOD_LOCAL,dateNow,0L,0L, audioTracks,currentTime,null,this,localEpisodeId,serverConnectionConfigId, serverAddress, "exo-player")
  }

  @JsonIgnore
  fun removeLocalFile(localFileId:String) {
    localFiles.removeIf { it.id == localFileId }
  }

  @JsonIgnore
  fun getMediaMetadata(): MediaMetadataCompat {
    return MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
      putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
      putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, authorName)
      putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, getCoverUri().toString())
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getCoverUri().toString())
      putString(MediaMetadataCompat.METADATA_KEY_ART_URI, getCoverUri().toString())
      putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, authorName)
    }.build()
  }
}
