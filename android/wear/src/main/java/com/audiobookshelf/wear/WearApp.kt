package com.audiobookshelf.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.audiobookshelf.wear.data.AbsApi
import com.audiobookshelf.wear.data.AuthStore
import com.audiobookshelf.wear.data.DownloadStore
import com.audiobookshelf.wear.data.ProgressStore
import com.audiobookshelf.wear.sync.PhoneConfig
import com.audiobookshelf.wear.sync.SessionSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WearApp : Application(), ImageLoaderFactory {
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  lateinit var auth: AuthStore
  lateinit var api: AbsApi
  lateinit var downloads: DownloadStore
  lateinit var progress: ProgressStore
  lateinit var phoneConfig: PhoneConfig
  lateinit var sessionSync: SessionSync

  override fun onCreate() {
    super.onCreate()
    auth = AuthStore(this)
    api = AbsApi(auth)
    downloads = DownloadStore(this)
    progress = ProgressStore(this)
    phoneConfig = PhoneConfig(this)
    sessionSync = SessionSync(this, api, auth, progress)

    getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(DOWNLOAD_CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
    )

    appScope.launch {
      phoneConfig.refreshFromPhone()
      sessionSync.flushPending()
    }
  }

  // Covers need the signed in user's credentials
  override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).okHttpClient { api.client }.crossfade(true).build()

  companion object {
    const val DOWNLOAD_CHANNEL = "downloads"
  }
}
