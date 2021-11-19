package com.audiobookshelf.app

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.util.Log

class LocalMediaManager {
  private var ctx: Context
  val tag = "LocalAudioManager"

  constructor(ctx:Context) {
    this.ctx = ctx
  }

  data class LocalAudio(val uri: Uri,
                        val id:String,
                   val name: String,
                   val duration: Int,
                   val size: Int
  ) {
    fun toMediaMetadata(): MediaMetadataCompat {
      return MediaMetadataCompat.Builder().apply {
        putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, name)
        putString(MediaMetadataCompat.METADATA_KEY_TITLE, name)
//        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, book.authorFL)
//        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, getCover().toString())
        putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, "android.resource://com.audiobookshelf.app/" + R.drawable.icon)
//        putString(MediaMetadataCompat.METADATA_KEY_ART_URI, getCover().toString())
//        putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, book.authorFL)
      }.build()
    }
  }
  val localAudioFiles = mutableListOf<LocalAudio>()

  fun loadLocalAudio() {
    Log.d(tag, "Media store looking for local audio files")

    val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE)
    val audioCursor: Cursor? = ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)

    audioCursor?.use { cursor ->
      // Cache column indices.
      val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
      val nameColumn =
        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
      val durationColumn =
        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
      val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

      while (cursor.moveToNext()) {
        // Get values of columns for a given video.
        val id = cursor.getLong(idColumn)
        val name = cursor.getString(nameColumn)
        val duration = cursor.getInt(durationColumn)
        val size = cursor.getInt(sizeColumn)

        val contentUri: Uri = ContentUris.withAppendedId(
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
          id
        )
        Log.d(tag, "Found local audio file $name")
       localAudioFiles += LocalAudio(contentUri, id.toString(), name, duration, size)
      }
    }

    Log.d(tag, "${localAudioFiles.size} Local Audio Files found")
  }
}
