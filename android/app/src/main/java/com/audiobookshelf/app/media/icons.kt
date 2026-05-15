package com.audiobookshelf.app.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.AnyRes
import com.audiobookshelf.app.R

/**
 * get uri to drawable or any other resource type if u wish
 * @param drawableId - drawable res id
 * @return - uri
 */
fun getUriToDrawable(context: Context, @AnyRes drawableId: Int): Uri {
  return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE
    + "://" + context.resources.getResourcePackageName(drawableId)
    + '/' + context.resources.getResourceTypeName(drawableId)
    + '/' + context.resources.getResourceEntryName(drawableId))
}


/**
 * get uri to drawable or any other resource type if u wish
 * @param drawableId - drawable res id
 * @return - uri
 */
fun getUriToAbsIconDrawable(context: Context, absIconName: String): Uri {
  val drawableId = when(absIconName) {
    "audiobookshelf" -> R.drawable.abs_audiobookshelf
    "authors" -> R.drawable.abs_authors
    "book-1" -> R.drawable.abs_book_1
    "books-1" -> R.drawable.abs_books_1
    "books-2" -> R.drawable.abs_books_2
    "columns" -> R.drawable.abs_columns
    "database" -> R.drawable.abs_database
    "file-picture" -> R.drawable.abs_file_picture
    "headphones" -> R.drawable.abs_headphones
    "heart" -> R.drawable.abs_heart
    "microphone_1" -> R.drawable.abs_microphone_1
    "microphone_2" -> R.drawable.abs_microphone_2
    "microphone_3" -> R.drawable.abs_microphone_3
    "music" -> R.drawable.abs_music
    "podcast" -> R.drawable.abs_podcast
    "radio" -> R.drawable.abs_radio
    "rocket" -> R.drawable.abs_rocket
    "rss" -> R.drawable.abs_rss
    "star" -> R.drawable.abs_star
    "library-folder" -> R.drawable.icon_library_folder
    "downloads" -> R.drawable.abs_download_check
    "clock" -> R.drawable.md_clock_outline
    else -> R.drawable.icon_library_folder
  }
  return Uri.parse(
    ContentResolver.SCHEME_ANDROID_RESOURCE
      + "://" + context.resources.getResourcePackageName(drawableId)
      + '/' + context.resources.getResourceTypeName(drawableId)
      + '/' + context.resources.getResourceEntryName(drawableId))
}
