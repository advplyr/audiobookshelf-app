package com.bookshelf.app.player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import androidx.annotation.AnyRes
import com.bookshelf.app.R
import com.bookshelf.app.data.LibraryCategory
import com.bookshelf.app.data.LibraryItem
import com.bookshelf.app.data.LocalLibraryItem


class BrowseTree(
  val context: Context,
  libraryCategories: List<LibraryCategory>
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

    val allMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ALL_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Library Items")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.exo_icon_books).toString())
    }.build()

    val downloadsMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, DOWNLOADS_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Downloads")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.exo_icon_downloaddone).toString())
    }.build()

    // Server continue Listening cat
    libraryCategories.find { it.id == "continue-listening" }?.let { continueListeningCategory ->
      var continueListeningMediaMetadata = continueListeningCategory.entities.map { liw ->
        var libraryItem = liw as LibraryItem
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

    rootList += allMetadata
    rootList += downloadsMetadata

    // Server library cat
    libraryCategories.find { it.id == "library" }?.let { libraryCategory ->
      var libraryMediaMetadata = libraryCategory.entities.map { libc ->
        var libraryItem = libc as LibraryItem
        libraryItem.getMediaMetadata()
      }
      libraryMediaMetadata.forEach {
        val children = mediaIdToChildren[ALL_ROOT] ?: mutableListOf()
        children += it
        mediaIdToChildren[ALL_ROOT] = children
      }
    }

    libraryCategories.find { it.id == "local-books" }?.let { localBooksCat ->
      var localMediaMetadata = localBooksCat.entities.map { libc ->
        var libraryItem = libc as LocalLibraryItem
        libraryItem.getMediaMetadata()
      }
      localMediaMetadata.forEach {
        val children = mediaIdToChildren[DOWNLOADS_ROOT] ?: mutableListOf()
        children += it
        mediaIdToChildren[DOWNLOADS_ROOT] = children
      }
    }

    mediaIdToChildren[AUTO_BROWSE_ROOT] = rootList
  }

  operator fun get(mediaId: String) = mediaIdToChildren[mediaId]
}

const val AUTO_BROWSE_ROOT = "/"
const val ALL_ROOT = "__ALL__"
const val CONTINUE_ROOT = "__CONTINUE__"
const val DOWNLOADS_ROOT = "__DOWNLOADS__"
