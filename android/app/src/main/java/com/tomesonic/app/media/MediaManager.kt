package com.tomesonic.app.media

import android.app.Activity
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import com.tomesonic.app.data.*
import com.tomesonic.app.device.DeviceManager
import com.tomesonic.app.server.ApiHandler
import com.getcapacitor.JSObject
import com.google.common.util.concurrent.SettableFuture
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MediaManager(private var apiHandler: ApiHandler, var ctx: Context) {
  val tag = "MediaManager"

  // Listeners for Android Auto load completion events
  private var androidAutoLoadListeners: MutableList<() -> Unit> = mutableListOf()

  private var serverLibraryItems = mutableListOf<LibraryItem>() // Store all items here

  private var cachedLibraryAuthors : MutableMap<String, MutableMap<String, LibraryAuthorItem>> = hashMapOf()
  private var cachedLibraryAuthorItems : MutableMap<String, MutableMap<String, List<LibraryItem>>> = hashMapOf()
  private var cachedLibraryAuthorSeriesItems : MutableMap<String, MutableMap<String, List<LibraryItem>>> = hashMapOf()
  private var cachedLibrarySeries : MutableMap<String, List<LibrarySeriesItem>> = hashMapOf()
  private var cachedLibrarySeriesItem : MutableMap<String, MutableMap<String, List<LibraryItem>>> = hashMapOf()
  private var cachedLibraryCollections : MutableMap<String, MutableMap<String, LibraryCollection>> = hashMapOf()
  private var cachedLibraryRecentShelves : MutableMap<String, MutableList<LibraryShelfType>> = hashMapOf()
  private var cachedLibraryDiscovery : MutableMap<String, MutableList<LibraryItem>> = hashMapOf()
  private var cachedLibraryPodcasts : MutableMap<String, MutableMap<String, LibraryItem>> = hashMapOf()
  private var isLibraryPodcastsCached : MutableMap<String, Boolean> = hashMapOf()
  var allLibraryPersonalizationsDone : Boolean = false

  private var selectedPodcast:Podcast? = null
  private var selectedLibraryItemId:String? = null
  private var podcastEpisodeLibraryItemMap = mutableMapOf<String, LibraryItemWithEpisode>()
  private var serverConfigIdUsed:String? = null
  private var serverConfigLastPing:Long = 0L
  var serverUserMediaProgress:MutableList<MediaProgress> = mutableListOf()
  var serverItemsInProgress = listOf<ItemInProgress>()
  var serverLibraries = listOf<Library>()

  var userSettingsPlaybackRate:Float? = null

  /**
   * Initialize MediaManager by loading persisted server data
   */
  private fun initializePersistedData() {
    val serverConfigId = DeviceManager.serverConnectionConfigId
    if (serverConfigId.isNotEmpty()) {
      Log.d(tag, "AABrowser: Initializing persisted data for server $serverConfigId")

      // Load persisted server libraries first
      val persistedLibraries = DeviceManager.dbManager.getServerLibraries(serverConfigId)
      if (persistedLibraries != null && persistedLibraries.isNotEmpty()) {
        serverLibraries = persistedLibraries
        Log.d(tag, "AABrowser: Loaded ${persistedLibraries.size} persisted libraries")
      }

      // If we have libraries (either from persistence or already loaded), load their cached data
      if (serverLibraries.isNotEmpty()) {
        serverLibraries.forEach { library ->
          val libraryId = library.id

          // Load cached library items (podcasts, books, etc.)
          val cachedItems = DeviceManager.dbManager.getCachedLibraryItems(serverConfigId, libraryId)
          if (cachedItems != null && cachedItems.isNotEmpty()) {
            // Populate cachedLibraryPodcasts for podcasts
            if (!cachedLibraryPodcasts.containsKey(libraryId)) {
              cachedLibraryPodcasts[libraryId] = mutableMapOf()
            }
            cachedItems.forEach { item ->
              cachedLibraryPodcasts[libraryId]?.set(item.id, item)
              if (serverLibraryItems.find { li -> li.id == item.id } == null) {
                serverLibraryItems.add(item)
              }
            }
            isLibraryPodcastsCached[libraryId] = true
            Log.d(tag, "AABrowser: Loaded ${cachedItems.size} persisted items for library $libraryId")
          }

          // Load cached author items
          val authorKeys = DeviceManager.dbManager.getAllKeys().filter { it.startsWith("${serverConfigId}_${libraryId}_author_") && !it.contains("_series_") }
          authorKeys.forEach { cacheKey ->
            val authorId = cacheKey.substringAfter("${serverConfigId}_${libraryId}_author_")
            val cachedAuthorItems = DeviceManager.dbManager.getCachedLibraryItems(serverConfigId, "${libraryId}_author_${authorId}")
            if (cachedAuthorItems != null && cachedAuthorItems.isNotEmpty()) {
              if (!cachedLibraryAuthorItems.containsKey(libraryId)) {
                cachedLibraryAuthorItems[libraryId] = mutableMapOf()
              }
              cachedLibraryAuthorItems[libraryId]!![authorId] = cachedAuthorItems
              cachedAuthorItems.forEach { item ->
                if (serverLibraryItems.find { li -> li.id == item.id } == null) {
                  serverLibraryItems.add(item)
                }
              }
              Log.d(tag, "AABrowser: Loaded ${cachedAuthorItems.size} persisted author items for author $authorId")
            }
          }

          // Load cached series items
          val seriesKeys = DeviceManager.dbManager.getAllKeys().filter { it.startsWith("${serverConfigId}_") && !it.contains("_author_") && !it.contains("_series_") }
          seriesKeys.forEach { cacheKey ->
            // Extract seriesId from cache key (format: serverConfigId_seriesId)
            val seriesId = cacheKey.substringAfter("${serverConfigId}_")
            if (seriesId.isNotEmpty() && seriesId != cacheKey) { // Make sure we actually found a separator
              val cachedSeriesItems = DeviceManager.dbManager.getCachedSeriesItems(serverConfigId, seriesId)
              if (cachedSeriesItems != null && cachedSeriesItems.isNotEmpty()) {
                if (!cachedLibrarySeriesItem.containsKey(libraryId)) {
                  cachedLibrarySeriesItem[libraryId] = hashMapOf()
                }
                cachedLibrarySeriesItem[libraryId]!![seriesId] = cachedSeriesItems
                cachedSeriesItems.forEach { item ->
                  if (serverLibraryItems.find { li -> li.id == item.id } == null) {
                    serverLibraryItems.add(item)
                  }
                }
                Log.d(tag, "AABrowser: Loaded ${cachedSeriesItems.size} persisted series items for series $seriesId")
              }
            }
          }
        }
      } else {
        Log.d(tag, "AABrowser: No persisted libraries found, skipping cached data loading")
      }
    } else {
      Log.d(tag, "AABrowser: No server connection config, skipping persisted data initialization")
    }
  }

  fun getIsLibrary(id:String) : Boolean {
    return serverLibraries.find { it.id == id } != null
  }

  /**
   * Check if there is discovery shelf for [libraryId]
   * If personalized shelves are not yet populated for library then populate
   *
   */
  fun getHasDiscovery(libraryId: String) : Boolean {
    if (cachedLibraryDiscovery.containsKey(libraryId)) {
      if (cachedLibraryDiscovery[libraryId]!!.isNotEmpty()) {
        return true
      }
    } else {
      populatePersonalizedDataForLibrary(libraryId){}
    }
    return false
  }

  fun getLibrary(id:String) : Library? {
    return serverLibraries.find { it.id == id }
  }

  /**
   * Add [libraryItem] to [serverLibraryItems] if it is not already added
   */
  private fun addServerLibrary(libraryItem: LibraryItem) {
    if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
      serverLibraryItems.add(libraryItem)
    }
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

  fun checkResetServerItems():Boolean {
    // When opening android auto need to check if still connected to server
    //   and reset any server data already set
    val serverConnConfig = if (DeviceManager.isConnectedToServer) DeviceManager.serverConnectionConfig else DeviceManager.deviceData.getLastServerConnectionConfig()

    if (!DeviceManager.isConnectedToServer || !DeviceManager.checkConnectivity(ctx) || serverConnConfig == null || serverConnConfig.id !== serverConfigIdUsed) {
      Log.d(tag, "AABrowser: Server connection changed, clearing in-memory cache but preserving persisted data")

      // Clear in-memory cache but preserve persisted data
      podcastEpisodeLibraryItemMap = mutableMapOf()
      serverLibraries = listOf()
      serverLibraryItems = mutableListOf()
      cachedLibraryAuthors = hashMapOf()
      cachedLibraryAuthorItems = hashMapOf()
      cachedLibraryAuthorSeriesItems = hashMapOf()
      cachedLibrarySeries = hashMapOf()
      cachedLibrarySeriesItem = hashMapOf()
      cachedLibraryCollections = hashMapOf()
      cachedLibraryRecentShelves = hashMapOf()
      cachedLibraryDiscovery = hashMapOf()
      cachedLibraryPodcasts = hashMapOf()
      isLibraryPodcastsCached = hashMapOf()
      serverItemsInProgress = listOf()
      allLibraryPersonalizationsDone = false

      // Reload persisted data for the new server connection
      if (serverConnConfig != null) {
        initializePersistedData()
      }

      return true
    }
    return false
  }

  fun loadItemsInProgressForAllLibraries(cb: (List<ItemInProgress>) -> Unit) {
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

  /**
   * Load personalized shelves from server for all libraries.
   * [cb] resolves when all libraries are processed
   */
  fun populatePersonalizedDataForAllLibraries(cb: () -> Unit ) {
    if (serverLibraries.isEmpty()) {
      Log.d(tag, "AABrowser: No libraries to populate personalization data for")
      allLibraryPersonalizationsDone = true
      cb()
      return
    }

    allLibraryPersonalizationsDone = false
    var totalLibraries = serverLibraries.size
    var completedLibraries = 0

    serverLibraries.forEach { library ->
      Log.d(tag, "AABrowser: Loading personalization for library ${library.name} - ${library.id} - ${library.mediaType}")
      populatePersonalizedDataForLibrary(library.id) {
        Log.d(tag, "AABrowser: Loaded personalization for library ${library.name} - ${library.id} - ${library.mediaType}")
        completedLibraries++
        if (completedLibraries >= totalLibraries) {
          Log.d(tag, "AABrowser: Finished loading all library personalization data")
          allLibraryPersonalizationsDone = true
          cb()
        }
      }
    }
  }

  /**
   * Get personalized shelves from server for selected [libraryId].
   * Populates [cachedLibraryRecentShelves] and [cachedLibraryDiscovery].
   */
  private fun populatePersonalizedDataForLibrary(libraryId: String, cb: () -> Unit) {
    apiHandler.getLibraryPersonalized(libraryId) { shelves ->
      Log.d(tag, "populatePersonalizedDataForLibrary $libraryId")
      if (shelves === null) return@getLibraryPersonalized
      shelves.map { shelf ->
        Log.d(tag, "$shelf")
        if (shelf.type == "book") {
          if (shelf.id == "continue-listening") return@map
          else if (shelf.id == "listen-again") return@map
          else if (shelf.id == "recently-added") {
            if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
              cachedLibraryRecentShelves[libraryId] = mutableListOf()
            }
            if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
              cachedLibraryRecentShelves[libraryId]!!.add(shelf)
            }
          }
          else if (shelf.id == "discover") {
            if (!cachedLibraryDiscovery.containsKey(libraryId)) {
              cachedLibraryDiscovery[libraryId] = mutableListOf()
            }
            (shelf as LibraryShelfBookEntity).entities?.map {
              cachedLibraryDiscovery[libraryId]!!.add(it)
            }
          }
          else if (shelf.id == "continue-reading") return@map
          else if (shelf.id == "continue-series") return@map
          shelf as LibraryShelfBookEntity
        } else if (shelf.type == "series") {
          if (shelf.id == "recent-series") {
            if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
              cachedLibraryRecentShelves[libraryId] = mutableListOf()
            }
            if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
              cachedLibraryRecentShelves[libraryId]!!.add(shelf)
            }
          }
        } else if (shelf.type == "episode") {
          if (shelf.id == "continue-listening") return@map
          else if (shelf.id == "listen-again") return@map
          else if (shelf.id == "newest-episodes") {
            if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
              cachedLibraryRecentShelves[libraryId] = mutableListOf()
            }
            if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
              cachedLibraryRecentShelves[libraryId]!!.add(shelf)
            }

            val podcastLibraryItemIds = mutableListOf<String>()
            (shelf as LibraryShelfEpisodeEntity).entities?.forEach { libraryItem ->
              if (!podcastLibraryItemIds.contains(libraryItem.id)) {
                podcastLibraryItemIds.add(libraryItem.id)
                loadPodcastItem(libraryItem.libraryId, libraryItem.id) {}
              }
            }
          }
        } else if (shelf.type == "podcast") {
          if (shelf.id == "recently-added"){
            if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
              cachedLibraryRecentShelves[libraryId] = mutableListOf()
            }
            if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
              cachedLibraryRecentShelves[libraryId]!!.add(shelf)
            }
          }
          else if (shelf.id == "discover"){
            return@map
          }
        } else if (shelf.type =="authors") {
          if (shelf.id == "newest-authors") {
            if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
              cachedLibraryRecentShelves[libraryId] = mutableListOf()
            }
            if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
              cachedLibraryRecentShelves[libraryId]!!.add(shelf)
            }
          }
        }

      }
      Log.d(tag, "populatePersonalizedDataForLibrary $libraryId DONE")
      cb()
    }
  }

  /**
   * Returns podcasts for selected library.
   * If data is not found from local cache it is loaded from server
   */
  fun loadLibraryPodcasts(libraryId:String, cb: (List<LibraryItem>?) -> Unit) {
    // Without this there is possibility that only recent podcasts get loaded
    // Loading recent podcasts will also create cachedLibraryPodcasts entry for library
    if (!isLibraryPodcastsCached.containsKey(libraryId)) {
      isLibraryPodcastsCached[libraryId] = false
    }
    // Ensure that there is map for library
    if (!cachedLibraryPodcasts.containsKey(libraryId)) {
      cachedLibraryPodcasts[libraryId] = mutableMapOf()
    }
    if (isLibraryPodcastsCached.getOrElse(libraryId) {false}) {
      Log.d(tag, "loadLibraryPodcasts: Found from cache: $libraryId")
      cb(cachedLibraryPodcasts[libraryId]?.values?.sortedBy { libraryItem -> (libraryItem.media as Podcast).metadata.title })
    } else {
      apiHandler.getLibraryItems(libraryId) { libraryItems ->
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        libraryItemsWithAudio.forEach { libraryItem ->
          cachedLibraryPodcasts[libraryId]?.set(libraryItem.id, libraryItem)
          if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
            serverLibraryItems.add(libraryItem)
          }
        }
        isLibraryPodcastsCached[libraryId] = true

        // Persist cached library items for Android Auto
        val serverConfigId = DeviceManager.serverConnectionConfigId
        if (serverConfigId.isNotEmpty()) {
          DeviceManager.dbManager.saveCachedLibraryItems(serverConfigId, libraryId, libraryItemsWithAudio)
          Log.d(tag, "loadLibraryPodcasts: Persisted ${libraryItemsWithAudio.size} podcast items for library $libraryId")
        }

        Log.d(tag, "loadLibraryPodcasts: loaded from server: $libraryId")
        cb(libraryItemsWithAudio.sortedBy { libraryItem -> (libraryItem.media as Podcast).metadata.title })
      }
    }
  }

  /**
   * Returns podcasts for selected library synchronously for Android Auto
   * If data is not found from local cache it is loaded from server
   */
  fun loadLibraryPodcastsSync(libraryId: String): List<LibraryItem>? {
    val future = SettableFuture.create<List<LibraryItem>?>()

    loadLibraryPodcasts(libraryId) { podcasts ->
      future.set(podcasts)
    }

    return try {
      future.get()
    } catch (e: Exception) {
      Log.e(tag, "AABrowser: Error loading library podcasts synchronously", e)
      null
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
   *  Returns series with audio books from selected library synchronously for Android Auto
   *  If data is not found from local cache then it will be fetched from server
   */
  fun loadLibrarySeriesWithAudioSync(libraryId: String): List<LibrarySeriesItem> {
    val future = SettableFuture.create<List<LibrarySeriesItem>>()

    loadLibrarySeriesWithAudio(libraryId) { series ->
      future.set(series)
    }

    return try {
      future.get()
    } catch (e: Exception) {
      Log.e(tag, "AABrowser: Error loading library series synchronously", e)
      emptyList()
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

  /**
   * Sorts books in series. Assumes that sequence is main.minor
   */
  private fun sortSeriesBooks(seriesBooks: List<LibraryItem>) : List<LibraryItem> {
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

        // Persist cached series items for Android Auto
        val serverConfigId = DeviceManager.serverConnectionConfigId
        if (serverConfigId.isNotEmpty()) {
          DeviceManager.dbManager.saveCachedSeriesItems(serverConfigId, seriesId, sortedLibraryItemsWithAudio)
          Log.d(tag, "loadLibrarySeriesItemsWithAudio: Persisted ${sortedLibraryItemsWithAudio.size} series items for series $seriesId")
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
   * Returns authors with books from library synchronously for Android Auto
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorsWithBooksSync(libraryId: String): List<LibraryAuthorItem> {
    val future = SettableFuture.create<List<LibraryAuthorItem>>()

    loadAuthorsWithBooks(libraryId) { authors ->
      future.set(authors)
    }

    return try {
      future.get()
    } catch (e: Exception) {
      Log.e(tag, "AABrowser: Error loading library authors synchronously", e)
      emptyList()
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

        // Persist cached author items for Android Auto (using authorId as cache key)
        val serverConfigId = DeviceManager.serverConnectionConfigId
        if (serverConfigId.isNotEmpty()) {
          DeviceManager.dbManager.saveCachedLibraryItems(serverConfigId, "${libraryId}_author_${authorId}", libraryItemsWithAudio)
          Log.d(tag, "loadAuthorBooksWithAudio: Persisted ${libraryItemsWithAudio.size} author items for author $authorId")
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

        // Persist cached author series items for Android Auto
        val serverConfigId = DeviceManager.serverConnectionConfigId
        if (serverConfigId.isNotEmpty()) {
          DeviceManager.dbManager.saveCachedLibraryItems(serverConfigId, "${libraryId}_author_${authorId}_series_${seriesId}", sortedLibraryItemsWithAudio)
          Log.d(tag, "loadAuthorSeriesBooksWithAudio: Persisted ${sortedLibraryItemsWithAudio.size} author series items for author $authorId series $seriesId")
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
   * Returns collections with audiobooks from library synchronously for Android Auto
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibraryCollectionsWithAudioSync(libraryId: String): List<LibraryCollection> {
    val future = SettableFuture.create<List<LibraryCollection>>()

    loadLibraryCollectionsWithAudio(libraryId) { collections ->
      future.set(collections)
    }

    return try {
      future.get()
    } catch (e: Exception) {
      Log.e(tag, "AABrowser: Error loading library collections synchronously", e)
      emptyList()
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

  /**
   * Returns audiobooks from discovery shelf for [libraryId]
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibraryDiscoveryBooksWithAudio(libraryId: String, cb: (List<LibraryItem>) -> Unit) {
    if (!cachedLibraryDiscovery.containsKey(libraryId)) {
      cb(listOf())
      return
    }
    val libraryItemsWithAudio = cachedLibraryDiscovery[libraryId]?.filter { li -> li.checkHasTracks() } ?: listOf()
    libraryItemsWithAudio.forEach { libraryItem -> addServerLibrary(libraryItem) }
    cb(libraryItemsWithAudio)
  }

  /**
   * Returns library discovery books with audio synchronously for Android Auto
   */
  fun loadLibraryDiscoveryBooksWithAudioSync(libraryId: String): List<LibraryItem> {
    if (!cachedLibraryDiscovery.containsKey(libraryId)) {
      return listOf()
    }
    val libraryItemsWithAudio = cachedLibraryDiscovery[libraryId]?.filter { li -> li.checkHasTracks() } ?: listOf()
    libraryItemsWithAudio.forEach { libraryItem -> addServerLibrary(libraryItem) }
    return libraryItemsWithAudio
  }

  /**
   * Returns cached library discovery items for [libraryId]
   * If no items are cached returns empty list
   */
  fun getCachedLibraryDiscoveryItems(libraryId: String): List<LibraryItem> {
    return cachedLibraryDiscovery[libraryId] ?: listOf()
  }

  /**
   * Returns all cached library recent shelves
   */
  fun getAllCachedLibraryRecentShelves(): Map<String, MutableList<LibraryShelfType>> {
    return cachedLibraryRecentShelves
  }

  /**
   * Returns true if any recent shelves are loaded
   */
  fun hasRecentShelvesLoaded(): Boolean {
    return cachedLibraryRecentShelves.isNotEmpty() && cachedLibraryRecentShelves.values.any { it.isNotEmpty() }
  }

  /**
   * Returns recent shelves for [libraryId]
   * If data is not shelves are found returns empty list
   */
  fun getLibraryRecentShelfs(libraryId: String, cb: (List<LibraryShelfType>) -> Unit) {
    if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
      Log.d(tag, "getLibraryRecentShelfs: No shelves $libraryId")
      cb(listOf())
      return
    }
    cb(cachedLibraryRecentShelves[libraryId] as List<LibraryShelfType>)
  }

  /**
   * Returns recent shelf by [type] for [libraryId]
   * If shelf is not found returns null
   */
  fun getLibraryRecentShelfByType(libraryId: String, type:String, cb: (LibraryShelfType?) -> Unit) {
    Log.d(tag, "getLibraryRecentShelfByType: $libraryId | $type")
    if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
      cb(null)
      return
    }
    for (shelf in cachedLibraryRecentShelves[libraryId]!!) {
      if (shelf.type == type.lowercase()) {
        cb(shelf)
        return
      }
    }
    cb(null)
  }

  /**
   * Loads podcasts for newest episodes shelf
   */
  private fun loadPodcastItem(libraryId: String, libraryItemId: String, cb: (LibraryItem?) -> Unit) {
    // Ensure that there is map for library
    if (!cachedLibraryPodcasts.containsKey(libraryId)) {
      cachedLibraryPodcasts[libraryId] = mutableMapOf()
    }
    if (cachedLibraryPodcasts[libraryId]!!.containsKey(libraryItemId)) {
      Log.d(tag, "loadPodcastItem: Podcast found from cache | Library $libraryItemId ")
      cb(cachedLibraryPodcasts[libraryId]?.get(libraryItemId))
    } else {
      Log.d(tag, "loadPodcastItem: Calling getLibraryItem $libraryItemId")
      apiHandler.getLibraryItem(libraryItemId) { libraryItem ->
        if (libraryItem !== null) {
          Log.d(tag, "loadPodcastItem: Got library item ${libraryItem.id} ${libraryItem.media.metadata.title}")
          val podcast = libraryItem.media as Podcast
          podcast.episodes?.forEach { podcastEpisode ->
            podcastEpisodeLibraryItemMap[podcastEpisode.id] = LibraryItemWithEpisode(libraryItem, podcastEpisode)
          }
          cachedLibraryPodcasts[libraryId]?.set(libraryItemId, libraryItem)
          cb(libraryItem)
        }
      }
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
              val episodes = podcast.episodes?.sortedByDescending { it.publishedAt }
              val children = episodes?.map { podcastEpisode ->

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

  /**
   * Loads libraries for selected server with stats
   */
  private fun loadLibraries(cb: (List<Library>) -> Unit) {
    if (serverLibraries.isNotEmpty()) {
      Log.d(tag, "AABrowser: Using cached libraries, count=${serverLibraries.size}")
      cb(serverLibraries)
    } else {
      Log.d(tag, "AABrowser: Loading libraries from API")
      apiHandler.getLibraries { loadedLibraries ->
        Log.d(tag, "AABrowser: API returned ${loadedLibraries.size} libraries")
        serverLibraries = loadedLibraries

        // Persist server libraries for Android Auto
        val serverConfigId = DeviceManager.serverConnectionConfigId
        if (serverConfigId.isNotEmpty()) {
          DeviceManager.dbManager.saveServerLibraries(serverConfigId, loadedLibraries)
          Log.d(tag, "AABrowser: Persisted ${loadedLibraries.size} libraries for server $serverConfigId")
        }

        // Notify Android Auto listeners that libraries have been loaded
        androidAutoLoadListeners.forEach { listener ->
          try {
            listener()
          } catch (e: Exception) {
            Log.e(tag, "AABrowser: androidAutoLoadListener error: ${e.localizedMessage}")
          }
        }
        cb(serverLibraries)
      }
    }
  }

  fun registerAndroidAutoLoadListener(listener: () -> Unit) {
    androidAutoLoadListeners.add(listener)
  }

  fun unregisterAndroidAutoLoadListener(listener: () -> Unit) {
    androidAutoLoadListeners.remove(listener)
  }

  fun clearAndroidAutoLoadListeners() {
    androidAutoLoadListeners = mutableListOf()
  }

  /**
   * Load libraries synchronously for Android Auto
   */
  fun loadLibrariesSync(): List<Library> {
    val future = SettableFuture.create<List<Library>>()

    loadLibraries { libraries ->
      future.set(libraries)
    }

    return try {
      future.get()
    } catch (e: Exception) {
      Log.e(tag, "AABrowser: Error loading libraries synchronously", e)
      emptyList()
    }
  }

  /**
   * Load libraries asynchronously for Android Auto
   */
  fun loadLibrariesAsync(cb: (List<Library>) -> Unit) {
    loadLibraries(cb)
  }

  /**
   * Load items in progress synchronously for Android Auto
   */
  fun loadItemsInProgressSync(): List<ItemInProgress> {
    val future = SettableFuture.create<List<ItemInProgress>>()
    val combinedItems = mutableListOf<ItemInProgress>()

    // First, get local books with progress
    val localBooksWithProgress = getLocalBooksWithProgress()
    Log.d(tag, "AABrowser: Found ${localBooksWithProgress.size} local books with progress")

    // Add local books as ItemInProgress objects
    localBooksWithProgress.forEach { localBook ->
      val libraryItemId = localBook.libraryItemId ?: return@forEach
      val localProgress = DeviceManager.dbManager.getLocalMediaProgress(libraryItemId)
      if (localProgress != null) {
        val itemInProgress = ItemInProgress(
          libraryItemWrapper = localBook,
          episode = null,
          progressLastUpdate = localProgress.lastUpdate,
          isLocal = true
        )
        combinedItems.add(itemInProgress)
      }
    }

    // Then, try to get server items if available
    if (serverItemsInProgress.isNotEmpty()) {
      Log.d(tag, "AABrowser: Using cached server items in progress, count=${serverItemsInProgress.size}")

      // Add server items, but avoid duplicates (prefer server progress over local for same item)
      serverItemsInProgress.forEach { serverItem ->
        val existingLocalIndex = combinedItems.indexOfFirst {
          it.libraryItemWrapper.id == serverItem.libraryItemWrapper.id
        }

        if (existingLocalIndex >= 0) {
          // Replace local item with server item (server progress takes precedence)
          combinedItems[existingLocalIndex] = serverItem
          Log.d(tag, "AABrowser: Replaced local progress with server progress for item ${serverItem.libraryItemWrapper.id}")
        } else {
          // Add new server item
          combinedItems.add(serverItem)
        }
      }
    } else {
      Log.d(tag, "AABrowser: No cached server items, trying to load from API")
      apiHandler.getAllItemsInProgress { itemsInProgress ->
        val filteredItemsInProgress = itemsInProgress.filter {
          val libraryItem = it.libraryItemWrapper as LibraryItem
          libraryItem.checkHasTracks()
        }

        serverItemsInProgress = filteredItemsInProgress
        Log.d(tag, "AABrowser: Loaded ${filteredItemsInProgress.size} server items in progress")

        // Merge server items with existing local items, avoiding duplicates
        filteredItemsInProgress.forEach { serverItem ->
          val existingLocalIndex = combinedItems.indexOfFirst {
            it.libraryItemWrapper.id == serverItem.libraryItemWrapper.id
          }

          if (existingLocalIndex >= 0) {
            // Replace local item with server item (server progress takes precedence)
            combinedItems[existingLocalIndex] = serverItem
            Log.d(tag, "AABrowser: Replaced local progress with server progress for item ${serverItem.libraryItemWrapper.id}")
          } else {
            // Add new server item
            combinedItems.add(serverItem)
          }
        }

        // Sort by last played time (most recent first)
        val sortedItems = combinedItems.sortedByDescending { it.progressLastUpdate }
        future.set(sortedItems)
      }

      return try {
        future.get()
      } catch (e: Exception) {
        Log.e(tag, "AABrowser: Error loading server items in progress, returning local items only", e)
        // Return local items only if server fails
        combinedItems.sortedByDescending { it.progressLastUpdate }
      }
    }

    // Sort by last played time (most recent first) and return
    return combinedItems.sortedByDescending { it.progressLastUpdate }
  }

  /**
   * Get local books with progress for continue section
   */
  fun getLocalBooksWithProgress(): List<LocalLibraryItem> {
    val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
    return localBooks.filter { localBook ->
      val libraryItemId = localBook.libraryItemId ?: return@filter false
      val localProgress = DeviceManager.dbManager.getLocalMediaProgress(libraryItemId)
      localProgress != null && localProgress.currentTime > 0 && localProgress.duration > 0
    }.sortedByDescending { localBook ->
      // Sort by last update time (most recent first)
      val libraryItemId = localBook.libraryItemId ?: return@sortedByDescending 0L
      val localProgress = DeviceManager.dbManager.getLocalMediaProgress(libraryItemId)
      localProgress?.lastUpdate ?: 0L
    }
  }

  /**
   * Load items in progress asynchronously for Android Auto
   */
  fun loadItemsInProgressAsync(cb: (List<ItemInProgress>) -> Unit) {
    if (serverItemsInProgress.isNotEmpty()) {
      Log.d(tag, "AABrowser: Using cached items in progress, count=${serverItemsInProgress.size}")
      cb(serverItemsInProgress)
    } else {
      Log.d(tag, "AABrowser: Loading items in progress from API")
      apiHandler.getCurrentUser { user ->
        if (user != null && user.mediaProgress.isNotEmpty()) {
          Log.d(tag, "AABrowser: API returned ${user.mediaProgress.size} media progress items")
          serverUserMediaProgress = user.mediaProgress.toMutableList()

          // Convert to items in progress
          val itemsInProgress = user.mediaProgress.mapNotNull { progress ->
            val libraryItem = serverLibraryItems.find { it.id == progress.libraryItemId }
            if (libraryItem != null) {
              ItemInProgress(libraryItem, null, progress.lastUpdate, false)
            } else {
              null
            }
          }

          serverItemsInProgress = itemsInProgress
          Log.d(tag, "AABrowser: Converted to ${itemsInProgress.size} items in progress")
          cb(itemsInProgress)
        } else {
          Log.d(tag, "AABrowser: No user data or media progress available")
          cb(emptyList())
        }
      }
    }
  }

  /**
   * Load recent items synchronously for Android Auto
   */
  fun loadRecentItemsSync(): List<LibraryItem> {
    val future = SettableFuture.create<List<LibraryItem>>()

    // First, ensure personalized data is loaded for all libraries
    if (!allLibraryPersonalizationsDone) {
      Log.d(tag, "AABrowser: Loading personalized data for recent items")
      populatePersonalizedDataForAllLibraries {
        // After personalized data is loaded, get recent items
        val recentItems = getRecentItemsFromShelves()
        future.set(recentItems)
      }
    } else {
      // Personalized data already loaded, get recent items directly
      val recentItems = getRecentItemsFromShelves()
      future.set(recentItems)
    }

    return try {
      future.get()
    } catch (e: Exception) {
      Log.e(tag, "AABrowser: Error loading recent items synchronously", e)
      emptyList()
    }
  }

  /**
   * Helper method to extract recent items from loaded shelves
   */
  private fun getRecentItemsFromShelves(): List<LibraryItem> {
    val recentItems = mutableListOf<LibraryItem>()

    // Load recent items from all libraries
    val allRecentShelves = getAllCachedLibraryRecentShelves()
    allRecentShelves.values.forEach { shelves ->
      shelves.forEach { shelf ->
        when (shelf) {
          is LibraryShelfBookEntity -> {
            shelf.entities?.forEach { book ->
              if (recentItems.find { it.id == book.id } == null) {
                recentItems.add(book)
              }
            }
          }
          is LibraryShelfPodcastEntity -> {
            shelf.entities?.forEach { podcast ->
              if (recentItems.find { it.id == podcast.id } == null) {
                recentItems.add(podcast)
              }
            }
          }
          else -> {
            // Handle other shelf types or ignore
          }
        }
      }
    }

    Log.d(tag, "AABrowser: Found ${recentItems.size} recent items from personalized shelves")
    return recentItems
  }

  /**
   * Load recent items asynchronously for Android Auto
   */
  fun loadRecentItemsAsync(cb: (List<LibraryItem>) -> Unit) {
    val recentItems = mutableListOf<LibraryItem>()

    // Load recent items from all libraries
    val allRecentShelves = getAllCachedLibraryRecentShelves()
    allRecentShelves.values.forEach { shelves ->
      shelves.forEach { shelf ->
        when (shelf) {
          is LibraryShelfBookEntity -> {
            shelf.entities?.forEach { book ->
              if (recentItems.find { it.id == book.id } == null) {
                recentItems.add(book)
              }
            }
          }
          is LibraryShelfPodcastEntity -> {
            shelf.entities?.forEach { podcast ->
              if (recentItems.find { it.id == podcast.id } == null) {
                recentItems.add(podcast)
              }
            }
          }
          else -> {
            // Handle other shelf types or ignore
          }
        }
      }
    }

    if (recentItems.isNotEmpty()) {
      Log.d(tag, "AABrowser: Using cached recent items, count=${recentItems.size}")
      cb(recentItems)
    } else {
      // If no cached recent items, load from discovery shelves
      var loadedCount = 0
      val totalLibraries = serverLibraries.count { (it.stats?.numAudioFiles ?: 0) > 0 }

      if (totalLibraries == 0) {
        Log.d(tag, "AABrowser: No libraries to load recent items from")
        cb(emptyList())
        return
      }

      serverLibraries.forEach { library ->
        if ((library.stats?.numAudioFiles ?: 0) > 0) {
          loadLibraryDiscoveryBooksWithAudio(library.id) {
            loadedCount++
            Log.d(tag, "AABrowser: Loaded discovery for ${library.name} ($loadedCount/$totalLibraries)")

            if (loadedCount >= totalLibraries) {
              // Re-try loading recent items after discovery is loaded
              val finalRecentItems = mutableListOf<LibraryItem>()
              val finalRecentShelves = getAllCachedLibraryRecentShelves()
              finalRecentShelves.values.forEach { shelves ->
                shelves.forEach { shelf ->
                  when (shelf) {
                    is LibraryShelfBookEntity -> {
                      shelf.entities?.forEach { book ->
                        if (finalRecentItems.find { it.id == book.id } == null) {
                          finalRecentItems.add(book)
                        }
                      }
                    }
                    is LibraryShelfPodcastEntity -> {
                      shelf.entities?.forEach { podcast ->
                        if (finalRecentItems.find { it.id == podcast.id } == null) {
                          finalRecentItems.add(podcast)
                        }
                      }
                    }
                    else -> {
                      // Handle other shelf types or ignore
                    }
                  }
                }
              }
              Log.d(tag, "AABrowser: Final recent items count=${finalRecentItems.size}")
              cb(finalRecentItems)
            }
          }
        }
      }
    }
  }

  /**
   * Load library contents synchronously for Android Auto
   */
  fun loadLibraryContentsSync(libraryId: String): List<LibraryItem> {
    val future = SettableFuture.create<List<LibraryItem>>()

    val library = serverLibraries.find { it.id == libraryId }
    if (library == null) {
      Log.w(tag, "AABrowser: Library $libraryId not found")
      return emptyList()
    }

    if (library.mediaType == "podcast") {
      // For podcast libraries, load podcasts
      loadLibraryPodcasts(libraryId) { podcasts ->
        val podcastItems = podcasts ?: emptyList()
        Log.d(tag, "AABrowser: Loaded ${podcastItems.size} podcasts for library $libraryId")
        future.set(podcastItems)
      }
    } else {
      // For book libraries, load from discovery shelf
      loadLibraryDiscoveryBooksWithAudio(libraryId) {
        val discoveryItems = cachedLibraryDiscovery[libraryId] ?: emptyList()
        Log.d(tag, "AABrowser: Loaded ${discoveryItems.size} discovery items for library $libraryId")
        future.set(discoveryItems)
      }
    }

    return try {
      future.get()
    } catch (e: Exception) {
      Log.e(tag, "AABrowser: Error loading library contents synchronously", e)
      emptyList()
    }
  }

  /**
   * Load library contents asynchronously for Android Auto
   */
  fun loadLibraryContentsAsync(libraryId: String, cb: (List<LibraryItem>) -> Unit) {
    val library = serverLibraries.find { it.id == libraryId }
    if (library == null) {
      Log.w(tag, "AABrowser: Library $libraryId not found")
      cb(emptyList())
      return
    }

    if (library.mediaType == "podcast") {
      // For podcast libraries, load podcasts
      loadLibraryPodcasts(libraryId) { podcasts ->
        val podcastItems = podcasts ?: emptyList()
        Log.d(tag, "AABrowser: Loaded ${podcastItems.size} podcasts for library $libraryId")
        cb(podcastItems)
      }
    } else {
      // For book libraries, load from discovery shelf
      loadLibraryDiscoveryBooksWithAudio(libraryId) {
        val discoveryItems = cachedLibraryDiscovery[libraryId] ?: emptyList()
        Log.d(tag, "AABrowser: Loaded ${discoveryItems.size} discovery items for library $libraryId")
        cb(discoveryItems)
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
    Log.d(tag, "checkSetValidServerConnectionConfig | DeviceManager.serverConnectionConfig=${DeviceManager.serverConnectionConfig?.name}")
    Log.d(tag, "checkSetValidServerConnectionConfig | DeviceManager.isConnectedToServer=${DeviceManager.isConnectedToServer}")

    coroutineScope {
      if (!DeviceManager.checkConnectivity(ctx)) {
        serverUserMediaProgress = mutableListOf()
        Log.d(tag, "checkSetValidServerConnectionConfig: No connectivity")
        cb(false)
      } else if (DeviceManager.deviceData.lastServerConnectionConfigId.isNullOrBlank()) { // If in offline mode last server connection config is unset
        serverUserMediaProgress = mutableListOf()
        Log.d(tag, "checkSetValidServerConnectionConfig: No last server connection config")
        Log.d(tag, "checkSetValidServerConnectionConfig: Available server configs: ${DeviceManager.deviceData.serverConnectionConfigs.size}")
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

  fun initializeInProgressItems(cb: () -> Unit) {
    Log.d(tag, "Initializing inprogress items")

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
      Log.d(tag, "Initializing inprogress items done")
      cb()
    }
  }

  fun loadAndroidAutoItems(cb: () -> Unit) {
    Log.d(tag, "AABrowser: Load android auto items")

    // Initialize persisted data first
    initializePersistedData()

    // Check if any valid server connection if not use locally downloaded books
    checkSetValidServerConnectionConfig { isConnected ->
      if (isConnected) {
        serverConfigIdUsed = DeviceManager.serverConnectionConfigId
        Log.d(tag, "AABrowser: Connected to server config id=$serverConfigIdUsed")

        loadLibraries { libraries ->
          Log.d(tag, "AABrowser: Libraries loaded, count=${libraries.size}")
          if (libraries.isEmpty()) {
            Log.w(tag, "AABrowser: No libraries returned from server request")
            cb()
          } else {
            Log.d(tag, "AABrowser: Libraries loaded successfully, now loading continue items and recent shelves")

            // Load continue items
            initializeInProgressItems {
              Log.d(tag, "AABrowser: Continue items loaded, count=${serverItemsInProgress.size}")

              // Load recent shelves for all libraries
              populatePersonalizedDataForAllLibraries {
                Log.d(tag, "AABrowser: Recent shelves loaded for all libraries")
                cb() // Fully loaded
              }
            }
          }
        }
      } else { // Not connected to server
        Log.d(tag, "AABrowser: Not connected to server")
        cb()
      }
    }
  }

  /**
   * Handles search requests.
   * Searches from books, series and authors
   */
  suspend fun doSearch(libraryId: String, queryString: String) : Map<String, List<MediaBrowserCompat.MediaItem>> {
    return suspendCoroutine {
      apiHandler.getSearchResults(libraryId, queryString) { searchResult ->
        Log.d(tag, "searchLocalCache: $searchResult")
        // Nothing found from server
        if (searchResult === null) {
          it.resume(mapOf())
          return@getSearchResults
        }

        val foundItems: MutableMap<String, List<MediaBrowserCompat.MediaItem>> = mutableMapOf()

        val serverLibrary = serverLibraries.find { sl -> sl.id == libraryId }

        // Books
        if (searchResult.book !== null && searchResult.book!!.isNotEmpty()) {
          Log.d(tag, "searchLocalCache: found ${searchResult.book!!.size} books")
          val children = searchResult.book!!.filter { it.libraryItem.checkHasTracks() }.map { bookResult ->
            val libraryItem = bookResult.libraryItem

            if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
              serverLibraryItems.add(libraryItem)
            }
            val progress = serverUserMediaProgress.find { it.libraryItemId == libraryItem.id }
            val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
            libraryItem.localLibraryItemId = localLibraryItem?.id
            val description = libraryItem.getMediaDescription(progress, ctx, null, null, "Books (${serverLibrary?.name})")

            // Make books with chapters browsable instead of playable
            val flagToUse = if (libraryItem.mediaType == "book" &&
                              (libraryItem.media as? Book)?.chapters?.isNotEmpty() == true) {
              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            } else {
              MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            }
            MediaBrowserCompat.MediaItem(description, flagToUse)
          }
          foundItems["book"] = children
        }
        if (searchResult.series !== null && searchResult.series!!.isNotEmpty()) {
          Log.d(tag, "onSearch: found ${searchResult.series!!.size} series")
          val children = searchResult.series!!.map { seriesResult ->
            val seriesItem = seriesResult.series
            seriesItem.books = seriesResult.books as MutableList<LibraryItem>
            val description = seriesItem.getMediaDescription(null, ctx, "Series (${serverLibrary?.name})")
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
          }
          foundItems["series"] = children
        }
        if (searchResult.authors !== null && searchResult.authors!!.isNotEmpty()) {
          Log.d(tag, "onSearch: found ${searchResult.authors!!.size} authors")
          val children = searchResult.authors!!.map { authorItem ->
            val description = authorItem.getMediaDescription(null, ctx, "Authors (${serverLibrary?.name})")
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
          }
          foundItems["authors"] = children
        }

        it.resume(foundItems)
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

  /**
   * Get library item by ID with fallback to server if not found in cache
   * This is used for Android Auto playback when the item might not be cached
   */
  fun getByIdSync(id: String): LibraryItemWrapper? {
    return if (id.startsWith("local")) {
      DeviceManager.dbManager.getLocalLibraryItem(id)
    } else {
      // First try to find in cache
      var item = serverLibraryItems.find { it.id == id }
      if (item != null) {
        Log.d(tag, "Found item $id in cache")
        return item
      }

      // If not found in cache, try to fetch from server synchronously
      Log.d(tag, "Item $id not found in cache (${serverLibraryItems.size} items cached), fetching from server...")
      val future = SettableFuture.create<LibraryItem?>()

      loadLibraryItem(id) { libraryItem ->
        if (libraryItem != null && libraryItem is LibraryItem) {
          // Add to cache for future use
          addServerLibrary(libraryItem)
          Log.d(tag, "Successfully fetched and cached item $id from server")
          future.set(libraryItem)
        } else {
          Log.e(tag, "Failed to fetch item $id from server")
          future.set(null)
        }
      }

      try {
        item = future.get(5, TimeUnit.SECONDS)
      } catch (e: Exception) {
        Log.e(tag, "Failed to fetch library item $id from server: ${e.message}")
        return null
      }
      item
    }
  }

  fun getFromSearch(query:String?) : LibraryItemWrapper? {
    if (query.isNullOrEmpty()) return getFirstItem()
    return serverLibraryItems.find {
      it.title.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))
    }
  }

  /**
   * Get progress percentage for a library item
   */
  fun getProgressPercentage(libraryItemId: String, episodeId: String? = null): Int {
    // First check server progress
    val serverProgress = if (episodeId != null) {
      // For podcast episodes
      serverUserMediaProgress.find { it.libraryItemId == libraryItemId && it.episodeId == episodeId }
    } else {
      // For books
      serverUserMediaProgress.find { it.libraryItemId == libraryItemId }
    }

    if (serverProgress != null) {
      return (serverProgress.progress * 100).toInt()
    }

    // If no server progress, check local media progress
    val localMediaProgressId = if (episodeId != null) {
      "$libraryItemId-$episodeId"
    } else {
      libraryItemId
    }

    val localProgress = DeviceManager.dbManager.getLocalMediaProgress(localMediaProgressId)
    return if (localProgress != null && localProgress.duration > 0) {
      ((localProgress.currentTime / localProgress.duration) * 100).toInt()
    } else {
      0
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

  fun fetchAllDataForAndroidAuto(cb: () -> Unit) {
    Log.d(tag, "AABrowser: Starting to fetch all data")
    populatePersonalizedDataForAllLibraries {
        Log.d(tag, "AABrowser: Personalized data loaded, loading in-progress items")
        initializeInProgressItems {
            Log.d(tag, "AABrowser: All data loaded, calling callback")
            cb()
        }
    }
  }

  // Cache accessor methods for Media3 Android Auto browser

  /**
   * Get cached authors for a library, return empty list if not cached
   * If no cached data, trigger async loading for next time
   */
  fun getCachedAuthors(libraryId: String): List<LibraryAuthorItem> {
    val cached = cachedLibraryAuthors[libraryId]?.values?.toList()
    if (cached.isNullOrEmpty()) {
      // Trigger async loading for next browsing session
      Log.d(tag, "AABrowser: No cached authors for $libraryId, triggering load")
      loadAuthorsWithBooks(libraryId) { authors ->
        Log.d(tag, "AABrowser: Loaded ${authors.size} authors for $libraryId")
      }
      return emptyList()
    }
    return cached
  }

  /**
   * Get cached books for a specific author, return empty list if not cached
   * If no cached data, trigger async loading for next time
   */
  fun getCachedAuthorBooks(libraryId: String, authorId: String): List<LibraryItem> {
    val cached = cachedLibraryAuthorItems[libraryId]?.get(authorId)
    if (cached.isNullOrEmpty()) {
      // Trigger async loading for next browsing session
      Log.d(tag, "AABrowser: No cached author books for $libraryId/$authorId, triggering load")
      loadAuthorBooksWithAudio(libraryId, authorId) { books ->
        Log.d(tag, "AABrowser: Loaded ${books.size} books for author $authorId")
      }
      return emptyList()
    }
    return cached
  }

  /**
   * Get cached series for a library, return empty list if not cached
   * If no cached data, trigger async loading for next time
   */
  fun getCachedSeries(libraryId: String): List<LibrarySeriesItem> {
    val cached = cachedLibrarySeries[libraryId]
    if (cached.isNullOrEmpty()) {
      // Trigger async loading for next browsing session
      Log.d(tag, "AABrowser: No cached series for $libraryId, triggering load")
      loadLibrarySeriesWithAudio(libraryId) { series ->
        Log.d(tag, "AABrowser: Loaded ${series.size} series for $libraryId")
      }
      return emptyList()
    }
    return cached
  }

  /**
   * Get cached books for a specific series, return empty list if not cached
   * If no cached data, trigger async loading for next time
   */
  fun getCachedSeriesBooks(libraryId: String, seriesId: String): List<LibraryItem> {
    val cached = cachedLibrarySeriesItem[libraryId]?.get(seriesId)
    if (cached.isNullOrEmpty()) {
      // Trigger async loading for next browsing session
      Log.d(tag, "AABrowser: No cached series books for $libraryId/$seriesId, triggering load")
      loadLibrarySeriesItemsWithAudio(libraryId, seriesId) { books ->
        Log.d(tag, "AABrowser: Loaded ${books.size} books for series $seriesId")
      }
      return emptyList()
    }
    return cached
  }

  /**
   * Get cached collections for a library, return empty list if not cached
   * If no cached data, trigger async loading for next time
   */
  fun getCachedCollections(libraryId: String): List<LibraryCollection> {
    val cached = cachedLibraryCollections[libraryId]?.values?.toList()
    if (cached.isNullOrEmpty()) {
      // Trigger async loading for next browsing session
      Log.d(tag, "AABrowser: No cached collections for $libraryId, triggering load")
      loadLibraryCollectionsWithAudio(libraryId) { collections ->
        Log.d(tag, "AABrowser: Loaded ${collections.size} collections for $libraryId")
      }
      return emptyList()
    }
    return cached
  }

  /**
   * Get cached books for a specific collection, return empty list if not cached
   */
  fun getCachedCollectionBooks(libraryId: String, collectionId: String): List<LibraryItem> {
    val collection = cachedLibraryCollections[libraryId]?.get(collectionId)
    if (collection?.books.isNullOrEmpty()) {
      // Trigger async loading for next browsing session
      Log.d(tag, "AABrowser: No cached collection books for $libraryId/$collectionId, triggering load")
      loadLibraryCollectionBooksWithAudio(libraryId, collectionId) { books ->
        Log.d(tag, "AABrowser: Loaded ${books.size} books for collection $collectionId")
      }
      return emptyList()
    }
    return collection?.books ?: emptyList()
  }

  /**
   * Get cached discovery items for a library, return empty list if not cached
   * If no cached data, trigger async loading for next time
   */
  fun getCachedDiscoveryItems(libraryId: String): List<LibraryItem> {
    val cached = cachedLibraryDiscovery[libraryId]
    if (cached.isNullOrEmpty()) {
      // Trigger async loading for next browsing session
      Log.d(tag, "AABrowser: No cached discovery for $libraryId, triggering load")
      loadLibraryDiscoveryBooksWithAudio(libraryId) { books ->
        Log.d(tag, "AABrowser: Loaded ${books.size} discovery books for $libraryId")
      }
      return emptyList()
    }
    return cached
  }

  /**
   * Get cached podcasts for a library, return empty list if not cached
   * If no cached data, trigger async loading for next time
   */
  fun getCachedPodcasts(libraryId: String): List<LibraryItem> {
    val cached = cachedLibraryPodcasts[libraryId]?.values?.toList()
    if (cached.isNullOrEmpty()) {
      // Trigger async loading for next browsing session
      Log.d(tag, "AABrowser: No cached podcasts for $libraryId, triggering load")
      loadLibraryPodcasts(libraryId) { podcasts ->
        Log.d(tag, "AABrowser: Loaded ${podcasts?.size ?: 0} podcasts for $libraryId")
      }
      return emptyList()
    }
    return cached
  }

  /**
   * Get cached books for a specific author in a specific series, return empty list if not cached
   */
  fun getCachedAuthorSeriesBooks(libraryId: String, authorId: String, seriesId: String): List<LibraryItem> {
    val authorSeriesKey = "$authorId|$seriesId"
    return cachedLibraryAuthorSeriesItems[libraryId]?.get(authorSeriesKey) ?: emptyList()
  }

  /**
   * Check if we have any cached data for Android Auto browsing
   */
  fun hasCachedBrowsingData(): Boolean {
    return cachedLibraryAuthors.isNotEmpty() ||
           cachedLibrarySeries.isNotEmpty() ||
           cachedLibraryCollections.isNotEmpty() ||
           cachedLibraryDiscovery.isNotEmpty()
  }

  /**
   * Ensure server connection is established for Android Auto
   * This is specifically for Android Auto initialization to check and establish server connection
   */
  fun ensureServerConnectionForAndroidAuto(cb: () -> Unit) {
    Log.d(tag, "AABrowser: Ensuring server connection for Android Auto")

    // If we already have a valid connection, just call the callback
    if (hasValidServerConnection()) {
      Log.d(tag, "AABrowser: Already have valid server connection")
      cb()
      return
    }

    // Initialize persisted data first
    initializePersistedData()

    // Try to establish server connection
    checkSetValidServerConnectionConfig { isConnected ->
      if (isConnected) {
        serverConfigIdUsed = DeviceManager.serverConnectionConfigId
        Log.d(tag, "AABrowser: Server connection established for Android Auto - config id=$serverConfigIdUsed")
      } else {
        Log.d(tag, "AABrowser: No server connection available for Android Auto")
      }
      cb()
    }
  }

  /**
   * Pre-load essential data for Android Auto browsing
   * This should be called when Android Auto connects to ensure we have data available
   */
  fun preloadAndroidAutoBrowsingData(cb: () -> Unit) {
    Log.d(tag, "AABrowser: Pre-loading essential browsing data")

    // Check if we have a valid server connection before attempting to load data
    if (!hasValidServerConnection()) {
      Log.w(tag, "AABrowser: No valid server connection, skipping server data loading")
      cb()
      return
    }

    // First ensure we have libraries loaded
    if (serverLibraries.isEmpty()) {
      Log.d(tag, "AABrowser: No libraries available, attempting to load from server")
      loadLibraries { libraries ->
        if (libraries.isNotEmpty()) {
          Log.d(tag, "AABrowser: Loaded ${libraries.size} libraries, now loading browsing data")
          loadPersonalizedAndLibraryData(cb)
        } else {
          Log.w(tag, "AABrowser: No libraries available from server")
          cb()
        }
      }
      return
    }

    loadPersonalizedAndLibraryData(cb)
  }

  private fun loadPersonalizedAndLibraryData(cb: () -> Unit) {
    // First load personalized data (for recent items and continue listening)
    Log.d(tag, "AABrowser: Loading personalized data for all libraries")
    populatePersonalizedDataForAllLibraries {
      Log.d(tag, "AABrowser: Personalized data loaded, now loading library browsing data")
      preloadLibraryData(cb)
    }
  }

  private fun preloadLibraryData(cb: () -> Unit) {
    var loadingCount = 0
    var completedCount = 0

    // Count libraries that need data loading
    serverLibraries.forEach { library ->
      if (library.stats?.numAudioFiles ?: 0 > 0) {
        if (library.mediaType == "podcast") {
          loadingCount += 1 // Only podcasts for podcast libraries
        } else {
          loadingCount += 4 // authors, series, collections, discovery for book libraries
        }
      }
    }

    if (loadingCount == 0) {
      Log.d(tag, "AABrowser: No libraries to load data for")
      cb()
      return
    }

    val checkComplete = {
      completedCount++
      Log.d(tag, "AABrowser: Completed $completedCount/$loadingCount data loads")
      if (completedCount >= loadingCount) {
        Log.d(tag, "AABrowser: All essential browsing data loaded")
        cb()
      }
    }

    // Load essential data for each library
    serverLibraries.forEach { library ->
      if (library.stats?.numAudioFiles ?: 0 > 0) {
        if (library.mediaType == "podcast") {
          // Load podcasts for podcast library
          loadLibraryPodcasts(library.id) {
            Log.d(tag, "AABrowser: Loaded podcasts for ${library.name}")
            checkComplete()
          }
        } else {
          // Load all categories for book library
          loadAuthorsWithBooks(library.id) {
            Log.d(tag, "AABrowser: Loaded authors for ${library.name}")
            checkComplete()
          }

          loadLibrarySeriesWithAudio(library.id) {
            Log.d(tag, "AABrowser: Loaded series for ${library.name}")
            checkComplete()
          }

          loadLibraryCollectionsWithAudio(library.id) {
            Log.d(tag, "AABrowser: Loaded collections for ${library.name}")
            checkComplete()
          }

          loadLibraryDiscoveryBooksWithAudio(library.id) {
            Log.d(tag, "AABrowser: Loaded discovery for ${library.name}")
            checkComplete()
          }
        }
      }
    }
  }

  /**
   * Check if we have a valid server connection and libraries
   */
  fun hasValidServerConnection(): Boolean {
    return DeviceManager.isConnectedToServer && serverLibraries.isNotEmpty()
  }

  /**
   * Get all available libraries with audio content
   */
  fun getLibrariesWithAudio(): List<Library> {
    return serverLibraries.filter { library ->
      (library.stats?.numAudioFiles ?: 0) > 0
    }
  }
}
