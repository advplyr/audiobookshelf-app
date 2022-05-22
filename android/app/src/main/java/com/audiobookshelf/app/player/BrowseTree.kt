package com.audiobookshelf.app.player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import androidx.annotation.AnyRes
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.Library
import com.audiobookshelf.app.data.LibraryCategory
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LocalLibraryItem

class BrowseTree(
  val context: Context,
  libraryCategories: List<LibraryCategory>,
  libraries: List<Library>
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
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Listening")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.exo_icon_localaudio).toString())
    }.build()

    val downloadsMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, DOWNLOADS_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Downloads")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.exo_icon_downloaddone).toString())
    }.build()

    val librariesMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, LIBRARIES_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Libraries")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.icon_library_folder).toString())
    }.build()

    // Server continue Listening cat
    libraryCategories.find { it.id == "continue-listening" }?.let { continueListeningCategory ->
      val continueListeningMediaMetadata = continueListeningCategory.entities.map { liw ->
        val libraryItem = liw as LibraryItem
        libraryItem.getMediaMetadata()
      }
      if (continueListeningMediaMetadata.isNotEmpty()) {
        rootList += continueReadingMetadata
      }
      continueListeningMediaMetadata.forEach {
        val children = mediaIdToChildren[CONTINUE_ROOT] ?: mutableListOf()
        children += it
        mediaIdToChildren[CONTINUE_ROOT] = children
      }
    }

    if (libraries.isNotEmpty()) {
      rootList += librariesMetadata

      libraries.forEach { library ->
        val libraryMediaMetadata = library.getMediaMetadata()
        val children = mediaIdToChildren[LIBRARIES_ROOT] ?: mutableListOf()
        children += libraryMediaMetadata
        mediaIdToChildren[LIBRARIES_ROOT] = children
      }
    }

    rootList += downloadsMetadata
    libraryCategories.find { it.id == "local-books" }?.let { localBooksCat ->
       localBooksCat.entities.forEach { libc ->
        val libraryItem = libc as LocalLibraryItem
         val children = mediaIdToChildren[DOWNLOADS_ROOT] ?: mutableListOf()
         children += libraryItem.getMediaMetadata(context)
         mediaIdToChildren[DOWNLOADS_ROOT] = children
      }
    }

    libraryCategories.find { it.id == "local-podcasts" }?.let { localPodcastsCat ->
      localPodcastsCat.entities.forEach { libc ->
        val libraryItem = libc as LocalLibraryItem
        val children = mediaIdToChildren[DOWNLOADS_ROOT] ?: mutableListOf()
        children += libraryItem.getMediaMetadata(context)
        mediaIdToChildren[DOWNLOADS_ROOT] = children
      }
    }

    mediaIdToChildren[AUTO_BROWSE_ROOT] = rootList
  }

  operator fun get(mediaId: String) = mediaIdToChildren[mediaId]
}

const val AUTO_BROWSE_ROOT = "/"
const val CONTINUE_ROOT = "__CONTINUE__"
const val DOWNLOADS_ROOT = "__DOWNLOADS__"
const val LIBRARIES_ROOT = "__LIBRARIES__"
