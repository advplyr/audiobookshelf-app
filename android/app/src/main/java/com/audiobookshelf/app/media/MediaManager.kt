package com.audiobookshelf.app.media

import android.app.Activity
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.server.ApiHandler
import com.getcapacitor.JSObject
import java.util.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MediaManager(private var apiHandler: ApiHandler, var ctx: Context) {
  val tag = "MediaManager"

  private var serverLibraryItems = mutableListOf<LibraryItem>() // Store all items here
  private var selectedLibraryItems = mutableListOf<LibraryItem>()
  private var selectedLibraryId = ""

  private var cachedLibraryAuthors : MutableMap<String, MutableMap<String, LibraryAuthorItem>> = hashMapOf()
  private var cachedLibraryAuthorItems : MutableMap<String, MutableMap<String, List<LibraryItem>>> = hashMapOf()
  private var cachedLibraryAuthorSeriesItems : MutableMap<String, MutableMap<String, List<LibraryItem>>> = hashMapOf()
  private var cachedLibrarySeries : MutableMap<String, List<LibrarySeriesItem>> = hashMapOf()
  private var cachedLibrarySeriesItem : MutableMap<String, MutableMap<String, List<LibraryItem>>> = hashMapOf()
  private var cachedLibraryCollections : MutableMap<String, MutableMap<String, LibraryCollection>> = hashMapOf()

  private var selectedPodcast:Podcast? = null
  private var selectedLibraryItemId:String? = null
  private var podcastEpisodeLibraryItemMap = mutableMapOf<String, LibraryItemWithEpisode>()
  private var serverLibraryCategories = listOf<LibraryCategory>()
  private var serverConfigIdUsed:String? = null
  private var serverConfigLastPing:Long = 0L
  var serverUserMediaProgress:MutableList<MediaProgress> = mutableListOf()
  var serverItemsInProgress = listOf<ItemInProgress>()
  var serverLibraries = listOf<Library>()

  var userSettingsPlaybackRate:Float? = null

  fun getIsLibrary(id:String) : Boolean {
    return serverLibraries.find { it.id == id } != null
  }

  fun getLibrary(id:String) : Library? {
    return serverLibraries.find { it.id == id }
  }

  fun getSavedPlaybackRate():Float {
    if (userSettingsPlaybackRate != null) {
      return userSettingsPlaybackRate ?: 1f
    }

    val sharedPrefs = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    if (sharedPrefs != null) {
      val userSettingsPref = sharedPrefs.getString("userSettings", null)
      if (userSettingsPref != null) {
        try {
          val userSettings = JSObject(userSettingsPref)
          if (userSettings.has("playbackRate")) {
            userSettingsPlaybackRate = userSettings.getDouble("playbackRate").toFloat()
            return userSettingsPlaybackRate ?: 1f
          }
        } catch(je:JSONException) {
          Log.e(tag, "Failed to parse userSettings JSON ${je.localizedMessage}")
        }
      }
    }
    return 1f
  }

  fun setSavedPlaybackRate(newRate: Float) {
    val sharedPrefs = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    val sharedPrefEditor = sharedPrefs.edit()
    if (sharedPrefs != null) {
      val userSettingsPref = sharedPrefs.getString("userSettings", null)
      if (userSettingsPref != null) {
        try {
          val userSettings = JSObject(userSettingsPref)
          // toString().toDouble() to prevent float conversion issues (ex 1.2f becomes 1.2000000476837158d)
          userSettings.put("playbackRate", newRate.toString().toDouble())
          sharedPrefEditor.putString("userSettings", userSettings.toString())
          sharedPrefEditor.apply()
          userSettingsPlaybackRate = newRate
          Log.d(tag, "Saved userSettings JSON from Android Auto with playbackRate=$newRate")
        } catch(je:JSONException) {
          Log.e(tag, "Failed to save userSettings JSON ${je.localizedMessage}")
        }
      } else {
        // Not sure if this is the best place for this, but if a user has not changed any user settings in the app
        // the object will not exist yet, could be moved to a centralized place or created on first app load
        val userSettings = JSONObject()
        userSettings.put("playbackRate", newRate.toString().toDouble())
        sharedPrefEditor.putString("userSettings", userSettings.toString())
        userSettingsPlaybackRate = newRate
        Log.d(tag, "Created and saved userSettings JSON from Android Auto with playbackRate=$newRate")
      }
    }
  }

  fun checkResetServerItems() {
    // When opening android auto need to check if still connected to server
    //   and reset any server data already set
    val serverConnConfig = if (DeviceManager.isConnectedToServer) DeviceManager.serverConnectionConfig else DeviceManager.deviceData.getLastServerConnectionConfig()

    if (!DeviceManager.isConnectedToServer || !DeviceManager.checkConnectivity(ctx) || serverConnConfig == null || serverConnConfig.id !== serverConfigIdUsed) {
      podcastEpisodeLibraryItemMap = mutableMapOf()
      serverLibraryCategories = listOf()
      serverLibraries = listOf()
      serverLibraryItems = mutableListOf()
      selectedLibraryItems = mutableListOf()
      selectedLibraryId = ""
    }
  }

  private fun loadItemsInProgressForAllLibraries(cb: (List<ItemInProgress>) -> Unit) {
    if (serverItemsInProgress.isNotEmpty()) {
      cb(serverItemsInProgress)
    } else {
      apiHandler.getAllItemsInProgress { itemsInProgress ->
        serverItemsInProgress = itemsInProgress.filter {
          val libraryItem = it.libraryItemWrapper as LibraryItem
          libraryItem.checkHasTracks()
        }
        cb(serverItemsInProgress)
      }
    }
  }

  fun loadLibraryItemsWithAudio(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    if (selectedLibraryItems.isNotEmpty() && selectedLibraryId == libraryId) {
      cb(selectedLibraryItems)
    } else {
      apiHandler.getLibraryItems(libraryId) { libraryItems ->
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }
        if (libraryItemsWithAudio.isNotEmpty()) {
          selectedLibraryId = libraryId
        }

        selectedLibraryItems = mutableListOf()
        libraryItemsWithAudio.forEach { libraryItem ->
          selectedLibraryItems.add(libraryItem)
          if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
            serverLibraryItems.add(libraryItem)
          }
        }
        cb(libraryItemsWithAudio)
      }
    }
  }

  /**
   *  Returns series with audio books from selected library.
   *  If data is not found from local cache then it will be fetched from server
   */
  fun loadLibrarySeriesWithAudio(libraryId:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    // Check "cache" first
    if (cachedLibrarySeries.containsKey(libraryId)) {
      Log.d(tag, "Series with audio found from cache | Library $libraryId ")
      cb(cachedLibrarySeries[libraryId] as List<LibrarySeriesItem>)
    } else {
      apiHandler.getLibrarySeries(libraryId) { seriesItems ->
        Log.d(tag, "Series with audio loaded from server | Library $libraryId")
        val seriesItemsWithAudio = seriesItems.filter { si -> si.audiobookCount > 0 }

        cachedLibrarySeries[libraryId] = seriesItemsWithAudio

        cb(seriesItemsWithAudio)
      }
    }
  }

  /**
   * Returns series with audiobooks from selected library using filter for paging.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibrarySeriesWithAudio(libraryId:String, seriesFilter:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    // Check "cache" first
    if (!cachedLibrarySeries.containsKey(libraryId)) {
      loadLibrarySeriesWithAudio(libraryId) {}
    } else {
      Log.d(tag, "Series with audio found from cache | Library $libraryId ")
    }
    val seriesWithBooks = cachedLibrarySeries[libraryId]!!.filter { ls -> ls.title.uppercase().startsWith(seriesFilter) }.toList()
    cb(seriesWithBooks)
  }

  fun sortSeriesBooks(seriesBooks: List<LibraryItem>) : List<LibraryItem> {
    val sortingLogic = compareBy<LibraryItem> { it.seriesSequenceParts[0].length }
      .thenBy { it.seriesSequenceParts[0].ifEmpty { "" } }
      .thenBy { it.seriesSequenceParts.getOrElse(1) { "" }.length }
      .thenBy { it.seriesSequenceParts.getOrElse(1) { "" } }
    return seriesBooks.sortedWith(sortingLogic)
  }

  /**
   * Returns books for series from library.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibrarySeriesItemsWithAudio(libraryId:String, seriesId:String, cb: (List<LibraryItem>) -> Unit) {
    // Check "cache" first
    if (!cachedLibrarySeriesItem.containsKey(libraryId)) {
      cachedLibrarySeriesItem[libraryId] = hashMapOf()
    }
    if (cachedLibrarySeriesItem[libraryId]!!.containsKey(seriesId)) {
      Log.d(tag, "Items for series $seriesId found from cache | Library $libraryId")
      cachedLibrarySeriesItem[libraryId]!![seriesId]?.let { cb(it) }
    } else {
      apiHandler.getLibrarySeriesItems(libraryId, seriesId) { libraryItems ->
        Log.d(tag, "Items for series $seriesId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        val sortedLibraryItemsWithAudio = sortSeriesBooks(libraryItemsWithAudio)
        cachedLibrarySeriesItem[libraryId]!![seriesId] = sortedLibraryItemsWithAudio

        sortedLibraryItemsWithAudio.forEach { libraryItem ->
          if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
            serverLibraryItems.add(libraryItem)
          }
        }
        cb(sortedLibraryItemsWithAudio)
      }
    }
  }

  /**
   * Returns authors with books from library.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorsWithBooks(libraryId:String, cb: (List<LibraryAuthorItem>) -> Unit) {
    // Check "cache" first
    if (cachedLibraryAuthors.containsKey(libraryId)) {
      Log.d(tag, "Authors with books found from cache | Library $libraryId ")
      cb(cachedLibraryAuthors[libraryId]!!.values.toList())
    } else {
      // Fetch data from server and add it to local "cache"
      apiHandler.getLibraryAuthors(libraryId) { authorItems ->
        Log.d(tag, "Authors with books loaded from server | Library $libraryId ")
        // TO-DO: This check won't ensure that there is audiobooks. Current API won't offer ability to do so
        var authorItemsWithBooks = authorItems.filter { li -> li.bookCount != null && li.bookCount!! > 0 }
        authorItemsWithBooks = authorItemsWithBooks.sortedBy { it.name }
        // Ensure that there is map for library
        cachedLibraryAuthors[libraryId] = mutableMapOf()
        // Cache authors
        authorItemsWithBooks.forEach {
          if (!cachedLibraryAuthors[libraryId]!!.containsKey(it.id)) {
            cachedLibraryAuthors[libraryId]!![it.id] = it
          }
        }
        cb(authorItemsWithBooks)
      }
    }
  }

  /**
   * Returns authors with books from selected library using filter for paging.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorsWithBooks(libraryId:String, authorFilter: String, cb: (List<LibraryAuthorItem>) -> Unit) {
    // Check "cache" first
    if (cachedLibraryAuthors.containsKey(libraryId)) {
      Log.d(tag, "Authors with books found from cache | Library $libraryId ")
    } else {
      loadAuthorsWithBooks(libraryId) {}
    }
    val authorsWithBooks = cachedLibraryAuthors[libraryId]!!.values.filter { lai -> lai.name.uppercase().startsWith(authorFilter) }.toList()
    cb(authorsWithBooks)
  }

  /**
   * Returns audiobooks for author from library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorBooksWithAudio(libraryId:String, authorId:String, cb: (List<LibraryItem>) -> Unit) {
    // Ensure that there is map for library
    if (!cachedLibraryAuthorItems.containsKey(libraryId)) {
        cachedLibraryAuthorItems[libraryId] = mutableMapOf()
    }
    // Check "cache" first
    if (cachedLibraryAuthorItems[libraryId]!!.containsKey(authorId)) {
      Log.d(tag, "Items for author $authorId found from cache | Library $libraryId")
      cachedLibraryAuthorItems[libraryId]!![authorId]?.let { cb(it) }
    } else {
      apiHandler.getLibraryItemsFromAuthor(libraryId, authorId) { libraryItems ->
        Log.d(tag, "Items for author $authorId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        cachedLibraryAuthorItems[libraryId]!![authorId]  = libraryItemsWithAudio

        libraryItemsWithAudio.forEach { libraryItem ->
          if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
            serverLibraryItems.add(libraryItem)
          }
        }

        cb(libraryItemsWithAudio)
      }
    }
  }

  /**
   * Returns audiobooks for author from specified series within library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorSeriesBooksWithAudio(libraryId:String, authorId:String, seriesId: String, cb: (List<LibraryItem>) -> Unit) {
    val authorSeriesKey = "$authorId|$seriesId"
    // Ensure that there is map for library
    if (!cachedLibraryAuthorSeriesItems.containsKey(libraryId)) {
      cachedLibraryAuthorSeriesItems[libraryId] = mutableMapOf()
    }
    // Check "cache" first
    if (cachedLibraryAuthorSeriesItems[libraryId]!!.containsKey(authorSeriesKey)) {
      Log.d(tag, "Items for series $seriesId with author $authorId found from cache | Library $libraryId")
      cachedLibraryAuthorSeriesItems[libraryId]!![authorSeriesKey]?.let { cb(it) }
    } else {
      apiHandler.getLibrarySeriesItems(libraryId, seriesId) { libraryItems ->
        Log.d(tag, "Items for series $seriesId with author $authorId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }
        if (!cachedLibraryAuthors[libraryId]!!.containsKey(authorId)) {
          Log.d(tag, "Author data is missing")
        }
        val authorName = cachedLibraryAuthors[libraryId]!![authorId]?.name ?: ""
        Log.d(tag, "Using author name: $authorName")
        val libraryItemsFromAuthorWithAudio = libraryItemsWithAudio.filter { li -> li.authorName.indexOf(authorName, ignoreCase = true) >= 0 }

        val sortedLibraryItemsWithAudio = sortSeriesBooks(libraryItemsFromAuthorWithAudio)
        cachedLibraryAuthorSeriesItems[libraryId]!![authorId] = sortedLibraryItemsWithAudio

        sortedLibraryItemsWithAudio.forEach { libraryItem ->
          if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
            serverLibraryItems.add(libraryItem)
          }
        }

        cb(sortedLibraryItemsWithAudio)
      }
    }
  }

  /**
   * Returns collections with audiobooks from library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibraryCollectionsWithAudio(libraryId:String, cb: (List<LibraryCollection>) -> Unit) {
    if (cachedLibraryCollections.containsKey(libraryId)) {
      Log.d(tag, "Collections with books found from cache | Library $libraryId ")
      cb(cachedLibraryCollections[libraryId]!!.values.toList())
    } else {
      apiHandler.getLibraryCollections(libraryId) { libraryCollections ->
        Log.d(tag, "Collections with books loaded from server | Library $libraryId ")
        val libraryCollectionsWithAudio = libraryCollections.filter { lc -> lc.audiobookCount > 0 }

        // Cache collections
        cachedLibraryCollections[libraryId] = hashMapOf()
        libraryCollectionsWithAudio.forEach {
          if (!cachedLibraryCollections[libraryId]!!.containsKey(it.id)) {
            cachedLibraryCollections[libraryId]!![it.id] = it
          }
        }
        cb(libraryCollectionsWithAudio)
      }
    }
  }

  /**
   * Returns audiobooks for collection from library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibraryCollectionBooksWithAudio(libraryId: String, collectionId: String, cb: (List<LibraryItem>) -> Unit) {
    if (!cachedLibraryCollections.containsKey(libraryId)) {
      loadLibraryCollectionsWithAudio(libraryId) {}
    }
    Log.d(tag, "Trying to find collection $collectionId items from from cache | Library $libraryId ")
    if ( cachedLibraryCollections[libraryId]!!.containsKey(collectionId)) {
      val libraryCollectionBookswithAudio = cachedLibraryCollections[libraryId]!![collectionId]?.books
      libraryCollectionBookswithAudio?.forEach { libraryItem ->
        if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
          serverLibraryItems.add(libraryItem)
        }
      }
      cb(libraryCollectionBookswithAudio as List<LibraryItem>)
    }
  }

  private fun loadLibraryItem(libraryItemId:String, cb: (LibraryItemWrapper?) -> Unit) {
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

  fun loadPodcastEpisodeMediaBrowserItems(libraryItemId:String, ctx:Context, cb: (MutableList<MediaBrowserCompat.MediaItem>) -> Unit) {
      loadLibraryItem(libraryItemId) { libraryItemWrapper ->
        Log.d(tag, "Loaded Podcast library item $libraryItemWrapper")

        libraryItemWrapper?.let {
          if (libraryItemWrapper is LocalLibraryItem) { // Local podcast episodes
            if (libraryItemWrapper.mediaType != "podcast" || libraryItemWrapper.media.getAudioTracks().isEmpty()) {
              cb(mutableListOf())
            } else {
              val podcast = libraryItemWrapper.media as Podcast
              selectedLibraryItemId = libraryItemWrapper.id
              selectedPodcast = podcast

              val children = podcast.episodes?.map { podcastEpisode ->
                Log.d(tag, "Local Podcast Episode ${podcastEpisode.title} | ${podcastEpisode.id}")

                val progress = DeviceManager.dbManager.getLocalMediaProgress("${libraryItemWrapper.id}-${podcastEpisode.id}")
                val description = podcastEpisode.getMediaDescription(libraryItemWrapper, progress, ctx)

                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
              }
              children?.let { cb(children as MutableList) } ?: cb(mutableListOf())
            }
          } else if (libraryItemWrapper is LibraryItem) { // Server podcast episodes
            if (libraryItemWrapper.mediaType != "podcast" || libraryItemWrapper.media.getAudioTracks().isEmpty()) {
              cb(mutableListOf())
            } else {
              val podcast = libraryItemWrapper.media as Podcast
              podcast.episodes?.forEach { podcastEpisode ->
                podcastEpisodeLibraryItemMap[podcastEpisode.id] = LibraryItemWithEpisode(libraryItemWrapper, podcastEpisode)
              }
              selectedLibraryItemId = libraryItemWrapper.id
              selectedPodcast = podcast

              val children = podcast.episodes?.map { podcastEpisode ->

                val progress = serverUserMediaProgress.find { it.libraryItemId == libraryItemWrapper.id && it.episodeId == podcastEpisode.id }

                // to show download icon
                val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItemWrapper.id)
                localLibraryItem?.let { lli ->
                  val localEpisode = (lli.media as Podcast).episodes?.find { it.serverEpisodeId == podcastEpisode.id }
                  podcastEpisode.localEpisodeId = localEpisode?.id
                }

                val description = podcastEpisode.getMediaDescription(libraryItemWrapper, progress, ctx)
                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
              }
              children?.let { cb(children as MutableList) } ?: cb(mutableListOf())
            }
          }
        }
      }
  }

  private fun loadLibraries(cb: (List<Library>) -> Unit) {
    if (serverLibraries.isNotEmpty()) {
      cb(serverLibraries)
    } else {
      apiHandler.getLibraries { loadedLibraries ->
        serverLibraries = loadedLibraries.map { library ->
          apiHandler.getLibraryStats(library.id) { libraryStats ->
            Log.d(tag, "Library stats for library ${library.id} | $libraryStats")
            library.stats = libraryStats
          }
          library
        }
        cb(serverLibraries)
      }
    }
  }

  private suspend fun checkServerConnection(config:ServerConnectionConfig) : Boolean {
    var successfulPing = false
    suspendCoroutine { cont ->
      apiHandler.pingServer(config) {
        Log.d(tag, "checkServerConnection: Checked server conn for ${config.address} result = $it")
        successfulPing = it
        cont.resume(it)
      }
    }
    return successfulPing
  }

  private suspend fun authorize(config:ServerConnectionConfig) : MutableList<MediaProgress> {
    var mediaProgress:MutableList<MediaProgress> = mutableListOf()
    suspendCoroutine { cont ->
      apiHandler.authorize(config) {
        Log.d(tag, "authorize: Authorized server config ${config.address} result = $it")
        if (!it.isNullOrEmpty()) {
          mediaProgress = it
        }
        cont.resume(mediaProgress)
      }
    }
    return mediaProgress
  }

  private fun checkSetValidServerConnectionConfig(cb: (Boolean) -> Unit) = runBlocking {
    Log.d(tag, "checkSetValidServerConnectionConfig | serverConfigIdUsed=$serverConfigIdUsed | lastServerConnectionConfigId=${DeviceManager.deviceData.lastServerConnectionConfigId}")

    coroutineScope {
      if (!DeviceManager.checkConnectivity(ctx)) {
        serverUserMediaProgress = mutableListOf()
        Log.d(tag, "checkSetValidServerConnectionConfig: No connectivity")
        cb(false)
      } else if (DeviceManager.deviceData.lastServerConnectionConfigId.isNullOrBlank()) { // If in offline mode last server connection config is unset
        serverUserMediaProgress = mutableListOf()
        Log.d(tag, "checkSetValidServerConnectionConfig: No last server connection config")
        cb(false)
      } else {
        var hasValidConn = false
        var lookupMediaProgress = true

        if (!serverConfigIdUsed.isNullOrEmpty() && serverConfigLastPing > 0L && System.currentTimeMillis() - serverConfigLastPing < 5000) {
            Log.d(tag, "checkSetValidServerConnectionConfig last ping less than a 5 seconds ago")
          hasValidConn = true
          lookupMediaProgress = false
        } else {
          serverUserMediaProgress = mutableListOf()
        }

        if (!hasValidConn) {
          // First check if the current selected config is pingable
          DeviceManager.serverConnectionConfig?.let {
            hasValidConn = checkServerConnection(it)
            Log.d(
              tag,
              "checkSetValidServerConnectionConfig: Current config ${DeviceManager.serverAddress} is pingable? $hasValidConn"
            )
          }
        }

        if (!hasValidConn) {
          // Loop through available configs and check if can connect
          for (config: ServerConnectionConfig in DeviceManager.deviceData.serverConnectionConfigs) {
            val result = checkServerConnection(config)

            if (result) {
              hasValidConn = true
              DeviceManager.serverConnectionConfig = config
              Log.d(tag, "checkSetValidServerConnectionConfig: Set server connection config ${DeviceManager.serverConnectionConfigId}")
              break
            }
          }
        }

        if (hasValidConn) {
          serverConfigLastPing = System.currentTimeMillis()

          if (lookupMediaProgress) {
            Log.d(tag, "Has valid conn now get user media progress")
            DeviceManager.serverConnectionConfig?.let {
              serverUserMediaProgress = authorize(it)
            }
          }
        }

        cb(hasValidConn)
      }
    }

  }

  fun loadServerUserMediaProgress(cb: () -> Unit) {
    Log.d(tag, "Loading server media progress")
    if (DeviceManager.serverConnectionConfig == null) {
      return cb()
    }

    DeviceManager.serverConnectionConfig?.let { config ->
      apiHandler.authorize(config) {
        Log.d(tag, "loadServerUserMediaProgress: Authorized server config ${config.address} result = $it")
        if (!it.isNullOrEmpty()) {
          serverUserMediaProgress = it
        }
        cb()
      }
    }
  }

  fun loadAndroidAutoItems(cb: () -> Unit) {
    Log.d(tag, "Load android auto items")

    // Check if any valid server connection if not use locally downloaded books
    checkSetValidServerConnectionConfig { isConnected ->
      if (isConnected) {
        serverConfigIdUsed = DeviceManager.serverConnectionConfigId
        Log.d(tag, "loadAndroidAutoItems: Connected to server config id=$serverConfigIdUsed")

        loadLibraries { libraries ->
          if (libraries.isEmpty()) {
            Log.w(tag, "No libraries returned from server request")
            cb()
          } else {
            val library = libraries[0]
            Log.d(tag, "Loading categories for library ${library.name} - ${library.id} - ${library.mediaType}")

            loadItemsInProgressForAllLibraries { itemsInProgress ->
              itemsInProgress.forEach {
                val libraryItem = it.libraryItemWrapper as LibraryItem
                if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
                  serverLibraryItems.add(libraryItem)
                }

                if (it.episode != null) {
                  podcastEpisodeLibraryItemMap[it.episode.id] = LibraryItemWithEpisode(it.libraryItemWrapper, it.episode)
                }
              }

              cb() // Fully loaded
            }
          }
        }
      } else { // Not connected to server
        Log.d(tag, "loadAndroidAutoItems: Not connected to server")
        cb()
      }
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
    return if (id.startsWith("local")) {
      DeviceManager.dbManager.getLocalLibraryItemWithEpisode(id)
    } else {
      podcastEpisodeLibraryItemMap[id]
    }
  }

  fun getById(id:String) : LibraryItemWrapper? {
    return if (id.startsWith("local")) {
      DeviceManager.dbManager.getLocalLibraryItem(id)
    } else {
      serverLibraryItems.find { it.id == id }
    }
  }

  fun getFromSearch(query:String?) : LibraryItemWrapper? {
    if (query.isNullOrEmpty()) return getFirstItem()
    return serverLibraryItems.find {
      it.title.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))
    }
  }

  fun play(libraryItemWrapper:LibraryItemWrapper, episode:PodcastEpisode?, playItemRequestPayload:PlayItemRequestPayload, cb: (PlaybackSession?) -> Unit) {
    if (libraryItemWrapper is LocalLibraryItem) {
      cb(libraryItemWrapper.getPlaybackSession(episode, playItemRequestPayload.deviceInfo))
    } else {
      val libraryItem = libraryItemWrapper as LibraryItem
      apiHandler.playLibraryItem(libraryItem.id,episode?.id ?: "", playItemRequestPayload) {
        if (it == null) {
          cb(null)
        } else {
          cb(it)
        }
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
