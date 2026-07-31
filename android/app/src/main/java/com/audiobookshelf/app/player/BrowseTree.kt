package com.audiobookshelf.app.player

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.media.getUriToDrawable

class BrowseTree(
  val context: Context,
  itemsInProgress: List<ItemInProgress>,
  libraries: List<Library>,
  recentsLoaded: Boolean,
  ttsBooks: List<TTSBookSummary>,
  hasEbooksInProgress: Boolean
) {
  private val mediaIdToChildren = mutableMapOf<String, MutableList<MediaMetadataCompat>>()

  init {
    val rootList = mediaIdToChildren[AUTO_BROWSE_ROOT] ?: mutableListOf()

    val continueListeningMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, CONTINUE_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Continue")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.exo_icon_localaudio).toString())
    }.build()

    val recentMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, RECENTLY_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Recent")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.md_clock_outline).toString())
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

    // Ebooks cached for the read aloud (TTS) player - playable without the WebView
    val ebooksMetadata = MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, EBOOKS_ROOT)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Ebooks")
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getUriToDrawable(context, R.drawable.md_book_open_blank_variant_outline).toString())
    }.build()

    // Shown for audio items in progress and for partially read ebooks in the TTS cache
    if (itemsInProgress.isNotEmpty() || hasEbooksInProgress) {
      rootList += continueListeningMetadata
    }

    if (libraries.isNotEmpty()) {
      if (recentsLoaded) {
        rootList += recentMetadata
      }
      rootList += librariesMetadata

      libraries.forEach { library ->
        // Skip libraries without playable content - audio, or ebooks in book
        // libraries (playable with the read aloud/TTS player)
        val hasAudio = library.stats?.numAudioFiles != 0
        val hasEbooks = library.mediaType == "book" && library.stats?.totalItems != 0
        if (!hasAudio && !hasEbooks) return@forEach
        Log.d("BrowseTree", "Library $library | ${library.icon}")
        // Generate library list items for Libraries menu
        val libraryMediaMetadata = library.getMediaMetadata(context)
        val children = mediaIdToChildren[LIBRARIES_ROOT] ?: mutableListOf()
        children += libraryMediaMetadata
        mediaIdToChildren[LIBRARIES_ROOT] = children

        if (recentsLoaded) {
          // Generate library list items for Recent menu
          val recentlyMediaMetadata = library.getMediaMetadata(context,"recently")
          val childrenRecently = mediaIdToChildren[RECENTLY_ROOT] ?: mutableListOf()
          childrenRecently += recentlyMediaMetadata
          mediaIdToChildren[RECENTLY_ROOT] = childrenRecently
        }
      }
    }

    rootList += downloadsMetadata

    if (ttsBooks.isNotEmpty()) {
      rootList += ebooksMetadata
    }

    mediaIdToChildren[AUTO_BROWSE_ROOT] = rootList
  }

  operator fun get(mediaId: String) = mediaIdToChildren[mediaId]
}

const val AUTO_BROWSE_ROOT = "/"
const val CONTINUE_ROOT = "__CONTINUE__"
const val DOWNLOADS_ROOT = "__DOWNLOADS__"
const val LIBRARIES_ROOT = "__LIBRARIES__"
const val RECENTLY_ROOT = "__RECENTLY__"
const val EBOOKS_ROOT = "__EBOOKS__"

// Media id prefix for cached ebooks played with the read aloud (TTS) player,
// routed to the TTS engine in MediaSessionCallback.onPlayFromMediaId
const val EBOOK_MEDIA_ID_PREFIX = "ebook__"
