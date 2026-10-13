package com.audiobookshelf.wear.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Client-only preferences for this watch; nothing here is sent to the server. */
class ContentSettings(context: Context) {
  private val prefs = context.getSharedPreferences("content_settings", Context.MODE_PRIVATE)

  private val _classicHome = MutableStateFlow(prefs.getBoolean(KEY_CLASSIC_HOME, false))

  /** Use the original scrolling list home instead of the hub. Watch only; not shared with the phone. */
  val classicHome: StateFlow<Boolean> = _classicHome.asStateFlow()

  fun setClassicHome(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_CLASSIC_HOME, enabled).apply()
    _classicHome.value = enabled
  }

  private val _autoDeleteFinished = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DELETE_FINISHED, false))

  /** Remove a download from the watch once the book or episode is finished. Off by default. */
  val autoDeleteFinished: StateFlow<Boolean> = _autoDeleteFinished.asStateFlow()

  fun setAutoDeleteFinished(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_AUTO_DELETE_FINISHED, enabled).apply()
    _autoDeleteFinished.value = enabled
  }

  companion object {
    private const val KEY_CLASSIC_HOME = "classicHome"
    private const val KEY_AUTO_DELETE_FINISHED = "autoDeleteFinished"
  }
}
