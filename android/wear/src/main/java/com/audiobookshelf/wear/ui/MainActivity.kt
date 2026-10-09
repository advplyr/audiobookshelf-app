package com.audiobookshelf.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.audiobookshelf.wear.WearApp
import com.audiobookshelf.wear.playback.PlayerConnection

class MainActivity : ComponentActivity() {
  private lateinit var player: PlayerConnection

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
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
            if (account == null) LoginScreen() else AppNavigation()
          }
        }
      }
    }
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
  fun library(id: String, name: String) = "library/$id/${android.net.Uri.encode(name)}"
  fun search(libraryId: String) = "search/$libraryId"
  fun item(id: String) = "item/$id"
}

@Composable
private fun AppNavigation() {
  val nav = rememberSwipeDismissableNavController()
  SwipeDismissableNavHost(navController = nav, startDestination = Routes.HOME) {
    composable(Routes.HOME) { HomeScreen(nav) }
    composable("library/{id}/{name}") {
      LibraryScreen(nav, it.arguments?.getString("id")!!, it.arguments?.getString("name").orEmpty())
    }
    composable("search/{id}") { SearchScreen(nav, it.arguments?.getString("id")!!) }
    composable("item/{id}") { ItemScreen(nav, it.arguments?.getString("id")!!) }
    composable(Routes.DOWNLOADS) { DownloadsScreen(nav) }
    composable(Routes.PLAYER) { PlayerScreen(nav) }
    composable(Routes.PLAYER_OPTIONS) { PlayerOptionsScreen(nav) }
    composable(Routes.SPEED) { SpeedScreen(nav) }
    composable(Routes.SLEEP) { SleepTimerScreen(nav) }
    composable(Routes.CHAPTERS) { ChaptersScreen(nav) }
    composable(Routes.SETTINGS) { SettingsScreen() }
  }
}
