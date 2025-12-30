package com.audiobookshelf.app.player

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.*
import androidx.media3.common.MediaMetadata
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.google.android.gms.cast.*

/** HLS MIME type used by DefaultMediaSourceFactory to create HlsMediaSource */
private const val MIME_TYPE_HLS = "application/x-mpegURL"

/**
 * Adapter functions that produce PlayerMediaItem DTOs from a PlaybackSession.
 * Keeping conversion logic in the player package avoids leaking framework types into the data model.
 */
fun PlaybackSession.toPlayerMediaItems(
  ctx: Context,
  preferServerUrisForCast: Boolean = false
): List<PlayerMediaItem> {
  val mediaItems: MutableList<PlayerMediaItem> = mutableListOf()

  for (audioTrack in this.audioTracks) {
    val useServerUri =
      preferServerUrisForCast && this.isLocal && !this.serverAddress.isNullOrBlank()
    val mediaUri = if (useServerUri) {
      castServerUriForTrack(audioTrack)
    } else {
      this.getContentUri(audioTrack)
    }
    val queueItem = if (useServerUri && mediaUri != null) {
      castQueueItemWithServerUri(audioTrack, mediaUri)
    } else {
      this.getQueueItem(audioTrack) // Queue item used in exo player CastManager
    }
    // Use HLS MIME type for transcoded sessions so DefaultMediaSourceFactory
    // creates an HlsMediaSource instead of a ProgressiveMediaSource
    val mimeType = if (this.isHLS) MIME_TYPE_HLS else audioTrack.mimeType
    val displayTitle = this.displayTitle ?: audioTrack.title

    val safeUri = mediaUri ?: continue
    val playerMediaItem = PlayerMediaItem(
      mediaId = "${this.id}_${audioTrack.stableId}",
      uri = safeUri,
      mimeType = mimeType,
      tag = queueItem,
      title = displayTitle,
      artworkUri = this.getCoverUri(ctx),
      startPositionMs = audioTrack.startOffsetMs
    )
    mediaItems.add(playerMediaItem)
  }
  return mediaItems
}

private fun PlaybackSession.castServerUriForTrack(audioTrack: AudioTrack): Uri? {
  val serverAddr = this.serverAddress ?: return null
  val uriString = if (checkIsServerVersionGte("2.22.0")) {
    if (isDirectPlay) {
      "$serverAddr/public/session/$id/track/${audioTrack.index}"
    } else {
      "$serverAddr${audioTrack.contentUrl}"
    }
  } else {
    "$serverAddr${audioTrack.contentUrl}?token=${DeviceManager.token}"
  }
  return uriString.toUri()
}

private fun PlaybackSession.castQueueItemWithServerUri(
  audioTrack: AudioTrack,
  mediaUri: Uri
): MediaQueueItem {
  val mediaInfo =
    MediaInfo.Builder(mediaUri.toString())
      .apply {
        setContentUrl(mediaUri.toString())
        setContentType(audioTrack.mimeType)
        setMetadata(getCastMediaMetadata(audioTrack))
        setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
      }
      .build()

  return MediaQueueItem.Builder(mediaInfo)
    .apply { setPlaybackDuration(audioTrack.duration) }
    .build()
}

fun PlaybackSession.toMedia3MediaItems(
  ctx: Context,
  preferServerUrisForCast: Boolean = false
): List<MediaItem> {
  return toPlayerMediaItems(ctx, preferServerUrisForCast).map { playerMediaItem ->
    MediaItem.Builder()
      .setUri(playerMediaItem.uri.toString())
      .setMediaId(playerMediaItem.mediaId)
      .setMimeType(playerMediaItem.mimeType)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(displayTitle)
          .setArtist(displayAuthor)
          .setAlbumArtist(displayAuthor)
          .setArtworkUri(playerMediaItem.artworkUri)
          .build()
      )
      .build()
  }
}
