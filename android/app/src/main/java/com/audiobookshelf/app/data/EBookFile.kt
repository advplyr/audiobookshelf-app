package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.squareup.moshi.JsonClass

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonClass(generateAdapter = true)
data class EBookFile(
  var ino:String,
  var metadata:FileMetadata?,
  var ebookFormat:String,
  var isLocal:Boolean,
  var localFileId:String?,
  var contentUrl:String?
)
