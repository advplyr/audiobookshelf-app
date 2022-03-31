package com.audiobookshelf.app.data

data class FolderScanResult(
  val name:String?,
  val absolutePath:String,
  val mediaType:String,
  val contentUrl:String,
  val localMediaItems:MutableList<LocalMediaItem>,
)
