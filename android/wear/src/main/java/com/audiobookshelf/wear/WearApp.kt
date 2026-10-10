package com.audiobookshelf.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.audiobookshelf.wear.data.AbsApi
import com.audiobookshelf.wear.data.AuthStore
import com.audiobookshelf.wear.data.DownloadStore
import com.audiobookshelf.wear.data.ProgressStore
import com.audiobookshelf.wear.sync.ContentSettings
import com.audiobookshelf.wear.sync.PhoneConfig
import com.audiobookshelf.wear.sync.PhoneLink
import com.audiobookshelf.wear.sync.ServerRoute
import com.audiobookshelf.wear.sync.SessionSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WearApp : Application(), ImageLoaderFactory {
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  lateinit var auth: AuthStore
  lateinit var phoneLink: PhoneLink
  lateinit var serverRoute: ServerRoute
  lateinit var api: AbsApi
  lateinit var downloads: DownloadStore
  lateinit var progress: ProgressStore
  lateinit var phoneConfig: PhoneConfig
  lateinit var contentSettings: ContentSettings
  lateinit var sessionSync: SessionSync

  override fun onCreate() {
    super.onCreate()
    auth = AuthStore(this)
    phoneLink = PhoneLink(this)
    serverRoute = ServerRoute(this, auth, phoneLink, appScope)
    api = AbsApi(auth, phoneLink, serverRoute)
    downloads = DownloadStore(this)
    progress = ProgressStore(this)
    phoneConfig = PhoneConfig(this)
    contentSettings = ContentSettings(this)
    sessionSync = SessionSync(this, api, auth, progress)

    getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(DOWNLOAD_CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
    )

    serverRoute.start()
    appScope.launch {
      syncSignInFromPhone()
      phoneConfig.refreshFromPhone()
      sessionSync.flushPending()
    }
  }

  /**
   * Signs in with the phone's account, or picks up a different server or user chosen on the phone.
   * A watch-only sign in and a sign out on the watch are left alone.
   */
  suspend fun syncSignInFromPhone() {
    val current = auth.current
    if (current?.linkedToPhone == false || (current == null && auth.signedOutByUser)) return
    try {
      val account = phoneLink.requestAccount()
      if (account.serverAddress != current?.serverAddress || account.userId != current.userId) auth.save(account)
    } catch (e: Exception) {
      Log.d(TAG, "No sign in from the phone: ${e.message}")
    }
  }

  // Covers need the signed in user's credentials
  override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).okHttpClient { api.client }.crossfade(true).build()

  companion object {
    private const val TAG = "WearApp"
    const val DOWNLOAD_CHANNEL = "downloads"
  }
}
