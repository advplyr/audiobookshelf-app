package com.audiobookshelf.wear.ui

import androidx.navigation.NavHostController
import com.audiobookshelf.wear.WearApp
import com.audiobookshelf.wear.data.BookDetail
import com.audiobookshelf.wear.playback.PlayerConnection

/** Starts (or resumes) a book and opens the player. Uses the downloaded copy when there is one. */
suspend fun startBook(app: WearApp, player: PlayerConnection, nav: NavHostController, itemId: String) {
  startBook(player, nav, app.downloads.get(itemId) ?: app.api.item(itemId))
}

suspend fun startBook(player: PlayerConnection, nav: NavHostController, book: BookDetail) {
  // Resumes without reloading when this book is already in the player
  player.play(book)
  nav.navigate(Routes.PLAYER) { launchSingleTop = true }
}
