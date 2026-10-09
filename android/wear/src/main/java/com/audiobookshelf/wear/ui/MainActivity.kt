package com.audiobookshelf.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
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
        MaterialTheme(colors = absColors) {
          val account by app.auth.account.collectAsState()
          if (account == null) LoginScreen() else AppNavigation()
        }
      }
    }
  }

  override fun onDestroy() {
    player.release()
    super.onDestroy()
  }
}

private val absColors = Colors(
  primary = Color(0xFF1AD691),
  primaryVariant = Color(0xFF0E9E69),
  secondary = Color(0xFFE5C07B),
  onPrimary = Color.Black
)

object Routes {
  const val HOME = "home"
  const val DOWNLOADS = "downloads"
  const val PLAYER = "player"
  const val PLAYER_OPTIONS = "player_options"
  const val SETTINGS = "settings"
  fun library(id: String) = "library/$id"
  fun search(libraryId: String) = "search/$libraryId"
  fun item(id: String) = "item/$id"
}

@androidx.compose.runtime.Composable
private fun AppNavigation() {
  val nav = rememberSwipeDismissableNavController()
  SwipeDismissableNavHost(navController = nav, startDestination = Routes.HOME) {
    composable(Routes.HOME) { HomeScreen(nav) }
    composable("library/{id}") { LibraryScreen(nav, it.arguments?.getString("id")!!) }
    composable("search/{id}") { SearchScreen(nav, it.arguments?.getString("id")!!) }
    composable("item/{id}") { ItemScreen(nav, it.arguments?.getString("id")!!) }
    composable(Routes.DOWNLOADS) { DownloadsScreen(nav) }
    composable(Routes.PLAYER) { PlayerScreen(nav) }
    composable(Routes.PLAYER_OPTIONS) { PlayerOptionsScreen() }
    composable(Routes.SETTINGS) { SettingsScreen() }
  }
}
