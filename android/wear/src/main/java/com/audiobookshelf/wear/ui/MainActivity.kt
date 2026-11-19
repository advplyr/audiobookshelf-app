package com.audiobookshelf.wear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import android.util.Log
import com.audiobookshelf.wear.WearApp
import com.audiobookshelf.wear.data.BookSummary
import com.audiobookshelf.wear.data.MediaKey
import com.audiobookshelf.wear.playback.NowPlaying
import com.audiobookshelf.wear.playback.PlaybackService
import com.audiobookshelf.wear.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
  private lateinit var player: PlayerConnection

  /** Set when launched from the media notification / now playing indicator. */
  private val openPlayerRequest = MutableStateFlow(false)

  /** Set by a tile when a book or episode that isn't loaded should play. */
  private val resumeRequest = MutableStateFlow<MediaKey?>(null)

  /** Set by a tile to open one of its screens. */
  private val routeRequest = MutableStateFlow<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) handleIntent(intent)
    val app = application as WearApp
    player = PlayerConnection(this)
    player.connect()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    setContent {
      CompositionLocalProvider(LocalApp provides app, LocalPlayer provides player) {
        AbsTheme {
          // AppScaffold keeps the curved time text in place while screens swipe in and out
          AppScaffold {
            val account by app.auth.account.collectAsState()
            if (account == null) LoginScreen() else AppNavigation(openPlayerRequest, resumeRequest, routeRequest)
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent?) {
    if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
      openPlayerRequest.value = true
      // Consume it so recreating the activity doesn't jump to the player again
      intent.removeExtra(EXTRA_OPEN_PLAYER)
    }
    val arg = intent?.getStringExtra(EXTRA_TILE_ARG)
    when (intent?.getStringExtra(EXTRA_TILE_ACTION)) {
      TILE_PLAY -> arg?.let(MediaKey::parse)?.let { key ->
        if (NowPlaying.book.value?.book?.mediaKey == key) {
          PlaybackService.current?.guard?.play()
          openPlayerRequest.value = true
        } else {
          resumeRequest.value = key
        }
      }
      TILE_ROUTE -> routeRequest.value = arg
    }
    intent?.removeExtra(EXTRA_TILE_ACTION)
    intent?.removeExtra(EXTRA_TILE_ARG)
  }

  companion object {
    const val EXTRA_OPEN_PLAYER = "open_player"
    /** What a tile asks for: [TILE_PLAY] a book or episode, or open a [TILE_ROUTE]. */
    const val EXTRA_TILE_ACTION = "tile_action"
    /** The [com.audiobookshelf.wear.data.MediaKey.key] to play, or one of the ROUTE_ names */
    const val EXTRA_TILE_ARG = "tile_arg"
    const val TILE_PLAY = "play"
    const val TILE_ROUTE = "route"
    const val ROUTE_CONTINUE = "continue"
    const val ROUTE_DOWNLOADS = "downloads"
    const val ROUTE_LIBRARIES = "libraries"
  }

  override fun onDestroy() {
    player.release()
    super.onDestroy()
  }
}

object Routes {
  const val HOME = "home"
  const val DOWNLOADS = "downloads"
  const val PLAYER = "player"
  const val PLAYER_OPTIONS = "player_options"
  const val SPEED = "speed"
  const val SLEEP = "sleep"
  const val CHAPTERS = "chapters"
  const val SETTINGS = "settings"
  const val AUDIO_OUTPUT = "audio_output"
  const val CONTINUE = "continue"
  const val LIBRARIES = "libraries"
  const val SERIES_LIBRARIES = "series_libraries"
  fun series(libraryId: String, libraryName: String) = "series/$libraryId/${android.net.Uri.encode(libraryName)}"
  fun seriesDetail(libraryId: String, seriesId: String, name: String) = "seriesDetail/$libraryId/$seriesId/${android.net.Uri.encode(name)}"
  fun library(id: String, name: String) = "library/$id/${android.net.Uri.encode(name)}"
  fun search(libraryId: String) = "search/$libraryId"
  fun item(id: String, episodeId: String? = null) = if (episodeId == null) "item/$id" else "item/$id?episode=$episodeId"
  fun podcast(id: String) = "podcast/$id"

  /** Where tapping a list entry goes: a podcast's episode list, or the book or episode itself. */
  fun open(summary: BookSummary) = if (summary.isPodcast) podcast(summary.id) else item(summary.id, summary.episodeId)
}

@Composable
private fun AppNavigation(
  openPlayerRequest: MutableStateFlow<Boolean>,
  resumeRequest: MutableStateFlow<MediaKey?>,
  routeRequest: MutableStateFlow<String?>
) {
  val nav = rememberSwipeDismissableNavController()
  val app = LocalApp.current
  val player = LocalPlayer.current
  // Requests from tiles. Collected once, so clearing a request doesn't cancel the work it started.
  LaunchedEffect(Unit) {
    routeRequest.filterNotNull().collect { route ->
      routeRequest.value = null
      val target = when (route) {
        MainActivity.ROUTE_CONTINUE -> Routes.CONTINUE
        MainActivity.ROUTE_DOWNLOADS -> Routes.DOWNLOADS
        MainActivity.ROUTE_LIBRARIES -> Routes.LIBRARIES
        else -> return@collect
      }
      nav.navigate(target) { launchSingleTop = true }
    }
  }
  LaunchedEffect(Unit) {
    resumeRequest.filterNotNull().collect { key ->
      resumeRequest.value = null
      try {
        startBook(app, player, nav, key)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // e.g. offline and not downloaded: show the item so the problem is visible
        Log.w("MainActivity", "Could not play ${key.key}: ${e.message}")
        nav.navigate(Routes.item(key.itemId, key.episodeId))
      }
    }
  }
  val openPlayer by openPlayerRequest.collectAsState()
  val nowPlaying by NowPlaying.book.collectAsState()
  LaunchedEffect(openPlayer, nowPlaying) {
    if (openPlayer && nowPlaying != null) {
      openPlayerRequest.value = false
      nav.navigate(Routes.PLAYER) { launchSingleTop = true }
    } else if (openPlayer) {
      // Give the player a moment to reconnect, then drop the request so it can't fire later
      delay(3000)
      openPlayerRequest.value = false
    }
  }
  SwipeDismissableNavHost(navController = nav, startDestination = Routes.HOME) {
    composable(Routes.HOME) {
      val classicHome by LocalApp.current.contentSettings.classicHome.collectAsState()
      if (classicHome) ClassicHomeScreen(nav) else HomeHubScreen(nav)
    }
    composable(Routes.CONTINUE) { ContinueListeningScreen(nav) }
    composable(Routes.LIBRARIES) { LibraryPickerScreen(nav, forSeries = false) }
    composable(Routes.SERIES_LIBRARIES) { LibraryPickerScreen(nav, forSeries = true) }
    composable("series/{libraryId}/{name}") {
      SeriesListScreen(nav, it.arguments?.getString("libraryId")!!, it.arguments?.getString("name").orEmpty())
    }
    composable("seriesDetail/{libraryId}/{seriesId}/{name}") {
      val args = it.arguments!!
      SeriesDetailScreen(nav, args.getString("libraryId")!!, args.getString("seriesId")!!, args.getString("name").orEmpty())
    }
    composable("library/{id}/{name}") {
      LibraryScreen(nav, it.arguments?.getString("id")!!, it.arguments?.getString("name").orEmpty())
    }
    composable("search/{id}") { SearchScreen(nav, it.arguments?.getString("id")!!) }
    composable(
      "item/{id}?episode={episode}",
      arguments = listOf(navArgument("episode") { type = NavType.StringType; nullable = true; defaultValue = null })
    ) {
      ItemScreen(nav, MediaKey(it.arguments?.getString("id")!!, it.arguments?.getString("episode")))
    }
    composable("podcast/{id}") { PodcastScreen(nav, it.arguments?.getString("id")!!) }
    composable(Routes.DOWNLOADS) { DownloadsScreen(nav) }
    composable(Routes.PLAYER) { PlayerScreen(nav) }
    composable(Routes.PLAYER_OPTIONS) { PlayerOptionsScreen(nav) }
    composable(Routes.SPEED) { SpeedScreen(nav) }
    composable(Routes.SLEEP) { SleepTimerScreen(nav) }
    composable(Routes.CHAPTERS) { ChaptersScreen(nav) }
    composable(Routes.SETTINGS) { SettingsScreen() }
    composable(Routes.AUDIO_OUTPUT) { AudioOutputScreen(nav) }
  }
}
