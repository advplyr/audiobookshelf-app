package com.audiobookshelf.app.player

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem

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
    val mimeType = audioTrack.mimeType
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
