package com.audiobookshelf.app.media

import android.bluetooth.BluetoothClass
import android.content.Context
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.server.ApiHandler
import java.util.*
import io.paperdb.Paper

class MediaManager(var apiHandler: ApiHandler, var ctx: Context) {
  val tag = "MediaManager"

  var serverLibraryItems = listOf<LibraryItem>()
  var serverLibraryCategories = listOf<LibraryCategory>()
  var serverLibraries = listOf<Library>()

  fun initializeAndroidAuto() {
    Log.d(tag, "Android Auto started when MainActivity was never started - initializing Paper")
    Paper.init(ctx)
  }

  fun loadLibraryCategories(libraryId:String, cb: (List<LibraryCategory>) -> Unit) {
    if (serverLibraryCategories.isNotEmpty()) {
      cb(serverLibraryCategories)
    } else {
      apiHandler.getLibraryCategories(libraryId) {
        serverLibraryCategories = it
        cb(it)
      }
    }
  }

  fun loadLibraryItems(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    if (serverLibraryItems.isNotEmpty()) {
      cb(serverLibraryItems)
    } else {
      apiHandler.getLibraryItems(libraryId) { libraryItems ->
        serverLibraryItems = libraryItems
        cb(libraryItems)
      }
    }
  }

  fun loadLibraries(cb: (List<Library>) -> Unit) {
    if (serverLibraries.isNotEmpty()) {
      cb(serverLibraries)
    } else {
      apiHandler.getLibraries {
        serverLibraries = it
        cb(it)
      }
    }
  }

  // TODO: Load currently listening category for local items
  fun loadLocalCategory():List<LibraryCategory> {
    val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
    val localPodcasts = DeviceManager.dbManager.getLocalLibraryItems("podcast")
    val cats = mutableListOf<LibraryCategory>()
    if (localBooks.isNotEmpty()) {
      cats.add(LibraryCategory("local-books", "Local Books", "book", localBooks, true))
    }
    if (localPodcasts.isNotEmpty()) {
      cats.add(LibraryCategory("local-podcasts", "Local Podcasts", "podcast", localPodcasts, true))
    }
    return cats
  }

  fun loadAndroidAutoItems(libraryId:String, cb: (List<LibraryCategory>) -> Unit) {
    Log.d(tag, "Load android auto items for library id $libraryId")
    val cats = mutableListOf<LibraryCategory>()

    val localCategories = loadLocalCategory()
    cats.addAll(localCategories)

    // Connected to server and has internet - load other cats
    if (apiHandler.isOnline() && (DeviceManager.isConnectedToServer || DeviceManager.hasLastServerConnectionConfig)) {
      if (!DeviceManager.isConnectedToServer) {
        DeviceManager.serverConnectionConfig = DeviceManager.deviceData.getLastServerConnectionConfig()
        Log.d(tag, "Not connected to server, set last server \"${DeviceManager.serverAddress}\"")
      }

      loadLibraries { libraries ->
        val library = libraries.find { it.id == libraryId } ?: libraries[0]
        Log.d(tag, "Loading categories for library ${library.name} - ${library.id} - ${library.mediaType}")

        loadLibraryCategories(libraryId) { libraryCategories ->

          // Only using book or podcast library categories for now
          libraryCategories.forEach {
            Log.d(tag, "Found library category ${it.label} with type ${it.type}")
            if (it.type == library.mediaType) {
              Log.d(tag, "Using library category ${it.id}")
              cats.add(it)
            }
          }

          loadLibraryItems(libraryId) { libraryItems ->
            val mainCat = LibraryCategory("library", "Library", library.mediaType, libraryItems, false)
            cats.add(mainCat)

            cb(cats)
          }
        }
      }
    } else { // Not connected/no internet sent downloaded cats only
      cb(cats)
    }
  }

  fun getFirstItem() : LibraryItemWrapper? {
    if (serverLibraryItems.isNotEmpty()) {
      return serverLibraryItems[0]
    } else {
      val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
      return if (localBooks.isNotEmpty()) return localBooks[0] else null
    }
  }

  fun getById(id:String) : LibraryItemWrapper? {
    if (id.startsWith("local")) {
      return DeviceManager.dbManager.getLocalLibraryItem(id)
    } else {
      return serverLibraryItems.find { it.id == id }
    }
  }

  fun getFromSearch(query:String?) : LibraryItemWrapper? {
    if (query.isNullOrEmpty()) return getFirstItem()
    return serverLibraryItems.find {
      it.title.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))
    }
  }

  fun play(libraryItemWrapper:LibraryItemWrapper, mediaPlayer:String, cb: (PlaybackSession) -> Unit) {
   if (libraryItemWrapper is LocalLibraryItem) {
    val localLibraryItem = libraryItemWrapper as LocalLibraryItem
    cb(localLibraryItem.getPlaybackSession(null))
   } else {
     val libraryItem = libraryItemWrapper as LibraryItem
     apiHandler.playLibraryItem(libraryItem.id,"",false, mediaPlayer) {
       cb(it)
     }
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
