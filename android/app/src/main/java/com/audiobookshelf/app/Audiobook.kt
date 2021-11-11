package com.audiobookshelf.app

import android.net.Uri
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

  var fallbackCover:Uri
  var fallbackUri:Uri

  constructor(jsobj: JSObject) {
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

    fallbackUri = Uri.parse("http://fallback.com/run.mp3")
    fallbackCover = Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon)
  }

  fun getCover(serverUrl:String, token:String):Uri {
    return Uri.parse("$serverUrl/${book.cover}?token=$token")
  }
}
