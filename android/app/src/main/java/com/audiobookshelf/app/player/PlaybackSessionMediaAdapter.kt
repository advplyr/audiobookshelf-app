package com.audiobookshelf.app.player

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.*
import androidx.media3.common.MediaMetadata
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.media3.coverUriToArtworkData
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

/**
 * Per-track label for a multi-track session, or null for single-track sessions. Falls back to
 * "Part N" when the title is missing, echoes the book title, or looks like a filename ("track_001").
 */
fun PlaybackSession.trackLabelForIndex(index: Int): String? {
  if (audioTracks.size <= 1) return null
  val bookTitle = displayTitle ?: ""
  val t = audioTracks.getOrNull(index)?.title
  return if (!t.isNullOrEmpty() && !t.equals(bookTitle, ignoreCase = true) && !t.contains("_")) t
  else "Part ${index + 1}"
}

fun PlaybackSession.artistLineForTrack(index: Int): String {
  val author = displayAuthor ?: ""
  val trackLabel = trackLabelForIndex(index)
  return if (trackLabel != null) "$trackLabel • $author" else author
}

/** Cover artwork bytes for a local item, or null for server items / unreadable covers. */
private fun PlaybackSession.localCoverArtworkData(ctx: Context): ByteArray? {
  if (!isLocal) return null
  val coverUri = getCoverUri(ctx)
  if (coverUri.scheme != "content") return null
  return coverUriToArtworkData(coverUri, ctx, size = 512, quality = 85)
}

fun PlaybackSession.toMedia3MediaItems(
  ctx: Context,
  preferServerUrisForCast: Boolean = false
): List<MediaItem> {
  val playerMediaItems = toPlayerMediaItems(ctx, preferServerUrisForCast)

  // Decoded once per session, then reused for every track below.
  val localArtworkData = localCoverArtworkData(ctx)

  return playerMediaItems.mapIndexed { index, playerMediaItem ->
    val audioTrack = audioTracks.getOrNull(index)
    val bookTitle = displayTitle ?: ""
    val author = displayAuthor ?: ""

    val metadataBuilder = MediaMetadata.Builder()
      .setTitle(bookTitle)
      .setArtist(artistLineForTrack(index))
      .setAlbumTitle(bookTitle)
      .setAlbumArtist(author)
      .setArtworkUri(playerMediaItem.artworkUri)
      .setTrackNumber(index + 1)
      .setTotalTrackCount(audioTracks.size)
      .setDurationMs(audioTrack?.durationMs ?: C.TIME_UNSET)
      .setIsPlayable(true)
      .setIsBrowsable(false)
      .setMediaType(
        if (isPodcastEpisode) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
        else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
      )

    if (localArtworkData != null) {
      metadataBuilder.setArtworkData(localArtworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    }

    MediaItem.Builder()
      .setUri(playerMediaItem.uri.toString())
      .setMediaId(playerMediaItem.mediaId)
      .setMimeType(playerMediaItem.mimeType)
      .setMediaMetadata(metadataBuilder.build())
      .build()
  }
}
