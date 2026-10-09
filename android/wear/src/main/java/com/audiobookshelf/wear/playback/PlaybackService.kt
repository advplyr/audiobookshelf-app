package com.audiobookshelf.wear.playback

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.audiobookshelf.wear.WearApp
import com.audiobookshelf.wear.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Plays books from watch storage or streams them from the server, with a media session for system controls. */
class PlaybackService : MediaSessionService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var app: WearApp
  private lateinit var player: ExoPlayer
  private lateinit var mediaSession: MediaSession
  private lateinit var tracker: ListeningTracker
  private var sleepJob: Job? = null

  @OptIn(UnstableApi::class)
  override fun onCreate() {
    super.onCreate()
    app = application as WearApp

    // Streams go through the authenticated client so the server accepts them
    val dataSourceFactory = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(app.api.client))
    player = ExoPlayer.Builder(this)
      .setAudioAttributes(
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
        true
      )
      .setHandleAudioBecomingNoisy(true)
      .setWakeMode(C.WAKE_MODE_NETWORK)
      .setSeekBackIncrementMs(SEEK_BACK_MS)
      .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
      .build()

    tracker = ListeningTracker(app, player, scope)
    player.addListener(tracker)

    // Used by the media notification and the "now playing" indicator on the watch face,
    // so it opens straight into the player rather than the app's home screen
    val openPlayer = PendingIntent.getActivity(
      this,
      REQUEST_OPEN_PLAYER,
      Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    mediaSession = MediaSession.Builder(this, player).setSessionActivity(openPlayer).build()
    current = this
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

  /** Loads a book and starts playing at [startTime] seconds into the book. */
  fun load(playing: PlayingBook, startTime: Double) {
    // Save where the previous book was left before its tracks are replaced
    if (NowPlaying.book.value != null) tracker.finish()

    NowPlaying.book.value = playing
    val book = playing.book
    val coverFile = app.downloads.coverFile(book.id)
    val artwork = if (playing.isLocal && coverFile.exists()) Uri.fromFile(coverFile) else null
    val items = book.tracks.map { track ->
      val uri = if (playing.isLocal) Uri.fromFile(app.downloads.trackFile(book.id, track)) else Uri.parse(app.api.absoluteUrl(track.contentUrl))
      MediaItem.Builder()
        .setMediaId("${book.id}/${track.index}")
        .setUri(uri)
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setAlbumTitle(book.title)
            .setArtworkUri(artwork)
            .build()
        )
        .build()
    }
    val (index, positionMs) = playing.trackPosition(startTime)
    player.setMediaItems(items, index, positionMs)
    player.setPlaybackSpeed(playbackSpeed(this))
    player.prepare()
    player.play()
  }

  /** Stops playback and unloads the book, saving its position first. */
  fun unload() {
    player.pause()
    tracker.finish()
    cancelSleepTimer()
    player.stop()
    player.clearMediaItems()
    NowPlaying.book.value = null
  }

  fun startSleepTimer(durationMs: Long) {
    sleepJob?.cancel()
    val endsAt = System.currentTimeMillis() + durationMs
    sleepJob = scope.launch {
      while (true) {
        val remaining = endsAt - System.currentTimeMillis()
        if (remaining <= 0) break
        _sleepRemainingMs.value = remaining
        delay(minOf(remaining, 1000L))
      }
      _sleepRemainingMs.value = null
      player.pause()
    }
  }

  fun cancelSleepTimer() {
    sleepJob?.cancel()
    _sleepRemainingMs.value = null
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    if (!player.playWhenReady || player.mediaItemCount == 0) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    current = null
    tracker.finish()
    cancelSleepTimer()
    scope.cancel()
    mediaSession.release()
    player.release()
    NowPlaying.book.value = null
    super.onDestroy()
  }

  companion object {
    /** The listener's chosen speed, kept across books and app restarts. */
    fun playbackSpeed(context: android.content.Context): Float =
      context.getSharedPreferences("player", MODE_PRIVATE).getFloat("speed", 1f)

    fun savePlaybackSpeed(context: android.content.Context, speed: Float) {
      context.getSharedPreferences("player", MODE_PRIVATE).edit().putFloat("speed", speed).apply()
    }

    private const val REQUEST_OPEN_PLAYER = 1
    const val SEEK_BACK_MS = 10_000L
    const val SEEK_FORWARD_MS = 30_000L

    /** The running service, so the in-process UI can load books into it. Only touched on the main thread. */
    @SuppressLint("StaticFieldLeak")
    var current: PlaybackService? = null
      private set

    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs.asStateFlow()
  }
}
