package com.audiobookshelf.app

import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import com.getcapacitor.JSObject
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.util.MimeTypes
import java.lang.Exception

class AudiobookStreamData {
  var id:String = "unset"
  var audiobookId:String = ""
  var token:String = ""
  var playlistUrl:String = ""
  var title:String = "No Title"
  var author:String = "Unknown"
  var series:String = ""
  var cover:String = ""
  var playWhenReady:Boolean = false
  var startTime:Long = 0
  var playbackSpeed:Float = 1f
  var duration:Long = 0
  var tracks:MutableList<String> = mutableListOf()

  var isLocal:Boolean = false
  var contentUrl:String = ""

  var hasPlayerLoaded:Boolean = false

  var playlistUri:Uri = Uri.EMPTY
  var coverUri:Uri = Uri.EMPTY
  var contentUri:Uri = Uri.EMPTY // For Local only

  constructor(jsondata:JSObject) {
    id = jsondata.getString("id", "unset").toString()
    audiobookId = jsondata.getString("audiobookId", "").toString()
    title = jsondata.getString("title", "No Title").toString()
    token = jsondata.getString("token", "").toString()
    author = jsondata.getString("author", "Unknown").toString()
    series = jsondata.getString("series", "").toString()
    cover = jsondata.getString("cover", "").toString()
    playlistUrl = jsondata.getString("playlistUrl", "").toString()
    playWhenReady = jsondata.getBoolean("playWhenReady", false) == true

    if (jsondata.has("startTime")) {
      startTime = jsondata.getString("startTime", "0")!!.toLong()
    }

    if (jsondata.has("duration")) {
      duration = jsondata.getString("duration", "0")!!.toLong()
    }

    if (jsondata.has("playbackSpeed")) {
      playbackSpeed = jsondata.getDouble("playbackSpeed")!!.toFloat()
    }


    // Local data
    isLocal = jsondata.getBoolean("isLocal", false) == true
    contentUrl = jsondata.getString("contentUrl", "").toString()

    if (playlistUrl != "") {
      playlistUri = Uri.parse(playlistUrl)
    }
    if (cover != "" && cover != null) {
      coverUri = Uri.parse(cover)
    } else {
      coverUri = Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)
      cover = coverUri.toString()
    }

    if (contentUrl != "") {
      contentUri = Uri.parse(contentUrl)
    }

    // Tracks for cast
    try {
      var tracksTest = jsondata.getJSONArray("tracks")
      Log.d("AudiobookStreamData", "Load tracks from json array ${tracksTest.length()}")
      for (i in 0 until tracksTest.length()) {
        var track = tracksTest.get(i)
        Log.d("AudiobookStreamData", "Extracting track $track")
        tracks.add(track as String)
      }
    } catch(e:Exception) {
      Log.d("AudiobookStreamData", "No tracks found $e")
    }
  }

  fun clearCover() {
    coverUri = Uri.EMPTY
    cover = ""
  }

  fun getMediaMetadataCompat():MediaMetadataCompat {
    var metadataBuilder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, author)
      .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, author)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, series)
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)

//    if (cover != "") {
//      metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, cover)
//      metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, cover)
//    }
    return metadataBuilder.build()
  }

  fun getMediaMetadata():MediaMetadata {
    var metadataBuilder = MediaMetadata.Builder()
      .setTitle(title)
      .setDisplayTitle(title)
      .setArtist(author)
      .setAlbumArtist(author)
      .setSubtitle(author)

//    if (coverUri != Uri.EMPTY) {
//      metadataBuilder.setArtworkUri(coverUri)
//    }
    if (playlistUri != Uri.EMPTY) {
      metadataBuilder.setMediaUri(playlistUri)
    }
    if (contentUri != Uri.EMPTY) {
      metadataBuilder.setMediaUri(contentUri)
    }
    return metadataBuilder.build()
  }

  fun getMimeType():String {
    return if (isLocal) {
      MimeTypes.BASE_TYPE_AUDIO
    } else {
      MimeTypes.APPLICATION_M3U8
    }
  }

  fun getMediaUri():Uri {
    return if (isLocal) {
      contentUri
    } else {
      Uri.parse("$playlistUrl?token=$token")
    }
  }

  fun getCastQueue():ArrayList<MediaItem> {
    var mediaQueue: java.util.ArrayList<MediaItem> = java.util.ArrayList<MediaItem>()

   for (i in 0 until tracks.size) {
     var track = tracks[i]
     var metadataBuilder = MediaMetadata.Builder()
       .setTitle(title)
       .setDisplayTitle(title)
       .setArtist(author)
       .setAlbumArtist(author)
       .setSubtitle(author)
       .setTrackNumber(i + 1)

     if (coverUri != Uri.EMPTY) {
       metadataBuilder.setArtworkUri(coverUri)
     }

     var mimeType = MimeTypes.BASE_TYPE_AUDIO

     var mediaMetadata = metadataBuilder.build()
     var mediaItem = MediaItem.Builder().setUri(Uri.parse(track)).setMediaMetadata(mediaMetadata).setMimeType(mimeType).build()
     mediaQueue.add(mediaItem)
   }

    return mediaQueue
  }
}
