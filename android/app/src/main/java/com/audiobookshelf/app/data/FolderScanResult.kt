package com.audiobookshelf.app.data

data class FolderScanResult(
  var itemsAdded:Int,
  var itemsUpdated:Int,
  var itemsRemoved:Int,
  var itemsUpToDate:Int,
  val localFolder:LocalFolder,
  val localMediaItems:List<LocalMediaItem>,
)
