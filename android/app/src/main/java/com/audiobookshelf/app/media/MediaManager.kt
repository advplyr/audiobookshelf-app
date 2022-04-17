package com.audiobookshelf.app.media

import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.server.ApiHandler
import java.util.*

class MediaManager(var apiHandler: ApiHandler) {
  var serverLibraryItems = listOf<LibraryItem>()

  fun loadLibraryItems(cb: (List<LibraryItem>) -> Unit) {
    if (serverLibraryItems.isNotEmpty()) {
      cb(serverLibraryItems)
    } else {
      apiHandler.getLibraryItems("main") { libraryItems ->
        serverLibraryItems = libraryItems
        cb(libraryItems)
      }
    }
  }

  fun getFirstItem() : LibraryItem? {
    return if (serverLibraryItems.isNotEmpty()) serverLibraryItems[0] else null
  }

  fun getById(id:String) : LibraryItem? {
    return serverLibraryItems.find { it.id == id }
  }

  fun getFromSearch(query:String?) : LibraryItem? {
    if (query.isNullOrEmpty()) return getFirstItem()
    return serverLibraryItems.find {
      it.title.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))
    }
  }

  fun play(libraryItem:LibraryItem, mediaPlayer:String, cb: (PlaybackSession) -> Unit) {
    apiHandler.playLibraryItem(libraryItem.id,"",false, mediaPlayer) {
      cb(it)
    }
  }

  private fun levenshtein(lhs : CharSequence, rhs : CharSequence) : Int {
    val lhsLength = lhs.length + 1
    val rhsLength = rhs.length + 1

    var cost = Array(lhsLength) { it }
    var newCost = Array(lhsLength) { 0 }

    for (i in 1..rhsLength-1) {
      newCost[0] = i

      for (j in 1..lhsLength-1) {
        val match = if(lhs[j - 1] == rhs[i - 1]) 0 else 1

        val costReplace = cost[j - 1] + match
        val costInsert = cost[j] + 1
        val costDelete = newCost[j - 1] + 1

        newCost[j] = Math.min(Math.min(costInsert, costDelete), costReplace)
      }

      val swap = cost
      cost = newCost
      newCost = swap
    }

    return cost[lhsLength - 1]
  }
}
