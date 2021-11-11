package com.audiobookshelf.app

import com.getcapacitor.JSObject

class Book {
  var title:String
  var subtitle:String
  var author:String
  var authorFL:String
  var narrator:String
  var series:String
  var volumeNumber:String
  var publisher:String
  var description:String
  var publishYear:String
  var language:String
  var cover:String
  var coverFullPath:String
  var genres:String
  var lastUpdate:Long

  constructor(jsobj: JSObject) {
    title = jsobj.getString("title", "").toString()
    subtitle = jsobj.getString("subtitle", "").toString()
    author = jsobj.getString("author", "").toString()
    authorFL = jsobj.getString("authorFL", "").toString()
    narrator = jsobj.getString("narrator", "").toString()
    series = jsobj.getString("series", "").toString()
    volumeNumber = jsobj.getString("volumeNumber", "").toString()
    publisher = jsobj.getString("publisher", "").toString()
    description = jsobj.getString("description", "").toString()
    publishYear = jsobj.getString("publishYear", "").toString()
    language = jsobj.getString("language", "").toString()
    cover = jsobj.getString("cover", "").toString()
    coverFullPath = jsobj.getString("coverFullPath", "").toString()
    genres = jsobj.getString("genres", "").toString()
    lastUpdate = jsobj.getLong("lastUpdate")
  }
}
