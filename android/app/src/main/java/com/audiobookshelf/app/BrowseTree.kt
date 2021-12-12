package com.audiobookshelf.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import androidx.annotation.AnyRes


class BrowseTree(
  val context: Context,
  val audiobooksInProgress: List<Audiobook>,
  val audiobooks: List<Audiobook>,
  val localAudio: List<LocalMediaManager.LocalAudio>,
  val recentMediaId: String? = null
) {
  private val mediaIdToChildren = mutableMapOf<String, MutableList<MediaMetadataCompat>>()

  /**
   * get uri to drawable or any other resource type if u wish
   * @param context - context
   * @param drawableId - drawable res id
   * @return - uri
   */
  fun getUriToDrawable(context: Context,
                       @AnyRes drawableId: Int): Uri {
    return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE
      + "://" + context.resources.getResourcePackageName(drawableId)
      + '/' + context.resources.getResourceTypeName(drawableId)
      + '/' + context.resources.getResourceEntryName(drawableId))
  }

  init {
    val rootList = mediaIdToChildren[AUTO_BROWSE_ROOT] ?: mutableListOf()


    val continueReadingMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, CONTINUE_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Reading")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.exo_icon_localaudio).toString())
    }.build()

    val allMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ALL_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Audiobooks")

      var resource = getUriToDrawable(context, R.drawable.exo_icon_books).toString()
      Log.d("BrowseTree", "RESOURCE $resource")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, resource)
    }.build()


    val downloadsMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, DOWNLOADS_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Downloads")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.exo_icon_downloaddone).toString())
    }.build()

    val localsMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, LOCAL_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Samples")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.exo_icon_localaudio).toString())
    }.build()

    if (audiobooksInProgress.isNotEmpty()) {
      rootList += continueReadingMetadata
    }
    rootList += allMetadata
    rootList += downloadsMetadata
    rootList += localsMetadata
    mediaIdToChildren[AUTO_BROWSE_ROOT] = rootList

    audiobooksInProgress.forEach { audiobook ->
      val children = mediaIdToChildren[CONTINUE_ROOT] ?: mutableListOf()
      children += audiobook.toMediaMetadata()
      mediaIdToChildren[CONTINUE_ROOT] = children
    }

    audiobooks.forEach { audiobook ->
      if (audiobook.isDownloaded) {
        val downloadsChildren = mediaIdToChildren[DOWNLOADS_ROOT] ?: mutableListOf()
        downloadsChildren += audiobook.toMediaMetadata()
        mediaIdToChildren[DOWNLOADS_ROOT] = downloadsChildren
      }
        val allChildren = mediaIdToChildren[ALL_ROOT] ?: mutableListOf()
      allChildren += audiobook.toMediaMetadata()
        mediaIdToChildren[ALL_ROOT] = allChildren
    }

    localAudio.forEach { local ->
      val localChildren = mediaIdToChildren[LOCAL_ROOT] ?: mutableListOf()
      localChildren += local.toMediaMetadata()
      mediaIdToChildren[LOCAL_ROOT] = localChildren
    }
    Log.d("BrowseTree", "Set LOCAL AUDIO ${localAudio.size}")
  }

  operator fun get(mediaId: String) = mediaIdToChildren[mediaId]
}

const val AUTO_BROWSE_ROOT = "/"
const val ALL_ROOT = "__ALL__"
const val CONTINUE_ROOT = "__CONTINUE__"
const val DOWNLOADS_ROOT = "__DOWNLOADS__"
const val LOCAL_ROOT = "__LOCAL__"
