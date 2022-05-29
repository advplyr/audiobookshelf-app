package com.audiobookshelf.app.media

import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.server.ApiHandler
import java.util.*
import io.paperdb.Paper

class MediaManager(var apiHandler: ApiHandler, var ctx: Context) {
  val tag = "MediaManager"

  var serverLibraryItems = listOf<LibraryItem>()
  var selectedLibraryId = ""

  var selectedLibraryItemWrapper:LibraryItemWrapper? = null
  var selectedPodcast:Podcast? = null
  var selectedLibraryItemId:String? = null
  var serverPodcastEpisodes = listOf<PodcastEpisode>()
  var serverLibraryCategories = listOf<LibraryCategory>()
  var serverLibraries = listOf<Library>()

  fun initializeAndroidAuto() {
    Log.d(tag, "Android Auto started when MainActivity was never started - initializing Paper")
    Paper.init(ctx)
  }

  fun getIsLibrary(id:String) : Boolean {
    return serverLibraries.find { it.id == id } != null
  }

  fun checkResetServerItems() {
    // When opening android auto need to check if still connected to server
    //   and reset any server data already set
    if (!DeviceManager.isConnectedToServer) {
      serverPodcastEpisodes = listOf()
      serverLibraryCategories = listOf()
      serverLibraries = listOf()
      serverLibraryItems = listOf()
      selectedLibraryId = ""
    }
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

  fun loadLibraryItemsWithAudio(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    if (serverLibraryItems.isNotEmpty() && selectedLibraryId == libraryId) {
      cb(serverLibraryItems)
    } else {
      apiHandler.getLibraryItems(libraryId) { libraryItems ->
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }
        if (libraryItemsWithAudio.isNotEmpty()) selectedLibraryId = libraryId

        serverLibraryItems = libraryItemsWithAudio
        cb(libraryItemsWithAudio)
      }
    }
  }

  fun loadLibraryItem(libraryItemId:String, cb: (LibraryItemWrapper?) -> Unit) {
    if (libraryItemId.startsWith("local")) {
      cb(DeviceManager.dbManager.getLocalLibraryItem(libraryItemId))
    } else {
      Log.d(tag, "loadLibraryItem: $libraryItemId")
      apiHandler.getLibraryItem(libraryItemId) { libraryItem ->
        Log.d(tag, "loadLibraryItem: Got library item $libraryItem")
        cb(libraryItem)
      }
    }
  }

  fun loadPodcastEpisodeMediaBrowserItems(libraryItemId:String, cb: (MutableList<MediaBrowserCompat.MediaItem>) -> Unit) {
      loadLibraryItem(libraryItemId) { libraryItemWrapper ->
        Log.d(tag, "Loaded Podcast library item $libraryItemWrapper")

        selectedLibraryItemWrapper = libraryItemWrapper

        libraryItemWrapper?.let {
          if (libraryItemWrapper is LocalLibraryItem) { // Local podcast episodes
            if (libraryItemWrapper.mediaType != "podcast" || libraryItemWrapper.media.getAudioTracks().isEmpty()) {
              serverPodcastEpisodes = listOf()
              cb(mutableListOf())
            } else {
              val podcast = libraryItemWrapper.media as Podcast
              serverPodcastEpisodes = podcast.episodes ?: listOf()
              selectedLibraryItemId = libraryItemWrapper.id
              selectedPodcast = podcast

              val children = podcast.episodes?.map { podcastEpisode ->
                Log.d(tag, "Local Podcast Episode ${podcastEpisode.title} | ${podcastEpisode.id}")
                MediaBrowserCompat.MediaItem(podcastEpisode.getMediaMetadata(libraryItemWrapper).description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
              }
              children?.let { cb(children as MutableList) } ?: cb(mutableListOf())
            }
          } else if (libraryItemWrapper is LibraryItem) { // Server podcast episodes
            if (libraryItemWrapper.mediaType != "podcast" || libraryItemWrapper.media.getAudioTracks().isEmpty()) {
              serverPodcastEpisodes = listOf()
              cb(mutableListOf())
            } else {
              val podcast = libraryItemWrapper.media as Podcast
              serverPodcastEpisodes = podcast.episodes ?: listOf()
              selectedLibraryItemId = libraryItemWrapper.id
              selectedPodcast = podcast

              val children = podcast.episodes?.map { podcastEpisode ->
                MediaBrowserCompat.MediaItem(podcastEpisode.getMediaMetadata(libraryItemWrapper).description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
              }
              children?.let { cb(children as MutableList) } ?: cb(mutableListOf())
            }
          }
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

  fun loadAndroidAutoItems(cb: (List<LibraryCategory>) -> Unit) {
    Log.d(tag, "Load android auto items")
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
        val library = libraries[0]
        Log.d(tag, "Loading categories for library ${library.name} - ${library.id} - ${library.mediaType}")

        loadLibraryCategories(library.id) { libraryCategories ->

          // Only using book or podcast library categories for now
          libraryCategories.forEach {
            // Log.d(tag, "Found library category ${it.label} with type ${it.type}")
            if (it.type == library.mediaType) {
              // Log.d(tag, "Using library category ${it.id}")
              cats.add(it)
            }
          }

          cb(cats)
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

  fun getPodcastWithEpisodeByEpisodeId(id:String) : LibraryItemWithEpisode? {
    if (id.startsWith("local")) {
      return DeviceManager.dbManager.getLocalLibraryItemWithEpisode(id)
    } else {
      val podcastEpisode = serverPodcastEpisodes.find { it.id == id }
      return if (podcastEpisode != null && selectedLibraryItemWrapper != null) {
        LibraryItemWithEpisode(selectedLibraryItemWrapper!!, podcastEpisode)
      } else {
        null
      }
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

  fun play(libraryItemWrapper:LibraryItemWrapper, episode:PodcastEpisode?, playItemRequestPayload:PlayItemRequestPayload, cb: (PlaybackSession) -> Unit) {
   if (libraryItemWrapper is LocalLibraryItem) {
    val localLibraryItem = libraryItemWrapper as LocalLibraryItem
    cb(localLibraryItem.getPlaybackSession(episode))
   } else {
     val libraryItem = libraryItemWrapper as LibraryItem
     apiHandler.playLibraryItem(libraryItem.id,episode?.id ?: "",playItemRequestPayload) {
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
