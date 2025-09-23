package com.tomesonic.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class EBookFile(
  var ino:String,
  var metadata:FileMetadata?,
  var ebookFormat:String,
  var isLocal:Boolean,
  var localFileId:String?,
  var contentUrl:String?
)
