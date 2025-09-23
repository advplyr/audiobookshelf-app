package com.tomesonic.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.util.Log
import com.tomesonic.app.R
import com.tomesonic.app.data.*
import com.tomesonic.app.media.getUriToDrawable

class BrowseTree(
  val context: Context,
  itemsInProgress: List<ItemInProgress>,
  libraries: List<Library>,
  recentsLoaded: Boolean
) {
  private val mediaIdToChildren = mutableMapOf<String, MutableList<MediaItem>>()

  init {
    Log.d("BrowseTree", "AABrowser: BrowseTree init: libraries=${libraries.size}, itemsInProgress=${itemsInProgress.size}, recentsLoaded=$recentsLoaded")
    val rootList = mediaIdToChildren[AUTO_BROWSE_ROOT] ?: mutableListOf()

    val continueListeningItem = MediaItem.Builder()
      .setMediaId(CONTINUE_ROOT)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle("Continue")
          .setArtworkUri(getUriToDrawable(context, R.drawable.exo_icon_localaudio))
          .setIsBrowsable(true)
          .setIsPlayable(false)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .build()
      )
      .build()

    val recentItem = MediaItem.Builder()
      .setMediaId(RECENTLY_ROOT)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle("Recent")
          .setArtworkUri(getUriToDrawable(context, R.drawable.md_clock_outline))
          .setIsBrowsable(true)
          .setIsPlayable(false)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .build()
      )
      .build()

    val downloadsItem = MediaItem.Builder()
      .setMediaId(DOWNLOADS_ROOT)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle("Downloads")
          .setArtworkUri(getUriToDrawable(context, R.drawable.exo_icon_downloaddone))
          .setIsBrowsable(true)
          .setIsPlayable(false)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .build()
      )
      .build()

    val librariesItem = MediaItem.Builder()
      .setMediaId(LIBRARIES_ROOT)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle("Libraries")
          .setArtworkUri(getUriToDrawable(context, R.drawable.icon_library_folder))
          .setIsBrowsable(true)
          .setIsPlayable(false)
          .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .build()
      )
      .build()

    if (itemsInProgress.isNotEmpty()) {
      Log.d("BrowseTree", "AABrowser: Adding Continue item")
      rootList += continueListeningItem
    }

    if (libraries.isNotEmpty()) {
      Log.d("BrowseTree", "AABrowser: Adding Libraries and potentially Recent items")
      if (recentsLoaded) {
        Log.d("BrowseTree", "AABrowser: Adding Recent item")
        rootList += recentItem
      }
      rootList += librariesItem

      libraries.forEach { library ->
        // Log library info for debugging
        Log.d("BrowseTree", "AABrowser: Library ${library.name} | ${library.icon} | audioFiles: ${library.stats?.numAudioFiles}")
        // Generate library list items for Libraries menu
        val libraryMediaItem = library.getMediaItem(context)
        val children = mediaIdToChildren[LIBRARIES_ROOT] ?: mutableListOf()
        children += libraryMediaItem
        mediaIdToChildren[LIBRARIES_ROOT] = children

        if (recentsLoaded) {
          // Generate library list items for Recent menu
          val recentlyMediaItem = library.getMediaItem(context, "recently")
          val childrenRecently = mediaIdToChildren[RECENTLY_ROOT] ?: mutableListOf()
          childrenRecently += recentlyMediaItem
          mediaIdToChildren[RECENTLY_ROOT] = childrenRecently
        }
      }
    }

    Log.d("BrowseTree", "AABrowser: Adding Downloads item")
    rootList += downloadsItem

    mediaIdToChildren[AUTO_BROWSE_ROOT] = rootList
    Log.d("BrowseTree", "AABrowser: Final root list has ${rootList.size} items")
  }

  operator fun get(mediaId: String) = mediaIdToChildren[mediaId]
}

const val AUTO_BROWSE_ROOT = "/"
const val CONTINUE_ROOT = "__CONTINUE__"
const val DOWNLOADS_ROOT = "__DOWNLOADS__"
const val LIBRARIES_ROOT = "__LIBRARIES__"
const val RECENTLY_ROOT = "__RECENTLY__"
