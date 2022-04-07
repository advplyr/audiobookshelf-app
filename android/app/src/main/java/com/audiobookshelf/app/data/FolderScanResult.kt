package com.audiobookshelf.app.data

data class FolderScanResult(
  var itemsAdded:Int,
  var itemsUpdated:Int,
  var itemsRemoved:Int,
  var itemsUpToDate:Int,
  val localFolder:LocalFolder,
  val localLibraryItems:List<LocalLibraryItem>,
)

data class LocalLibraryItemScanResult(
  val updated:Boolean,
  val localLibraryItem:LocalLibraryItem,
)
