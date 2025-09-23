package com.tomesonic.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import com.tomesonic.app.BuildConfig
import com.tomesonic.app.R
import com.tomesonic.app.device.DeviceManager
import com.tomesonic.app.media.MediaProgressSyncData
import com.tomesonic.app.player.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import kotlinx.coroutines.*
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
// MIGRATION-DEFERRED: CAST - Commented out for migration
// import com.google.android.gms.cast.MediaInfo
// import com.google.android.gms.cast.MediaQueueItem
// import com.google.android.gms.common.images.WebImage

// Android Auto package names for URI permission granting
private const val ANDROID_AUTO_PKG_NAME = "com.google.android.projection.gearhead"
private const val ANDROID_AUTO_SIMULATOR_PKG_NAME = "com.google.android.projection.gearhead.emulator"
private const val ANDROID_AUTOMOTIVE_PKG_NAME = "com.google.android.projection.gearhead.phone"

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

  companion object {
    // Session is considered expired after 30 minutes of inactivity
    private const val SESSION_EXPIRY_TIME_MS = 30 * 60 * 1000L // 30 minutes
  }

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
  val progress
    get() = currentTime / getTotalDuration()
  @get:JsonIgnore
  val mediaItemId
    get() = if (episodeId.isNullOrEmpty()) libraryItemId ?: "" else "$libraryItemId-$episodeId"

  /**
   * Checks if this session might be expired based on the last update time
   * This is used to determine if we should request a fresh session from the server
   */
  @JsonIgnore
  fun isLikelyExpired(): Boolean {
    // Don't check expiry for local items
    if (isLocal) return false

    val currentTime = System.currentTimeMillis()
    val timeSinceLastUpdate = currentTime - updatedAt

    return timeSinceLastUpdate > SESSION_EXPIRY_TIME_MS
  }

  /**
   * Checks if this session needs to be refreshed for server streaming
   * Returns true for remote sessions that may have expired URLs
   */
  @JsonIgnore
  fun needsSessionRefresh(): Boolean {
    // Only server sessions need refresh, not local
    if (isLocal) return false

    // If the session is likely expired, it needs refresh
    if (isLikelyExpired()) return true

    // If server connection config doesn't match current, needs refresh
    return serverConnectionConfigId != DeviceManager.serverConnectionConfigId
  }

  @JsonIgnore
  fun getCurrentTrackIndex(): Int {
    for (i in 0 until audioTracks.size) {
      val track = audioTracks[i]
      if (currentTimeMs >= track.startOffsetMs && (track.endOffsetMs > currentTimeMs)) {
        return i
      }
    }
    return audioTracks.size - 1
  }

  @JsonIgnore
  fun getNextTrackIndex(): Int {
    for (i in 0 until audioTracks.size) {
      val track = audioTracks[i]
      if (currentTimeMs < track.startOffsetMs) {
        return i
      }
    }
    return audioTracks.size - 1
  }

  @JsonIgnore
  fun getChapterForTime(time: Long): BookChapter? {
    if (chapters.isEmpty()) return null
    return chapters.find { time >= it.startMs && it.endMs > time }
  }

  @JsonIgnore
  fun getCurrentTrackEndTime(): Long {
    val currentTrack = audioTracks[this.getCurrentTrackIndex()]
    return currentTrack.startOffsetMs + currentTrack.durationMs
  }

  @JsonIgnore
  fun getNextChapterForTime(time: Long): BookChapter? {
    if (chapters.isEmpty()) return null
    return chapters.find { time < it.startMs } // First chapter where start time is > then time
  }

  /**
   * Get the chapter index for a given time position
   * Returns -1 if no chapters or time doesn't fall within any chapter
   */
  @JsonIgnore
  fun getChapterIndexForTime(time: Long): Int {
    if (chapters.isEmpty()) return -1
    return chapters.indexOfFirst { time >= it.startMs && it.endMs > time }
  }

  /**
   * Get a chapter by its index
   * Returns null if index is out of bounds
   */
  @JsonIgnore
  fun getChapterByIndex(index: Int): BookChapter? {
    return chapters.getOrNull(index)
  }

  /**
   * Get the previous chapter for a given time position
   * Returns null if no chapters or already at first chapter
   */
  @JsonIgnore
  fun getPreviousChapterForTime(time: Long): BookChapter? {
    if (chapters.isEmpty()) return null
    val currentIndex = getChapterIndexForTime(time)
    if (currentIndex <= 0) return null
    return chapters.getOrNull(currentIndex - 1)
  }

  /**
   * Check if this playback session has chapters
   */
  @JsonIgnore
  fun hasChapters(): Boolean {
    return chapters.isNotEmpty()
  }

  /**
   * Get the duration of a specific chapter in milliseconds
   */
  @JsonIgnore
  fun getChapterDuration(chapterIndex: Int): Long {
    val chapter = getChapterByIndex(chapterIndex) ?: return 0L
    return chapter.endMs - chapter.startMs
  }

  /**
   * Determine the starting track and position based on current playback time
   * This is useful for initializing ChapterAwarePlayer
   */
  @JsonIgnore
  fun getStartingPlaybackInfo(): PlaybackStartInfo {
    val startTrackIndex = getCurrentTrackIndex()
    val startTrackPosition = getCurrentTrackTimeMs()
    val currentChapter = getChapterForTime(currentTimeMs)
    val currentChapterIndex = if (currentChapter != null) getChapterIndexForTime(currentTimeMs) else -1

    return PlaybackStartInfo(
      trackIndex = startTrackIndex,
      trackPositionMs = startTrackPosition,
      absolutePositionMs = currentTimeMs,
      chapterIndex = currentChapterIndex,
      chapter = currentChapter
    )
  }

  /**
   * Data class to hold playback starting information
   */
  data class PlaybackStartInfo(
    val trackIndex: Int,
    val trackPositionMs: Long,
    val absolutePositionMs: Long,
    val chapterIndex: Int,
    val chapter: BookChapter?
  )

  /**
   * Check if the current session might be expired
   * This helps avoid 404 errors when resuming sessions after app restarts
   */
  @JsonIgnore
  fun isSessionExpired(): Boolean {
    // If session ID is empty, consider it expired
    if (id.isEmpty()) {
      Log.d("PlaybackSession", "isSessionExpired: Session ID is empty")
      return true
    }

    // Check if this session was started more than a reasonable time ago
    // For safety, assume sessions might expire after extended periods
    val currentTime = System.currentTimeMillis()
    val sessionAgeHours = (currentTime - startedAt) / (1000 * 60 * 60)

    // Consider sessions older than 6 hours as potentially expired
    // This is conservative to avoid 404 errors on resume
    val isOld = sessionAgeHours > 6
    if (isOld) {
      Log.d("PlaybackSession", "isSessionExpired: Session is ${sessionAgeHours} hours old, considering expired")
    }

    return isOld
  }

  @JsonIgnore
  fun getNextTrackEndTime(): Long {
    val currentTrack = audioTracks[this.getNextTrackIndex()]
    return currentTrack.startOffsetMs + currentTrack.durationMs
  }

  @JsonIgnore
  fun getCurrentTrackTimeMs(): Long {
    val currentTrack = audioTracks[this.getCurrentTrackIndex()]
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
                FileProvider.getUriForFile(
                        ctx,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        coverUri.toFile()
                )

        // Grant URI permissions to Android Auto packages so they can access the content
        try {
          val androidAutoPackages = arrayOf(
            ANDROID_AUTO_PKG_NAME,
            ANDROID_AUTO_SIMULATOR_PKG_NAME,
            ANDROID_AUTOMOTIVE_PKG_NAME
          )

          for (packageName in androidAutoPackages) {
            ctx.grantUriPermission(packageName, coverUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
        } catch (e: Exception) {
          Log.w("PlaybackSession", "getCoverUri - Failed to grant URI permissions: ${e.message}")
        }
      }

      return coverUri
              ?: Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
    }

    if (coverPath == null) {
      return Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
    }

    // As of v2.17.0 token is not needed with cover image requests
    if (checkIsServerVersionGte("2.17.0")) {
      val serverUri = Uri.parse("$serverAddress/api/items/$libraryItemId/cover")
      return serverUri
    }
    val serverUriWithToken = Uri.parse("$serverAddress/api/items/$libraryItemId/cover?token=${DeviceManager.token}")
    return serverUriWithToken
  }

  @JsonIgnore
  fun getContentUri(audioTrack: AudioTrack): Uri {
    Log.d("PlaybackSession", "getContentUri: isLocal=$isLocal, audioTrack.contentUrl=${audioTrack.contentUrl}")

    if (isLocal) {
      val uri = Uri.parse(audioTrack.contentUrl)
      Log.d("PlaybackSession", "getContentUri: Local URI created: $uri")
      return uri
    }

    // As of v2.22.0 tracks use a different endpoint
    // See: https://github.com/advplyr/audiobookshelf/pull/4263
    if (checkIsServerVersionGte("2.22.0")) {
      val token = DeviceManager.token
      val uri = if (isDirectPlay) {
        // Check if we have a valid session ID before using session-based URLs
        if (id.isNotEmpty() && !isSessionExpired()) {
          val publicSessionUri = "$serverAddress/public/session/$id/track/${audioTrack.index}"
          Log.d("PlaybackSession", "getContentUri: Creating public session URI: $publicSessionUri")
          Log.d("PlaybackSession", "getContentUri: Session details - id=$id, trackIndex=${audioTrack.index}, serverAddress=$serverAddress")
          Uri.parse(publicSessionUri)
        } else {
          // Fallback to token-based URI for resumed/expired sessions
          val fallbackUri = if (token.isNotEmpty()) {
            "$serverAddress/api/items/$libraryItemId/file/${audioTrack.index}?token=$token"
          } else {
            "$serverAddress/api/items/$libraryItemId/file/${audioTrack.index}"
          }
          Log.d("PlaybackSession", "getContentUri: Session expired or empty, using fallback token URI: $fallbackUri")
          Uri.parse(fallbackUri)
        }
      } else {
        // Transcode uses HlsRouter on server
        val transcodeUri = "$serverAddress${audioTrack.contentUrl}"
        Log.d("PlaybackSession", "getContentUri: Creating transcode URI: $transcodeUri")
        Uri.parse(transcodeUri)
      }
      Log.d("PlaybackSession", "getContentUri: Server v2.22.0+ URI created: $uri (sessionId: $id, trackIndex: ${audioTrack.index}, isDirectPlay: $isDirectPlay)")
      return uri
    }

    val uri = Uri.parse("$serverAddress${audioTrack.contentUrl}?token=${DeviceManager.token}")
    Log.d("PlaybackSession", "getContentUri: Legacy server URI created: $uri")
    return uri
  }

  /**
   * Gets server content URI for casting, even for downloaded books
   * This ensures downloaded books can be cast using their server URLs
   */
  @JsonIgnore
  fun getServerContentUri(audioTrack: AudioTrack): Uri {
    Log.d("PlaybackSession", "getServerContentUri: generating Cast-compatible server URI")
    Log.d("PlaybackSession", "getServerContentUri: session details - id=$id, serverAddress=$serverAddress, isLocal=$isLocal")
    Log.d("PlaybackSession", "getServerContentUri: audioTrack - index=${audioTrack.index}, contentUrl=${audioTrack.contentUrl}")

    val token = DeviceManager.token
    if (token.isEmpty()) {
      Log.w("PlaybackSession", "getServerContentUri: No token available - Cast may fail")
    }

    // For downloaded books, we need to use the server equivalent
    if (isLocal && localLibraryItem != null && !localLibraryItem!!.libraryItemId.isNullOrEmpty()) {
      // Create a server-based URI using the server library item ID
      val serverLibraryItemId = localLibraryItem!!.libraryItemId!!
      Log.d("PlaybackSession", "getServerContentUri: local item, using serverLibraryItemId=$serverLibraryItemId")

      // For Cast, always use the contentUrl approach with token for reliability
      // The /api/items/{id}/file/{index} endpoint may not be universally supported
      val castUri = if (token.isNotEmpty()) {
        "$serverAddress${audioTrack.contentUrl}?token=$token"
      } else {
        "$serverAddress${audioTrack.contentUrl}"
      }

      Log.d("PlaybackSession", "getServerContentUri: local item Cast URI: $castUri")
      return Uri.parse(castUri)
    }

    // For server items, always use token-based authentication for Cast reliability

    // For Cast, always use the contentUrl approach with token for reliability
    // The /api/items/{id}/file/{index} endpoint may not be universally supported
    val castUri = if (token.isNotEmpty()) {
      "$serverAddress${audioTrack.contentUrl}?token=$token"
    } else {
      "$serverAddress${audioTrack.contentUrl}"
    }

    Log.d("PlaybackSession", "getServerContentUri: server item Cast URI: $castUri")
    return Uri.parse(castUri)
  }

  @JsonIgnore
  fun getMediaMetadataCompat(ctx: Context): MediaMetadataCompat {
    // Helper function to get app icon as bitmap for notification
    fun getAppIconBitmap(context: Context): Bitmap? {
      return try {
        val drawable = context.getDrawable(R.mipmap.ic_launcher)
        drawable?.let {
          val bitmap = Bitmap.createBitmap(
            it.intrinsicWidth,
            it.intrinsicHeight,
            Bitmap.Config.ARGB_8888
          )
          val canvas = android.graphics.Canvas(bitmap)
          it.setBounds(0, 0, canvas.width, canvas.height)
          it.draw(canvas)
          bitmap
        }
      } catch (e: Exception) {
        Log.w("PlaybackSession", "Failed to load app icon: ${e.message}")
        null
      }
    }

    val coverUri = getCoverUri(ctx)
    // Always use book metadata, never track metadata
    val nowPlayingTitle = displayTitle ?: "Audiobook"

    // Use consistent "Book Title • Author" format for subtitles
    val nowPlayingSubtitle = run {
      val title = displayTitle ?: "Audiobook"
      val author = displayAuthor
      if (!author.isNullOrBlank()) "$title • $author" else title
    }

    // Create MediaDescriptionCompat with proper bitmap handling for Android Auto
    val descriptionBuilder = android.support.v4.media.MediaDescriptionCompat.Builder()
      .setMediaId(id)
      .setTitle(nowPlayingTitle)
      .setSubtitle(nowPlayingSubtitle)
      .setDescription(displayAuthor)

    // Handle images differently for local vs server books
    var bitmap: android.graphics.Bitmap? = null
    if (localLibraryItem?.coverContentUrl != null) {
      // Local books: Use bitmap approach for Android Auto compatibility
      // Note: In Android Auto for local cover images, setting the icon uri to a local path does not work (cover is blank)
      // so we create and set the bitmap here instead of letting AbMediaDescriptionAdapter handle it
      try {
        val rawBitmap = if (Build.VERSION.SDK_INT >= 28) {
          val source: ImageDecoder.Source = ImageDecoder.createSource(ctx.contentResolver, coverUri)
          ImageDecoder.decodeBitmap(source) { decoder, info, source ->
            decoder.setTargetSize(512, 512) // Use larger size for testing notification quality
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE) // Ensure high quality
          }
        } else {
          // For API 24-27, use BitmapFactory instead of deprecated getBitmap
          try {
            ctx.contentResolver.openInputStream(coverUri)?.use { inputStream ->
              val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
              }
              BitmapFactory.decodeStream(inputStream, null, options)

              // Calculate inSampleSize for target size of 512px
              options.inSampleSize = calculateInSampleSize(options, 512, 512)
              options.inJustDecodeBounds = false

              ctx.contentResolver.openInputStream(coverUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
              }
            }
          } catch (e: Exception) {
            Log.e("PlaybackSession", "Error loading bitmap", e)
            null
          }
        }

        // Ensure bitmap is exactly 1024x1024 for high quality (larger to combat notification compression)
        bitmap = rawBitmap?.let { nonNullBitmap ->
          if (nonNullBitmap.width != 1024 || nonNullBitmap.height != 1024) {
            // Use Canvas-based scaling for better quality instead of createScaledBitmap
            val scaledBitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(scaledBitmap)
            val paint = android.graphics.Paint().apply {
              isAntiAlias = true
              isFilterBitmap = true
              isDither = false
            }
            val srcRect = android.graphics.Rect(0, 0, nonNullBitmap.width, nonNullBitmap.height)
            val dstRect = android.graphics.Rect(0, 0, 1024, 1024)
            canvas.drawBitmap(nonNullBitmap, srcRect, dstRect, paint)
            nonNullBitmap.recycle() // Free memory
            scaledBitmap
          } else {
            nonNullBitmap
          }
        }
        // Use app icon for description instead of book cover
        val appIconBitmap = getAppIconBitmap(ctx)
        if (appIconBitmap != null) {
          descriptionBuilder.setIconBitmap(appIconBitmap)
        } else {
          val appIconUri = Uri.parse("android.resource://${ctx.packageName}/${R.mipmap.ic_launcher}")
          descriptionBuilder.setIconUri(appIconUri)
        }
      } catch (e: Exception) {
        Log.w("PlaybackSession", "Failed to load bitmap for local book: ${e.message}")
        // Fallback to app icon
        val appIconBitmap = getAppIconBitmap(ctx)
        if (appIconBitmap != null) {
          descriptionBuilder.setIconBitmap(appIconBitmap)
        } else {
          val appIconUri = Uri.parse("android.resource://${ctx.packageName}/${R.mipmap.ic_launcher}")
          descriptionBuilder.setIconUri(appIconUri)
        }
      }
    } else {
      // Server books: Use app icon instead of server URI
      Log.d("PlaybackSession", "Server book - using app icon for notification")
      val appIconBitmap = getAppIconBitmap(ctx)
      if (appIconBitmap != null) {
        descriptionBuilder.setIconBitmap(appIconBitmap)
      } else {
        val appIconUri = Uri.parse("android.resource://${ctx.packageName}/${R.mipmap.ic_launcher}")
        descriptionBuilder.setIconUri(appIconUri)
      }
    }

    val description = descriptionBuilder.build()

    val metadataBuilder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, nowPlayingTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, nowPlayingTitle)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, nowPlayingSubtitle)
      .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, nowPlayingSubtitle) // Use "Book Title • Author" format
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, nowPlayingSubtitle) // Use "Book Title • Author" format
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, displayAuthor)
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
      // Set the total book duration for proper Android Auto progress indicators
      .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, totalDurationMs)

    // Set the description with proper bitmap/URI handling
    metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, description.mediaId)

    // Use app icon for notification instead of book cover
    val appIconBitmap = getAppIconBitmap(ctx)
    if (appIconBitmap != null) {
      metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, appIconBitmap)
      metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, appIconBitmap)
    } else {
      // Fallback to vector drawable resource
      val appIconUri = Uri.parse("android.resource://${ctx.packageName}/${R.mipmap.ic_launcher}")
      metadataBuilder
        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, appIconUri.toString())
        .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, appIconUri.toString())
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, appIconUri.toString())
    }

    val metadata = metadataBuilder.build()

    // Use reflection to set the description with iconBitmap for Android Auto compatibility
    try {
      val descriptionField = MediaMetadataCompat::class.java.getDeclaredField("mDescription")
      descriptionField.isAccessible = true
      descriptionField.set(metadata, description)
    } catch (e: Exception) {
      Log.w("PlaybackSession", "Failed to set description with iconBitmap: ${e.message}")
    }

    return metadata
  }

  @JsonIgnore
  fun getExoMediaMetadata(ctx: Context, audioTrack: AudioTrack? = null, chapter: BookChapter? = null, chapterIndex: Int = -1): MediaMetadata {
    val coverUri = getCoverUri(ctx)

    // Always prioritize book metadata over embedded track metadata
    val titleToUse = when {
      chapter != null -> chapter.title ?: "Chapter ${chapterIndex + 1}"
      else -> displayTitle ?: "Audiobook"
    }

    // Use consistent "Book Title • Author" format for subtitles
    val subtitleToUse = run {
      val title = displayTitle ?: "Audiobook"
      val author = displayAuthor
      if (!author.isNullOrBlank()) "$title • $author" else title
    }

    val metadataBuilder =
            MediaMetadata.Builder()
                    .setTitle(titleToUse)
                    .setDisplayTitle(titleToUse)
                    .setArtist(subtitleToUse) // Use the same "Book Title • Author" format for artist
                    .setAlbumArtist(subtitleToUse) // Use the same format for album artist too
                    .setSubtitle(subtitleToUse)
                    .setAlbumTitle(displayAuthor)
                    .setDescription(displayAuthor)
                    .setArtworkUri(coverUri) // Media3 BitmapLoader will handle automatic loading
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)

    // Note: Media3 1.8.0+ automatically handles artwork transmission to Bluetooth devices
    // via AVRCP when MediaSession is properly configured. The BitmapLoader will load
    // artwork from the URI and transmit it. Success depends on car's AVRCP version (1.6+ required).

    return metadataBuilder.build()
  }

  // MIGRATION-DEFERRED: CAST - Commented out Cast-related methods
  /*
  @JsonIgnore
  fun getCastMediaMetadata(audioTrack: AudioTrack, chapter: BookChapter? = null, chapterIndex: Int = -1): com.google.android.gms.cast.MediaMetadata {
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

    val titleToUse = chapter?.title ?: displayTitle ?: "Audiobook"
    val chapterTitleToUse = if (chapter != null) chapter.title ?: "Chapter ${chapterIndex + 1}" else displayTitle ?: "Audiobook"

    castMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, titleToUse)
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
            chapterTitleToUse
    )

    castMetadata.putInt(
            com.google.android.gms.cast.MediaMetadata.KEY_TRACK_NUMBER,
            chapterIndex + 1
    )
    return castMetadata
  }

  fun getCastMediaMetadata(audioTrack: AudioTrack): com.google.android.gms.cast.MediaMetadata {
    return getCastMediaMetadata(audioTrack, null, -1)
  }
  */

  // MIGRATION-DEFERRED: CAST - Commented out Cast-related methods
  /*
  @JsonIgnore
  fun getQueueItem(audioTrack: AudioTrack, chapter: BookChapter? = null, chapterIndex: Int = -1): MediaQueueItem {
    val castMetadata = if (chapter != null) {
      getCastMediaMetadata(audioTrack, chapter, chapterIndex)
    } else {
      getCastMediaMetadata(audioTrack)
    }

    val mediaUri = getContentUri(audioTrack)

    val mediaInfo =
            MediaInfo.Builder(mediaUri.toString())
                    .apply {
                      setContentUrl(mediaUri.toString())
                      setContentType(audioTrack.mimeType)
                      setMetadata(castMetadata)
                      setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    }
                    .build()

    return MediaQueueItem.Builder(mediaInfo)
            .apply { setPlaybackDuration(audioTrack.duration) }
            .build()
  }

  fun getQueueItem(audioTrack: AudioTrack): MediaQueueItem {
    return getQueueItem(audioTrack, null, -1)
  }
  */

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
    currentTime = syncData.currentTime
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
            false,
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

  /**
   * Creates Media3 compatible MediaMetadata for cast player
   * This replaces the old getCastMediaMetadata method for Media3
   */
  @JsonIgnore
  fun createCastMediaMetadata(
    track: AudioTrack,
    chapter: BookChapter? = null,
    chapterIndex: Int = -1
  ): androidx.media3.common.MediaMetadata {
    val titleToUse = chapter?.title ?: displayTitle ?: "Audiobook"
    val chapterTitleToUse = if (chapter != null) {
      chapter.title ?: "Chapter ${chapterIndex + 1}"
    } else {
      displayTitle ?: "Audiobook"
    }

    // Calculate the duration for this specific chapter/segment for cast
    val durationMs = if (chapter != null) {
      chapter.endMs - chapter.startMs
    } else {
      // For books without chapters, use track duration
      (track.duration * 1000).toLong()
    }

    // Create artwork URI for cast - always include token for maximum compatibility
    val artworkUri = if (coverPath != null) {
      val token = DeviceManager.token
      if (token.isNotEmpty()) {
        Uri.parse("$serverAddress/api/items/$libraryItemId/cover?token=$token")
      } else {
        Uri.parse("$serverAddress/api/items/$libraryItemId/cover")
      }
    } else null

    val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
      .setTitle(titleToUse)
      .setArtist(displayAuthor ?: "")
      .setAlbumTitle(displayTitle ?: "")
      .setDisplayTitle(chapterTitleToUse)
      .setTrackNumber(if (chapterIndex >= 0) chapterIndex + 1 else null)
      .setTotalTrackCount(if (chapters.isNotEmpty()) chapters.size else audioTracks.size)
      .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC)
      .setArtworkUri(artworkUri)

    // Set explicit duration for cast receivers
    if (durationMs > 0) {
      // Create extras bundle with duration and chapter info
      val extras = android.os.Bundle().apply {
        putLong("duration_ms", durationMs)
        putLong("media_duration_ms", durationMs) // Alternative key that cast receivers might use

        if (chapter != null) {
          putString("chapter_title", chapter.title ?: "")
          putLong("chapter_start_ms", chapter.startMs)
          putLong("chapter_end_ms", chapter.endMs)
          putLong("chapter_duration_ms", durationMs)
          putInt("chapter_index", chapterIndex)
          putBoolean("supports_chapter_navigation", true)
        }

        // Add audiobook-specific metadata for cast controls
        putBoolean("supports_skip_forward", true)
        putBoolean("supports_skip_backward", true)
        putInt("skip_forward_ms", 30000) // 30 seconds
        putInt("skip_backward_ms", 10000) // 10 seconds
        putString("media_type", "audiobook")
        putBoolean("supports_speed_control", true)

        // Add library ID for Media Browse API support in Cast receiver
        if (libraryItem?.libraryId != null) {
          putString("libraryId", libraryItem!!.libraryId)
        } else {
          // For local items, we might not have a library ID
          putString("libraryId", "")
        }
      }
      metadataBuilder.setExtras(extras)
    }

    return metadataBuilder.build()
  }

  // Helper function to calculate sample size for efficient bitmap loading
  private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
      val halfHeight: Int = height / 2
      val halfWidth: Int = width / 2

      while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
        inSampleSize *= 2
      }
    }
    return inSampleSize
  }
}
