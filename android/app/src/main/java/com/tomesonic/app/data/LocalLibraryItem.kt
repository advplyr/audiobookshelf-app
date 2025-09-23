package com.tomesonic.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.tomesonic.app.BuildConfig
import com.tomesonic.app.R
import com.tomesonic.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.tomesonic.app.player.PLAYMETHOD_LOCAL
import java.io.File
import java.util.*

// Android Auto package names for URI permission granting
private const val ANDROID_AUTO_PKG_NAME = "com.google.android.projection.gearhead"
private const val ANDROID_AUTO_SIMULATOR_PKG_NAME = "com.google.android.projection.gearhead.emulator"
private const val ANDROID_AUTOMOTIVE_PKG_NAME = "com.google.android.projection.gearhead.phone"

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
      val contentUri = FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", Uri.parse(coverContentUrl).toFile())

      // Grant URI permissions to Android Auto packages so they can access the content
      try {
        val androidAutoPackages = arrayOf(
          ANDROID_AUTO_PKG_NAME,
          ANDROID_AUTO_SIMULATOR_PKG_NAME,
          ANDROID_AUTOMOTIVE_PKG_NAME
        )

        for (packageName in androidAutoPackages) {
          ctx.grantUriPermission(packageName, contentUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      } catch (e: Exception) {
        Log.w("LocalLibraryItem", "getCoverUri - Failed to grant URI permissions: ${e.message}")
      }

      return contentUri
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
  fun hasTracks(episode:PodcastEpisode?): Boolean {
    var audioTracks = media.getAudioTracks() as MutableList<AudioTrack>
    if (episode != null) { // Get podcast episode audio track
      episode.audioTrack?.let { at -> mutableListOf(at) }?.let { tracks -> audioTracks = tracks }
    }
    if (audioTracks.size == 0) return false
    audioTracks.forEach {
      // Check that metadata is not null
      if (it.metadata === null) {
        return false
      }
      // Check that file exists
      val file = File(it.metadata!!.path)
      if (!file.exists()) {
        return false
      }
    }
    return true
  }

  @JsonIgnore
  fun getPlaybackSession(episode:PodcastEpisode?, deviceInfo:DeviceInfo):PlaybackSession {
    val localEpisodeId = episode?.id
    val sessionId = "${UUID.randomUUID()}"

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
    }

    val dateNow = System.currentTimeMillis()
    return PlaybackSession(sessionId,serverUserId,libraryItemId,episode?.serverEpisodeId, mediaType, mediaMetadata, deviceInfo,chapters ?: mutableListOf(), displayTitle, authorName,null,duration,PLAYMETHOD_LOCAL,dateNow,0L,0L, audioTracks,currentTime,null,this,localEpisodeId,serverConnectionConfigId, serverAddress, "exo-player")
  }

  @JsonIgnore
  fun removeLocalFile(localFileId:String) {
    localFiles.removeIf { it.id == localFileId }
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx:Context): MediaDescriptionCompat {
    val coverUri = getCoverUri(ctx)

    var bitmap:Bitmap? = null
    if (coverContentUrl != null) {
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
          Log.e("LocalLibraryItem", "Error loading bitmap", e)
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
