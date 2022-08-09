package com.audiobookshelf.app.media

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import androidx.media.utils.MediaConstants
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.server.ApiHandler
import com.getcapacitor.JSObject
import java.util.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MediaManager(var apiHandler: ApiHandler, var ctx: Context) {
  val tag = "MediaManager"

  var serverLibraryItems = mutableListOf<LibraryItem>()
  var selectedLibraryId = ""

  var selectedLibraryItemWrapper:LibraryItemWrapper? = null
  var selectedPodcast:Podcast? = null
  var selectedLibraryItemId:String? = null
  var serverPodcastEpisodes = listOf<PodcastEpisode>()
  var serverLibraryCategories = listOf<LibraryCategory>()
  var serverLibraries = listOf<Library>()
  var serverConfigIdUsed:String? = null
  var serverConfigLastPing:Long = 0L
  var serverUserMediaProgress:MutableList<MediaProgress> = mutableListOf()

  var userSettingsPlaybackRate:Float? = null

  fun getIsLibrary(id:String) : Boolean {
    return serverLibraries.find { it.id == id } != null
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

  fun checkResetServerItems() {
    // When opening android auto need to check if still connected to server
    //   and reset any server data already set
    val serverConnConfig = if (DeviceManager.isConnectedToServer) DeviceManager.serverConnectionConfig else DeviceManager.deviceData.getLastServerConnectionConfig()

    if (!DeviceManager.isConnectedToServer || !apiHandler.isOnline() || serverConnConfig == null || serverConnConfig.id !== serverConfigIdUsed) {
      serverPodcastEpisodes = listOf()
      serverLibraryCategories = listOf()
      serverLibraries = listOf()
      serverLibraryItems = mutableListOf()
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
        if (libraryItemsWithAudio.isNotEmpty()) {
          selectedLibraryId = libraryId
        }

        serverLibraryItems = mutableListOf()
        libraryItemsWithAudio.forEach { libraryItem ->
            serverLibraryItems.add(libraryItem)
        }
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

                val mediaMetadata = podcastEpisode.getMediaMetadata(libraryItemWrapper)
                val progress = DeviceManager.dbManager.getLocalMediaProgress("${libraryItemWrapper.id}-${podcastEpisode.id}")
                val description = getMediaDescriptionFromMediaMetadata(mediaMetadata, progress)
                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
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

                val mediaMetadata = podcastEpisode.getMediaMetadata(libraryItemWrapper)
                val progress = serverUserMediaProgress.find { it.libraryItemId == libraryItemWrapper.id && it.episodeId == podcastEpisode.id }
                val description = getMediaDescriptionFromMediaMetadata(mediaMetadata, progress)
                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
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

  suspend fun checkServerConnection(config:ServerConnectionConfig) : Boolean {
    var successfulPing = false
    suspendCoroutine<Boolean> { cont ->
      apiHandler.pingServer(config) {
        Log.d(tag, "checkServerConnection: Checked server conn for ${config.address} result = $it")
        successfulPing = it
        cont.resume(it)
      }
    }
    return successfulPing
  }

  suspend fun authorize(config:ServerConnectionConfig) : MutableList<MediaProgress> {
    var mediaProgress:MutableList<MediaProgress> = mutableListOf()
    suspendCoroutine<MutableList<MediaProgress>> { cont ->
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

  fun checkSetValidServerConnectionConfig(cb: (Boolean) -> Unit) = runBlocking {
    Log.d(tag, "checkSetValidServerConnectionConfig | $serverConfigIdUsed")

    coroutineScope {
      if (!apiHandler.isOnline()) {
        serverUserMediaProgress = mutableListOf()
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

    // Check if any valid server connection if not use locally downloaded books
    checkSetValidServerConnectionConfig { isConnected ->
      if (isConnected) {
        serverConfigIdUsed = DeviceManager.serverConnectionConfigId

        loadLibraries { libraries ->
          if (libraries.isEmpty()) {
            Log.w(tag, "No libraries returned from server request")
            cb(cats) // Return download category only
          } else {
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
        }
      } else { // Not connected/no internet sent downloaded cats only
        cb(cats)
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

  fun play(libraryItemWrapper:LibraryItemWrapper, episode:PodcastEpisode?, playItemRequestPayload:PlayItemRequestPayload, cb: (PlaybackSession?) -> Unit) {
    if (libraryItemWrapper is LocalLibraryItem) {
      cb(libraryItemWrapper.getPlaybackSession(episode))
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

  fun getMediaDescriptionFromMediaMetadata(item: MediaMetadataCompat, progress:MediaProgressWrapper?): MediaDescriptionCompat {

    val extras = Bundle()
    if (progress != null) {
      Log.d(tag, "Has media progress for ${item.description.title} | ${progress}")
      if (progress.isFinished) {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        )
      } else {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        )
        extras.putDouble(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, progress.progress
        )
      }
    } else {
      Log.d(tag, "No media progress for ${item.description.title} | ${item.description.mediaId}")
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
      )
    }

    return MediaDescriptionCompat.Builder()
      .setMediaId(item.description.mediaId)
      .setTitle(item.description.title)
      .setIconUri(item.description.iconUri)
      .setSubtitle(item.description.subtitle)
      .setExtras(extras).build()
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
