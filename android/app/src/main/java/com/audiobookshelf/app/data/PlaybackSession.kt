package com.audiobookshelf.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.player.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@JsonIgnoreProperties(ignoreUnknown = true)
class PlaybackSession(
        var id: String,
        var userId: String?,
        var libraryItemId: String?,
        var episodeId: String?,
        var mediaType: String,
        var mediaMetadata: MediaTypeMetadata,
        var deviceInfo: DeviceInfo,
        var chapters: List<BookChapter>,
        var displayTitle: String?,
        var displayAuthor: String?,
        var coverPath: String?,
        var duration: Double,
        var playMethod: Int,
        var startedAt: Long,
        var updatedAt: Long,
        var timeListening: Long,
        var audioTracks: MutableList<AudioTrack>,
        var currentTime: Double,
        var libraryItem: LibraryItem?,
        var localLibraryItem: LocalLibraryItem?,
        var localEpisodeId: String?,
        var serverConnectionConfigId: String?,
        var serverAddress: String?,
        var mediaPlayer: String?
) {

  @get:JsonIgnore
  val isHLS
    get() = playMethod == PLAYMETHOD_TRANSCODE
  @get:JsonIgnore
  val isDirectPlay
    get() = playMethod == PLAYMETHOD_DIRECTPLAY
  @get:JsonIgnore
  val isLocal
    get() = playMethod == PLAYMETHOD_LOCAL
  @get:JsonIgnore
  val isPodcastEpisode
    get() = mediaType == "podcast"
  @get:JsonIgnore
  val currentTimeMs
    get() = (currentTime * 1000L).toLong()
  @get:JsonIgnore
  val totalDurationMs
    get() = (getTotalDuration() * 1000L).toLong()
  @get:JsonIgnore
  val localLibraryItemId
    get() = localLibraryItem?.id ?: ""
  @get:JsonIgnore
  val localMediaProgressId
    get() =
            if (localEpisodeId.isNullOrEmpty()) localLibraryItemId
            else "$localLibraryItemId-$localEpisodeId"
  @get:JsonIgnore
  val progress: Double
    get() {
      val totalDuration = getTotalDuration()
      if (totalDuration <= 0.0) {
        // MediaProgressSyncer.kt:241,340 checks progress.isNaN() to detect a session with no
        // usable duration and skip syncing it. A zero currentTime against a zero duration
        // preserves that NaN sentinel; any other currentTime has no meaningful fraction, so it
        // reports the neutral 0.0 instead of the unclamped Infinity a raw division would give.
        return if (currentTime == 0.0) Double.NaN else 0.0
      }
      if (!currentTime.isFinite()) return 0.0
      return (currentTime / totalDuration).coerceIn(0.0, 1.0)
    }
  @get:JsonIgnore
  val mediaItemId
    get() = if (episodeId.isNullOrEmpty()) libraryItemId ?: "" else "$libraryItemId-$episodeId"

  @JsonIgnore
  fun getCurrentTrackIndex(): Int {
    for (i in 0 until audioTracks.size) {
      val track = audioTracks[i]
      if (currentTimeMs >= track.startOffsetMs && (track.endOffsetMs > currentTimeMs)) {
        return i
      }
    }
    // Falling through means the position is outside every track's range, which happens in two
    // opposite situations that must not get the same answer: *before* the first track starts
    // (a negative position, seen after a bad seek or a restored session), and *at or after* the
    // last track's end. Returning the last index unconditionally sent a negative position to the
    // final track, while getNextTrackIndex answered 0 for the same input - the two disagreed.
    if (audioTracks.isNotEmpty() && currentTimeMs < audioTracks[0].startOffsetMs) return 0
    // -1 for an empty list, which every caller then uses to index audioTracks. Sibling
    // getTrackStartOffsetMs already guards exactly this (index < 0 || index >= size -> 0L).
    return (audioTracks.size - 1).coerceAtLeast(0)
  }

  @JsonIgnore
  fun getNextTrackIndex(): Int {
    for (i in 0 until audioTracks.size) {
      val track = audioTracks[i]
      if (currentTimeMs < track.startOffsetMs) {
        return i
      }
    }
    return (audioTracks.size - 1).coerceAtLeast(0)
  }

  @JsonIgnore
  fun getChapterForTime(time: Long): BookChapter? {
    if (chapters.isEmpty()) return null
    return chapters.find { time >= it.startMs && it.endMs > time }
  }

  @JsonIgnore
  fun getCurrentTrackEndTime(): Long {
    val currentTrack = audioTracks.getOrNull(this.getCurrentTrackIndex()) ?: return 0L
    return currentTrack.startOffsetMs + currentTrack.durationMs
  }

  @JsonIgnore
  fun getNextChapterForTime(time: Long): BookChapter? {
    if (chapters.isEmpty()) return null
    return chapters.find { time < it.startMs } // First chapter where start time is > then time
  }

  @JsonIgnore
  fun getNextTrackEndTime(): Long {
    val currentTrack = audioTracks.getOrNull(this.getNextTrackIndex()) ?: return 0L
    return currentTrack.startOffsetMs + currentTrack.durationMs
  }

  @JsonIgnore
  fun getCurrentTrackTimeMs(): Long {
    val currentTrack = audioTracks.getOrNull(this.getCurrentTrackIndex()) ?: return 0L
    val time = currentTime - currentTrack.startOffset
    return (time * 1000L).toLong()
  }

  @JsonIgnore
  fun getTrackStartOffsetMs(index: Int): Long {
    if (index < 0 || index >= audioTracks.size) return 0L
    val currentTrack = audioTracks[index]
    return (currentTrack.startOffset * 1000L).toLong()
  }

  @JsonIgnore
  fun getTotalDuration(): Double {
    var total = 0.0
    audioTracks.forEach { total += it.duration }
    return total
  }

  @JsonIgnore
  fun checkIsServerVersionGte(compareVersion: String): Boolean {
    // Safety check this playback session is the same one currently connected (should always be)
    if (DeviceManager.serverConnectionConfigId != serverConnectionConfigId) {
      return false
    }

    return DeviceManager.isServerVersionGreaterThanOrEqualTo(compareVersion)
  }

  @JsonIgnore
  fun getCoverUri(ctx: Context): Uri {
    if (localLibraryItem?.coverContentUrl != null) {
      var coverUri = Uri.parse(localLibraryItem?.coverContentUrl.toString())
      if (coverUri.toString().startsWith("file:")) {
        coverUri =
                try {
                  FileProvider.getUriForFile(
                          ctx,
                          "${BuildConfig.APPLICATION_ID}.fileprovider",
                          coverUri.toFile()
                  )
                } catch (e: Exception) {
                  // A cover recorded with a file: path FileProvider isn't configured to serve
                  // (moved storage, SD card removed) throws IllegalArgumentException here on a
                  // real device.
                  return Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
                }
      }

      return coverUri
    }

    if (coverPath == null)
            return Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)

    // As of v2.17.0 token is not needed with cover image requests
    if (checkIsServerVersionGte("2.17.0")) {
      return Uri.parse("$serverAddress/api/items/$libraryItemId/cover")
    }
    return Uri.parse("$serverAddress/api/items/$libraryItemId/cover?token=${DeviceManager.token}")
  }

  @JsonIgnore
  fun getContentUri(audioTrack: AudioTrack): Uri {
    if (isLocal) return Uri.parse(audioTrack.contentUrl) // Local content url
    // As of v2.22.0 tracks use a different endpoint
    // See: https://github.com/advplyr/audiobookshelf/pull/4263
    if (checkIsServerVersionGte("2.22.0")) {
      return if (isDirectPlay) {
        Uri.parse("$serverAddress/public/session/$id/track/${audioTrack.index}")
      } else {
        // Transcode uses HlsRouter on server
        Uri.parse("$serverAddress${audioTrack.contentUrl}")
      }
    }
    return Uri.parse("$serverAddress${audioTrack.contentUrl}?token=${DeviceManager.token}")
  }

  /** Bitmap for the cover art, once resolved */
  @JsonIgnore
  private var resolvedCoverBitmap: Bitmap? = null

  /**
   * Builds the current session metadata, including the cover art bitmap if it has already been resolved.
   */
  @JsonIgnore
  fun getMediaMetadataCompat(ctx: Context): MediaMetadataCompat {
    val coverUri = getCoverUri(ctx)

    val metadataBuilder =
            MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
                    .putString(
                            MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI,
                            coverUri.toString()
                    )

    if (resolvedCoverBitmap != null) {
      metadataBuilder
        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, resolvedCoverBitmap)
        .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, resolvedCoverBitmap)
    } else {
      metadataBuilder
        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, coverUri.toString())
        .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, coverUri.toString())
    }

    return metadataBuilder.build()
  }

  /**
   * Resolves the cover art bitmap (local covers are decoded synchronously, server-side covers
   * are fetched asynchronously) and calls `onArtResolved` once it's available
   *
   * Returns the Job for the async fetch, or `null` if the bitmap was resolved synchronously.
   */
  @JsonIgnore
  fun resolveCoverBitmapAsync(
          ctx: Context,
          coroutineScope: CoroutineScope,
          onArtResolved: () -> Unit
  ): Job? {
    val coverUri = getCoverUri(ctx)

    // Local covers get bitmap synchronously, no async fetch needed
    if (localLibraryItem?.coverContentUrl != null) {
      resolvedCoverBitmap =
              try {
                if (Build.VERSION.SDK_INT < 28) {
                  MediaStore.Images.Media.getBitmap(ctx.contentResolver, coverUri)
                } else {
                  val source: ImageDecoder.Source =
                          ImageDecoder.createSource(ctx.contentResolver, coverUri)
                  ImageDecoder.decodeBitmap(source)
                }
              } catch (e: Exception) {
                // Cover on disk is a record but not decodable as an image. onArtResolved still
                // has to fire below - otherwise the UI waits forever for art that will never
                // arrive, trading a crash for a spinner that never clears.
                null
              }
      onArtResolved()
      return null
    }

    // Server-side cover: resolve the art bitmap async
    return coroutineScope.launch {
      val bitmap = resolveUriAsBitmap(ctx, coverUri)
      bitmap?.let {
        resolvedCoverBitmap = it
        onArtResolved()
      }
    }
  }

  @JsonIgnore
  fun getExoMediaMetadata(ctx: Context): MediaMetadata {
    val coverUri = getCoverUri(ctx)

    val metadataBuilder =
            MediaMetadata.Builder()
                    .setTitle(displayTitle)
                    .setDisplayTitle(displayTitle)
                    .setArtist(displayAuthor)
                    .setAlbumArtist(displayAuthor)
                    .setSubtitle(displayAuthor)
                    .setAlbumTitle(displayAuthor)
                    .setDescription(displayAuthor)
                    .setArtworkUri(coverUri)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)

    return metadataBuilder.build()
  }

  @JsonIgnore
  fun getMediaItems(ctx: Context): List<MediaItem> {
    val mediaItems: MutableList<MediaItem> = mutableListOf()

    for (audioTrack in audioTracks) {
      val mediaMetadata = this.getExoMediaMetadata(ctx)
      val mediaUri = this.getContentUri(audioTrack)
      val mimeType = audioTrack.mimeType

      val queueItem = getQueueItem(audioTrack) // Queue item used in exo player CastManager
      val mediaItem =
              MediaItem.Builder()
                      .setUri(mediaUri)
                      .setTag(queueItem)
                      .setMediaMetadata(mediaMetadata)
                      .setMimeType(mimeType)
                      .build()
      mediaItems.add(mediaItem)
    }
    return mediaItems
  }

  @JsonIgnore
  fun getCastMediaMetadata(audioTrack: AudioTrack): com.google.android.gms.cast.MediaMetadata {
    val castMetadata =
            com.google.android.gms.cast.MediaMetadata(
                    com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_AUDIOBOOK_CHAPTER
            )

    // As of v2.17.0 token is not needed with cover image requests
    val coverUri = if (checkIsServerVersionGte("2.17.0")) {
      Uri.parse("$serverAddress/api/items/$libraryItemId/cover")
    } else {
      Uri.parse("$serverAddress/api/items/$libraryItemId/cover?token=${DeviceManager.token}")
    }

    // Cast always uses server cover uri
    coverPath?.let {
      castMetadata.addImage(WebImage(coverUri))
    }

    castMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, displayTitle ?: "")
    castMetadata.putString(
            com.google.android.gms.cast.MediaMetadata.KEY_ARTIST,
            displayAuthor ?: ""
    )
    castMetadata.putString(
            com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE,
            displayAuthor ?: ""
    )
    castMetadata.putString(
            com.google.android.gms.cast.MediaMetadata.KEY_CHAPTER_TITLE,
            audioTrack.title
    )

    castMetadata.putInt(
            com.google.android.gms.cast.MediaMetadata.KEY_TRACK_NUMBER,
            audioTrack.index
    )
    return castMetadata
  }

  @JsonIgnore
  fun getQueueItem(audioTrack: AudioTrack): MediaQueueItem {
    val castMetadata = getCastMediaMetadata(audioTrack)

    val mediaUri = getContentUri(audioTrack)

    val mediaInfo =
            MediaInfo.Builder(mediaUri.toString())
                    .apply {
                      setContentUrl(mediaUri.toString())
                      setContentType(audioTrack.mimeType ?: "")
                      setMetadata(castMetadata)
                      setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    }
                    .build()

    return MediaQueueItem.Builder(mediaInfo)
            .apply { setPlaybackDuration(audioTrack.duration) }
            .build()
  }

  @JsonIgnore
  fun clone(): PlaybackSession {
    return PlaybackSession(
            id,
            userId,
            libraryItemId,
            episodeId,
            mediaType,
            mediaMetadata,
            deviceInfo,
            chapters,
            displayTitle,
            displayAuthor,
            coverPath,
            duration,
            playMethod,
            startedAt,
            updatedAt,
            timeListening,
            audioTracks,
            currentTime,
            libraryItem,
            localLibraryItem,
            localEpisodeId,
            serverConnectionConfigId,
            serverAddress,
            mediaPlayer
    )
  }

  @JsonIgnore
  fun syncData(syncData: MediaProgressSyncData) {
    timeListening += syncData.timeListened
    updatedAt = System.currentTimeMillis()
    // The media session, the player listener and the 15-second sync timer can each drive a save,
    // and none of them is serialised against the others - a delayed callback carrying an earlier
    // position must not overwrite a later one already recorded. Same missing comparison as the
    // progress-conflict cluster, reached through the external-control path instead of a stale
    // session object.
    if (syncData.currentTime >= currentTime) {
      currentTime = syncData.currentTime
    }
  }

  @JsonIgnore
  fun getNewLocalMediaProgress(): LocalMediaProgress {
    return LocalMediaProgress(
            localMediaProgressId,
            localLibraryItemId,
            localEpisodeId,
            getTotalDuration(),
            progress,
            currentTime,
            progress >= 0.99,
            null,
            null,
            updatedAt,
            startedAt,
            null,
            serverConnectionConfigId,
            serverAddress,
            userId,
            libraryItemId,
            episodeId
    )
  }
}
