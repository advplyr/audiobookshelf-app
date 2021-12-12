package com.audiobookshelf.app

import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import com.getcapacitor.JSObject

class Audiobook {
  var id:String
  var ino:String
  var libraryId:String
  var folderId:String
  var book:Book
  var duration:Float
  var size:Long
  var numTracks:Int
  var isMissing:Boolean
  var isInvalid:Boolean
  var path:String

  var isDownloaded:Boolean = false
  var downloadFolderUrl:String = ""
  var folderUrl:String = ""
  var contentUrl:String = ""
  var filename:String = ""
  var localCoverUrl:String = ""
  var localCover:String = ""

  var serverUrl:String = ""
  var token:String = ""

  constructor(jsobj: JSObject, serverUrl:String, token:String) {
    this.serverUrl = serverUrl
    this.token = token

    id = jsobj.getString("id", "").toString()
    ino = jsobj.getString("ino", "").toString()
    libraryId = jsobj.getString("libraryId", "").toString()
    folderId = jsobj.getString("folderId", "").toString()

    var bookJsObj = jsobj.getJSObject("book")
    book = bookJsObj?.let { Book(it) }!!

    duration = jsobj.getDouble("duration").toFloat()
    size = jsobj.getLong("size")
    numTracks = jsobj.getInteger("numTracks")!!
    isMissing = jsobj.getBoolean("isMissing")
    isInvalid = jsobj.getBoolean("isInvalid")
    path = jsobj.getString("path", "").toString()

    isDownloaded = jsobj.getBoolean("isDownloaded")
    if (isDownloaded) {
      downloadFolderUrl = jsobj.getString("downloadFolderUrl", "").toString()
      folderUrl = jsobj.getString("folderUrl", "").toString()
      contentUrl = jsobj.getString("contentUrl", "").toString()
      filename = jsobj.getString("filename", "").toString()
      localCover = jsobj.getString("localCover", "").toString()
      localCoverUrl = jsobj.getString("localCoverUrl", "").toString()
    }
  }

  fun getCover():Uri {
    if (isDownloaded) {
//      return Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)
      return Uri.parse(localCoverUrl)
    }
    if (book.cover == "" || serverUrl == "" || token == "") return Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)
    return Uri.parse("$serverUrl${book.cover}?token=$token&ts=${book.lastUpdate}")
  }

  fun getDurationLong():Long {
    return duration.toLong() * 1000L
  }

  fun toMediaMetadata():MediaMetadataCompat {
    return MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
      putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, book.title)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, book.title)
      putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, book.authorFL)
      putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, getCover().toString())
      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, getCover().toString())
      putString(MediaMetadataCompat.METADATA_KEY_ART_URI, getCover().toString())
      putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, book.authorFL)

//      val extras = Bundle()
//      if (isDownloaded) {
//        extras.putLong(
//          MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
//          MediaDescriptionCompat.STATUS_DOWNLOADED)
//      }
//            extras.putInt(
//              MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
//              MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)

//      putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, RESOURCE_ROOT_URI +
//        context.resources.getResourceEntryName(R.drawable.notification_bg_low_normal))
    }.build()
  }
}
