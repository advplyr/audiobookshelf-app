package com.audiobookshelf.app.player

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.*
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.VolumeProviderCompat
import android.media.AudioManager
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.SleepTimerManager
import com.audiobookshelf.app.managers.SleepTimerHost
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.MediaProgressSyncer
import com.audiobookshelf.app.media.getUriToAbsIconDrawable
import com.audiobookshelf.app.media.getUriToDrawable
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.CustomActionProvider
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.*
import kotlinx.coroutines.runBlocking

const val SLEEP_TIMER_WAKE_UP_EXPIRATION = 120000L // 2m
const val PLAYER_CAST = "cast-player"
const val PLAYER_EXO = "exo-player"
const val PLAYER_MEDIA3 = "media3-exoplayer"

class PlayerNotificationService : MediaBrowserServiceCompat(), PlaybackTelemetryHost, SleepTimerHost {

  private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  companion object {
    var isStarted = false
    var isClosed = false
    var isUnmeteredNetwork = false
    var hasNetworkConnectivity = false // Not 100% reliable has internet
    var isSwitchingPlayer = false // Used when switching between cast player and exoplayer
  }

  private val tag = "PlayerNotificationServ"

  interface ClientEventEmitter {
    fun onPlaybackSession(playbackSession: PlaybackSession)
    fun onPlaybackClosed()
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: PlaybackMetadata)
    fun onSleepTimerEnded(currentPosition: Long)
    fun onSleepTimerSet(sleepTimeRemaining: Int, isAutoSleepTimer: Boolean)
    fun onLocalMediaProgressUpdate(localMediaProgress: LocalMediaProgress)
    fun onPlaybackFailed(errorMessage: String)
    fun onMediaPlayerChanged(mediaPlayer: String)
    fun onProgressSyncFailing()
    fun onProgressSyncSuccess()
    fun onNetworkMeteredChanged(isUnmetered: Boolean)
    fun onMediaItemHistoryUpdated(mediaItemHistory: MediaItemHistory)
    fun onPlaybackSpeedChanged(playbackSpeed: Float)
  }
  private val binder = LocalBinder()

  var clientEventEmitter: ClientEventEmitter? = null

  private lateinit var ctx: Context
  override val appContext: Context
    get() = ctx
  override val isUnmeteredNetwork: Boolean
    get() = Companion.isUnmeteredNetwork
  override val context: Context
    get() = ctx
  private lateinit var mediaSessionConnector: MediaSessionConnector
  private lateinit var playerNotificationManager: PlayerNotificationManager
  // Media3 notification provider manager removed to avoid internal API usage
  lateinit var mediaSession: MediaSessionCompat
  private var remoteVolumeProvider: VolumeProviderCompat? = null
  private lateinit var transportControls: MediaControllerCompat.TransportControls

  lateinit var mediaManager: MediaManager
  lateinit var apiHandler: ApiHandler

  private lateinit var mPlayer: ExoPlayer
  private lateinit var currentPlayer: Player
  var castPlayer: CastPlayer? = null
  lateinit var playerWrapper: PlayerWrapper

  lateinit var sleepTimerManager: SleepTimerManager
  lateinit var mediaProgressSyncer: MediaProgressSyncer
  private var externalSleepTimerManager: SleepTimerManager? = null

  private var notificationId = 10
  private var channelId = "audiobookshelf_channel"
  private var channelName = "Audiobookshelf Channel"

  var currentPlaybackSession: PlaybackSession? = null
  private var initialPlaybackRate: Float? = null
  interface PlaybackStateBridge {
    fun currentPositionMs(): Long
    fun bufferedPositionMs(): Long
    fun durationMs(): Long
    fun isPlaying(): Boolean
    fun playbackState(): Int
    fun currentMediaItemIndex(): Int
  }

  private var externalPlaybackState: PlaybackStateBridge? = null

  fun setExternalPlaybackState(bridge: PlaybackStateBridge?) {
    externalPlaybackState = bridge
  }

  fun setExternalSleepTimerManager(manager: SleepTimerManager?) {
    externalSleepTimerManager = manager
  }

  private var isAndroidAuto = false

  // The following are used for the shake detection
  private var sleepTimerShakeController: SleepTimerShakeController? = null

  // Flags to control Android Auto reload behavior when connectivity or state changes.
  private var forceReloadingAndroidAuto: Boolean = false
  private var firstLoadDone: Boolean = false

  // Simple rollout metrics for comparing Exo vs Media3
  private var playbackStartMonotonicMs: Long = 0L
  private var firstReadyLatencyMs: Long = -1L
  private var bufferCount: Int = 0
  private var playbackErrorCount: Int = 0

  // --- Lightweight playback metrics helpers ---
  fun metricsRecordError() {
    try { playbackErrorCount += 1 } catch (_: Exception) {}
  }

  fun metricsRecordBuffer() {
    try { bufferCount += 1 } catch (_: Exception) {}
  }

  fun metricsRecordFirstReadyIfUnset() {
    try {
      if (firstReadyLatencyMs < 0 && playbackStartMonotonicMs > 0) {
        val now = SystemClock.elapsedRealtime()
        firstReadyLatencyMs = now - playbackStartMonotonicMs
        AbsLogger.info(
          "PlaybackMetrics",
          "startupReadyLatencyMs=${firstReadyLatencyMs} player=${getMediaPlayer()} item=${currentPlaybackSession?.mediaItemId}"
        )
      }
    } catch (_: Exception) {}
  }

  fun metricsLogSummary() {
    try {
      AbsLogger.info(
        "PlaybackMetrics",
        "summary player=${getMediaPlayer()} item=${currentPlaybackSession?.mediaItemId} buffers=${bufferCount} errors=${playbackErrorCount} startupReadyLatencyMs=${firstReadyLatencyMs}"
      )
    } catch (_: Exception) {}
  }

  fun isBrowseTreeInitialized(): Boolean {
    return this::browseTree.isInitialized
  }

  // Cache latest search so it wont trigger again when returning from series for example
  private var cachedSearch: String = ""
  private var cachedSearchResults: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

  /*
     Service related stuff
  */
  override fun onBind(intent: Intent): IBinder? {
    Log.d(tag, "onBind")

    // Android Auto Media Browser Service
    if (SERVICE_INTERFACE == intent.action) {
      Log.d(tag, "Is Media Browser Service")
      return super.onBind(intent)
    }
    return binder
  }

  inner class LocalBinder : Binder() {
    // Return this instance of LocalService so clients can call public methods
    fun getService(): PlayerNotificationService = this@PlayerNotificationService
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    isStarted = true
    Log.d(tag, "onStartCommand $startId")

    return START_STICKY
  }

  @Deprecated("Deprecated in Java")
  override fun onStart(intent: Intent?, startId: Int) {
    Log.d(tag, "onStart $startId")
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createNotificationChannel(channelId: String, channelName: String): String {
    val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
    chan.lightColor = Color.DKGRAY
    chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    val service = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    service.createNotificationChannel(chan)
    return channelId
  }

  private fun startForegroundWithPlaceholder() {
    // Create a placeholder notification to satisfy Android's foreground service requirement
    // The PlayerNotificationManager will replace this with the actual media notification
    val notification = NotificationCompat.Builder(this, channelId)
      .setContentTitle("Audiobookshelf")
      .setContentText("Loading...")
      .setSmallIcon(R.drawable.icon_monochrome)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } else {
      startForeground(notificationId, notification)
    }

    PlayerNotificationListener.isForegroundService = true
    Log.d(tag, "Started foreground service with placeholder notification")
  }

  // detach player
  override fun onDestroy() {
    try {
      val connectivityManager =
              getSystemService(ConnectivityManager::class.java) as ConnectivityManager
      connectivityManager.unregisterNetworkCallback(networkCallback)
    } catch (error: Exception) {
      Log.e(tag, "Error unregistering network listening callback $error")
    }

    Log.d(tag, "onDestroy")
    isStarted = false
    isClosed = true
    DeviceManager.widgetUpdater?.onPlayerChanged(this)

    playerNotificationManager.setPlayer(null)
    mPlayer.release()
    castPlayer?.release()
    mediaSession.release()
    mediaProgressSyncer.reset()
    sleepTimerShakeController?.release()
    sleepTimerShakeController = null

    super.onDestroy()
  }

  // removing service when user swipe out our app
  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    Log.d(tag, "onTaskRemoved")

    stopSelf()
  }

  override fun onCreate() {
    Log.d(tag, "onCreate")
    super.onCreate()
    ctx = this

    // Initialize Paper
    DbManager.initialize(ctx)

    // Initialize widget
    DeviceManager.initializeWidgetUpdater(ctx)

    // To listen for network change from metered to unmetered
    val networkRequest =
            NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build()
    val connectivityManager =
            getSystemService(ConnectivityManager::class.java) as ConnectivityManager
    connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

    DbManager.initialize(ctx)

    // Initialize API
    apiHandler = ApiHandler(ctx)

    // Initialize sleep timer
    sleepTimerManager = SleepTimerManager(this, serviceScope)

    // Initialize Media Progress Syncer
    mediaProgressSyncer = MediaProgressSyncer(this, apiHandler)

    // Initialize shake sensor
    initSensor()

    // Initialize media manager
    mediaManager = MediaManager(apiHandler, ctx)

    channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              createNotificationChannel(channelId, channelName)
            } else ""

    val sessionActivityPendingIntent =
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
              PendingIntent.getActivity(this, 0, sessionIntent, PendingIntent.FLAG_IMMUTABLE)
            }

    mediaSession =
            MediaSessionCompat(this, tag).apply {
              setSessionActivity(sessionActivityPendingIntent)
              // Only activate legacy session when not using Media3.
              isActive = !BuildConfig.USE_MEDIA3
            }

    val mediaController = MediaControllerCompat(ctx, mediaSession.sessionToken)

    // This is for Media Browser
    sessionToken = mediaSession.sessionToken

    if (!BuildConfig.USE_MEDIA3) {
      val builder = PlayerNotificationManager.Builder(ctx, notificationId, channelId)

      builder.setMediaDescriptionAdapter(AbMediaDescriptionAdapter(mediaController, this))
      builder.setNotificationListener(PlayerNotificationListener(this))

      playerNotificationManager = builder.build()
      playerNotificationManager.setMediaSessionToken(mediaSession.sessionToken)
      playerNotificationManager.setUsePlayPauseActions(true)
      playerNotificationManager.setUseNextAction(false)
      playerNotificationManager.setUsePreviousAction(false)
      playerNotificationManager.setUseChronometer(false)
      playerNotificationManager.setUseStopAction(false)
      playerNotificationManager.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      playerNotificationManager.setPriority(NotificationCompat.PRIORITY_MAX)
      playerNotificationManager.setUseFastForwardActionInCompactView(true)
      playerNotificationManager.setUseRewindActionInCompactView(true)
      playerNotificationManager.setSmallIcon(R.drawable.icon_monochrome)

      // Unknown action
      playerNotificationManager.setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)
    }

    transportControls = mediaController.transportControls

    mediaSessionConnector = MediaSessionConnector(mediaSession)
    val queueNavigator: TimelineQueueNavigator =
            object : TimelineQueueNavigator(mediaSession) {
              override fun getSupportedQueueNavigatorActions(player: Player): Long {
                return PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE
              }

              override fun getMediaDescription(
                      player: Player,
                      windowIndex: Int
              ): MediaDescriptionCompat {
                if (currentPlaybackSession == null) {
                  Log.e(tag, "Playback session is not set - returning blank MediaDescriptionCompat")
                  return MediaDescriptionCompat.Builder().build()
                }

                val coverUri = currentPlaybackSession!!.getCoverUri(ctx)


                var bitmap: Bitmap? = null
                // Local covers get bitmap
                // Note: In Android Auto for local cover images, setting the icon uri to a local path does not work (cover is blank)
                // so we create and set the bitmap here instead of AbMediaDescriptionAdapter
                if (currentPlaybackSession!!.localLibraryItem?.coverContentUrl != null) {
                  bitmap =
                    if (Build.VERSION.SDK_INT < 28) {
                      MediaStore.Images.Media.getBitmap(ctx.contentResolver, coverUri)
                    } else {
                      val source: ImageDecoder.Source =
                        ImageDecoder.createSource(ctx.contentResolver, coverUri)
                      ImageDecoder.decodeBitmap(source)
                    }
                }

                // Fix for local images crashing on Android 11 for specific devices
                // https://stackoverflow.com/questions/64186578/android-11-mediastyle-notification-crash/64232958#64232958
                try {
                  ctx.grantUriPermission(
                          "com.android.systemui",
                          coverUri,
                          Intent.FLAG_GRANT_READ_URI_PERMISSION
                  )
                } catch (error: Exception) {
                  Log.e(tag, "Grant uri permission error $error")
                }

                val extra = Bundle()
                extra.putString(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        currentPlaybackSession!!.displayAuthor
                )

                val mediaDescriptionBuilder =
                        MediaDescriptionCompat.Builder()
                                .setExtras(extra)
                                .setTitle(currentPlaybackSession!!.displayTitle)

                bitmap?.let { mediaDescriptionBuilder.setIconBitmap(it) }
                  ?: mediaDescriptionBuilder.setIconUri(coverUri)

                return mediaDescriptionBuilder.build()
              }
            }

    setMediaSessionConnectorPlaybackActions()
    mediaSessionConnector.setQueueNavigator(queueNavigator)
    mediaSessionConnector.setPlaybackPreparer(MediaSessionPlaybackPreparer(this))

    mediaSession.setCallback(MediaSessionCallback(this))

    initializeMPlayer()
    currentPlayer = mPlayer

    // Call startForeground immediately to prevent ANR only for legacy playback path.
    // When Media3 migration flag enabled, skip placeholder to avoid duplicate silent notification.
    if (!BuildConfig.USE_MEDIA3) {
      startForegroundWithPlaceholder()
    } else {
      Log.d(tag, "Skipping placeholder foreground notification (Media3 path)")
    }
  }

  private fun initializeMPlayer() {
    val customLoadControl: LoadControl =
            DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            1000 * 20, // 20s min buffer
                            1000 * 45, // 45s max buffer
                            1000 * 5, // 5s playback start
                            1000 * 20 // 20s playback rebuffer
                    )
                    .build()

    mPlayer =
      ExoPlayer.Builder(this)
        .setLoadControl(customLoadControl)
        .setSeekBackIncrementMs(deviceSettings.jumpBackwardsTimeMs)
        .setSeekForwardIncrementMs(deviceSettings.jumpForwardTimeMs)
        .build()
    // Only configure audio attributes and noisy handling for legacy Exo path.
    // When using Media3, the wrapper will own the active player/audio configuration.
    if (!PlayerWrapperFactory.useMedia3()) {
      mPlayer.setHandleAudioBecomingNoisy(true)
      // Note: Don't add listener directly to mPlayer - will be added to wrapper below
      val audioAttributes: AudioAttributes =
              AudioAttributes.Builder()
                      .setUsage(C.USAGE_MEDIA)
                      .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                      .build()
      mPlayer.setAudioAttributes(audioAttributes, true)
    }

  // Note: Do not attach the raw Exo player here. The PlayerWrapper will manage
  // attaching the correct underlying player instance to the notification and
  // media session so the service does not need conditional logic.

  // Create wrapper around the existing player instance. The factory will
  // return a Media3-backed wrapper when the feature flag is enabled, or an
  // Exo wrapper otherwise. The wrapper is responsible for wiring the
  // notification and media-session to the correct player instance.
  playerWrapper = PlayerWrapperFactory.wrapExistingPlayer(this, mPlayer)

    // Configure wrapper based on player type
    if (playerWrapper is Media3Wrapper) {
      // Media3 path: Set up session callback and seek increments
      val media3Wrapper = playerWrapper as Media3Wrapper
      media3Wrapper.setSeekIncrements(
        deviceSettings.jumpBackwardsTimeMs,
        deviceSettings.jumpForwardTimeMs
      )
      Log.d(tag, "Media3 seek increments configured")


    // Create the legacy v2 notification manager to cover Cast and local notifications
    // when running with Media3. The Media3 service owns its own notifications, so we
    // instantiate v2 here for cast fallback and to provide controls during migration.
    val v2Builder = PlayerNotificationManager.Builder(ctx, notificationId, channelId)
    v2Builder.setMediaDescriptionAdapter(AbMediaDescriptionAdapter(MediaControllerCompat(ctx, mediaSession.sessionToken), this))
    v2Builder.setNotificationListener(PlayerNotificationListener(this))
    playerNotificationManager = v2Builder.build()
    playerNotificationManager.setMediaSessionToken(mediaSession.sessionToken)
    playerNotificationManager.setSmallIcon(R.drawable.icon_monochrome)

    // Also set player on v2 manager so local Media3 playback still shows notification controls
    playerNotificationManager.setPlayer(mPlayer)

    media3Wrapper.attachNotificationManager(playerNotificationManager)
    media3Wrapper.attachMediaSessionConnector(mediaSessionConnector)

    Log.d(tag, "Media3 v2 notification manager created for Cast fallback and temp controls")
  } else {
    // ExoPlayer v2 path: Attach notification managers directly
    playerWrapper.attachNotificationManager(playerNotificationManager)
    playerWrapper.attachMediaSessionConnector(mediaSessionConnector)
  }

  // Add our listener through the wrapper so it works with both ExoPlayer v2 and Media3
  playerWrapper.addListener(PlayerListener(this))
  }

  /*
    User callable methods
  */
  fun preparePlayer(
          playbackSession: PlaybackSession,
          playWhenReady: Boolean,
          playbackRate: Float?
  ) {
    if (!isStarted) {
      Log.i(tag, "preparePlayer: foreground service not started - Starting service --")
      Intent(ctx, PlayerNotificationService::class.java).also { intent ->
        ContextCompat.startForegroundService(ctx, intent)
      }
    }

    // TODO: When an item isFinished the currentTime should be reset to 0
    //        will reset the time if currentTime is within 5s of duration (for android auto)
    Log.d(
            tag,
            "Prepare Player Session Current Time=${playbackSession.currentTime}, Duration=${playbackSession.duration}"
    )
    if (playbackSession.duration - playbackSession.currentTime < 5) {
      Log.d(tag, "Prepare Player Session is finished, so restart it")
      playbackSession.currentTime = 0.0
    }

    isClosed = false

  // Initialize simple metrics for this prepare/playback attempt
  playbackStartMonotonicMs = SystemClock.elapsedRealtime()
  firstReadyLatencyMs = -1L
  bufferCount = 0
  playbackErrorCount = 0

    val metadata = playbackSession.getMediaMetadataCompat(ctx)
    mediaSession.setMetadata(metadata)
    val mediaItems = playbackSession.toPlayerMediaItems(ctx)
    val exoMediaItems = if (playerWrapper is ExoPlayerWrapper) {
      (playerWrapper as ExoPlayerWrapper).toExoMediaItems(mediaItems)
    } else {
      // Fallback: build minimal Exo MediaItem instances from DTOs so existing
      // media-source / cast code continues to work. The canonical conversion
      // should live in Exo-specific classes; this fallback protects compile/runtime
      // when a non-Exo wrapper is active.
      mediaItems.map { dto ->
        val builder = MediaItem.Builder().setUri(dto.uri)
        dto.tag?.let { builder.setTag(it) }
        dto.mimeType?.let { builder.setMimeType(it) }
        builder.build()
      }
    }
    val playbackRateToUse = playbackRate ?: initialPlaybackRate ?: 1f
    initialPlaybackRate = playbackRate

    // Set actions on Android Auto like jump forward/backward
    setMediaSessionConnectorCustomActions(playbackSession)

    playbackSession.mediaPlayer = getMediaPlayer()

    if (playbackSession.mediaPlayer == PLAYER_CAST && playbackSession.isLocal) {
      Log.w(tag, "Cannot cast local media item - switching player")
      currentPlaybackSession = null
      switchToPlayer(false)
      playbackSession.mediaPlayer = getMediaPlayer()
    }

    if (playbackSession.mediaPlayer == PLAYER_CAST) {
      // If cast-player is the first player to be used let the wrapper switch
      // the active player used for notifications/sessions.
      playerWrapper.setActivePlayerForNotification(castPlayer)
    }

    currentPlaybackSession = playbackSession
    DeviceManager.setLastPlaybackSession(
            playbackSession
    ) // Save playback session to use when app is closed

    AbsLogger.info("PlayerNotificationService", "preparePlayer: Started playback session for item ${currentPlaybackSession?.mediaItemId}. MediaPlayer ${currentPlaybackSession?.mediaPlayer}")
    // Notify client
    clientEventEmitter?.onPlaybackSession(playbackSession)

    // Update widget
    DeviceManager.widgetUpdater?.onPlayerChanged(this)

    if (mediaItems.isEmpty()) {
      Log.e(tag, "Invalid playback session no media items to play")
      currentPlaybackSession = null
      return
    }

    if (mPlayer == currentPlayer) {
      // When Media3 is enabled, playerWrapper contains a different player instance
      // than mPlayer (which is ExoPlayer v2). We must use the wrapper for all
      // media operations to ensure we're working with the correct player.

      if (playerWrapper is ExoPlayerWrapper) {
        // ExoPlayerWrapper: Use the existing MediaSource-based approach for ExoPlayer v2
        val mediaSource: MediaSource

        if (playbackSession.isLocal) {
          AbsLogger.info("PlayerNotificationService", "preparePlayer: Playing local item ${currentPlaybackSession?.mediaItemId}.")
          val dataSourceFactory = DefaultDataSource.Factory(ctx)

          val extractorsFactory = DefaultExtractorsFactory()
          if (DeviceManager.deviceData.deviceSettings?.enableMp3IndexSeeking == true) {
            extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
          }

          mediaSource =
            ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
              .createMediaSource(exoMediaItems[0])
        } else if (!playbackSession.isHLS) {
          AbsLogger.info("PlayerNotificationService", "preparePlayer: Direct playing item ${currentPlaybackSession?.mediaItemId}.")
          val dataSourceFactory = DefaultHttpDataSource.Factory()

          val extractorsFactory = DefaultExtractorsFactory()
          if (DeviceManager.deviceData.deviceSettings?.enableMp3IndexSeeking == true) {
            extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
          }

          dataSourceFactory.setUserAgent(channelId)
          mediaSource =
            ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
              .createMediaSource(exoMediaItems[0])
        } else {
          AbsLogger.info("PlayerNotificationService", "preparePlayer: Playing HLS stream of item ${currentPlaybackSession?.mediaItemId}.")
          val dataSourceFactory = DefaultHttpDataSource.Factory()
          dataSourceFactory.setUserAgent(channelId)
          dataSourceFactory.setDefaultRequestProperties(
            hashMapOf("Authorization" to "Bearer ${DeviceManager.token}")
          )
          mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(exoMediaItems[0])
        }
        mPlayer.setMediaSource(mediaSource)

        // Add remaining media items if multi-track
        if (mediaItems.size > 1) {
          playerWrapper.addMediaItems(mediaItems.subList(1, mediaItems.size))
          Log.d(tag, "currentPlayer total media items ${playerWrapper.getMediaItemCount()}")

          val currentTrackIndex = playbackSession.getCurrentTrackIndex()
          val currentTrackTime = playbackSession.getCurrentTrackTimeMs()
          Log.d(
            tag,
            "currentPlayer current track index $currentTrackIndex & current track time $currentTrackTime"
          )
          playerWrapper.seekTo(currentTrackIndex, currentTrackTime)
        } else {
          playerWrapper.seekTo(playbackSession.currentTimeMs)
        }
      } else {
        // Media3Wrapper or other: Use wrapper's setMediaItems which handles
        // the media setup internally
        AbsLogger.info("PlayerNotificationService", "preparePlayer: Using wrapper.setMediaItems for ${mediaItems.size} items")

        val currentTrackIndex = if (mediaItems.size > 1) {
          playbackSession.getCurrentTrackIndex()
        } else {
          0
        }

        val startPosition = if (mediaItems.size > 1) {
          playbackSession.getCurrentTrackTimeMs()
        } else {
          playbackSession.currentTimeMs
        }

        Log.d(tag, "Media3: Setting ${mediaItems.size} media items, startIndex=$currentTrackIndex, startPos=$startPosition")
        playerWrapper.setMediaItems(mediaItems, currentTrackIndex, startPosition)
      }

        // Update MediaMetadata for Media3 notifications
        if (playerWrapper is Media3Wrapper) {
          (playerWrapper as Media3Wrapper).updateMediaMetadata(playbackSession)
        }

      Log.d(
        tag,
        "Prepare complete for session ${currentPlaybackSession?.displayTitle} | ${playerWrapper.getMediaItemCount()}"
      )
      playerWrapper.setPlayWhenReady(playWhenReady)
      // Proactively emit a playing update when playWhenReady is true so the web UI can
      // flip from spinner to pause immediately, without waiting for the next listener callback.
      // This mirrors Exo behavior closely and reduces perceived latency.
      if (playWhenReady) {
        try {
          clientEventEmitter?.onPlayingUpdate(true)
        } catch (e: Exception) {
          Log.w(tag, "Early onPlayingUpdate emit failed: ${e.message}")
        }
      }
      playerWrapper.setPlaybackSpeed(playbackRateToUse)

      playerWrapper.prepare()
  } else if (castPlayer != null) {
      val currentTrackIndex = playbackSession.getCurrentTrackIndex()
      val currentTrackTime = playbackSession.getCurrentTrackTimeMs()
      val mediaType = playbackSession.mediaType
      Log.d(tag, "Loading cast player $currentTrackIndex $currentTrackTime $mediaType")

      castPlayer?.load(
        mediaItems,
        currentTrackIndex,
        currentTrackTime,
        playWhenReady,
        playbackRateToUse,
        mediaType
      )
    }
  }

  private fun setMediaSessionConnectorCustomActions(playbackSession: PlaybackSession) {
  val mediaItems = playbackSession.toPlayerMediaItems(ctx)
    val customActionProviders =
            mutableListOf(
                    JumpBackwardCustomActionProvider(),
                    JumpForwardCustomActionProvider(),
                    ChangePlaybackSpeedCustomActionProvider() // Will be pushed to far left
            )
    if (playbackSession.mediaPlayer != PLAYER_CAST && mediaItems.size > 1) {
      customActionProviders.addAll(
              listOf(
                      SkipBackwardCustomActionProvider(),
                      SkipForwardCustomActionProvider(),
              )
      )
    }
    mediaSessionConnector.setCustomActionProviders(*customActionProviders.toTypedArray())
  }

  fun setMediaSessionConnectorPlaybackActions() {
    var playbackActions =
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_STOP

    if (deviceSettings.allowSeekingOnMediaControls) {
      playbackActions = playbackActions or PlaybackStateCompat.ACTION_SEEK_TO
    }
    mediaSessionConnector.setEnabledPlaybackActions(playbackActions)
  }

  fun handlePlayerPlaybackError(errorMessage: String) {
    // On error and was attempting to direct play - fallback to transcode
    currentPlaybackSession?.let { playbackSession ->
      if (playbackSession.isDirectPlay) {
        val playItemRequestPayload = getPlayItemRequestPayload(true)
        Log.d(tag, "Fallback to transcode $playItemRequestPayload.mediaPlayer")

        val libraryItemId = playbackSession.libraryItemId ?: "" // Must be true since direct play
        val episodeId = playbackSession.episodeId
        mediaProgressSyncer.stop(false) {
          apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
            if (it == null) { // Play request failed
              clientEventEmitter?.onPlaybackFailed(errorMessage)
              closePlayback(true)
            } else {
              Handler(Looper.getMainLooper()).post { preparePlayer(it, true, null) }
            }
          }
        }
      } else {
        clientEventEmitter?.onPlaybackFailed(errorMessage)
        closePlayback(true)
      }
    }
  }

  fun handlePlaybackEnded() {
    Log.d(tag, "handlePlaybackEnded")
    if (isAndroidAuto && currentPlaybackSession?.isPodcastEpisode == true) {
      Log.d(tag, "Podcast playback ended on android auto")
      val libraryItem = currentPlaybackSession?.libraryItem ?: return

      // Need to sync with server to set as finished
      mediaProgressSyncer.finished {
        // Need to reload media progress
        mediaManager.loadServerUserMediaProgress {
          val podcast = libraryItem.media as Podcast
          val nextEpisode = podcast.getNextUnfinishedEpisode(libraryItem.id, mediaManager)
          Log.d(tag, "handlePlaybackEnded nextEpisode=$nextEpisode")
          nextEpisode?.let { podcastEpisode ->
            mediaManager.play(libraryItem, podcastEpisode, getPlayItemRequestPayload(false)) {
              if (it == null) {
                Log.e(tag, "Failed to play library item")
              } else {
                val playbackRate = mediaManager.getSavedPlaybackRate()
                Handler(Looper.getMainLooper()).post { preparePlayer(it, true, playbackRate) }
              }
            }
          }
        }
      }
    }
  }

  fun startNewPlaybackSession() {
    currentPlaybackSession?.let { playbackSession ->
      Log.i(tag, "Starting new playback session for ${playbackSession.displayTitle}")

      val forceTranscode = playbackSession.isHLS // If already HLS then force
      val playItemRequestPayload = getPlayItemRequestPayload(forceTranscode)

      val libraryItemId = playbackSession.libraryItemId ?: "" // Must be true since direct play
      val episodeId = playbackSession.episodeId
      mediaProgressSyncer.stop(false) {
        apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
          if (it == null) {
            Log.e(tag, "Failed to start new playback session")
          } else {
            Log.d(
                    tag,
                    "New playback session response from server with session id ${it.id} for \"${it.displayTitle}\""
            )
            Handler(Looper.getMainLooper()).post { preparePlayer(it, true, null) }
          }
        }
      }
    }
  }

  fun switchToPlayer(useCastPlayer: Boolean) {
    val wasPlaying = isPlayerActive()
    if (useCastPlayer) {
      if (currentPlayer == castPlayer) {
        Log.d(tag, "switchToPlayer: Already using Cast Player " + castPlayer?.deviceInfo)
        return
      } else {
        Log.d(tag, "switchToPlayer: Switching to cast player from exo player stop exo player")
        playerWrapper.stop()
      }
    } else {
      if (currentPlayer == mPlayer) {
        Log.d(tag, "switchToPlayer: Already using Exo Player " + mPlayer.deviceInfo)
        return
      } else if (castPlayer != null) {
        Log.d(tag, "switchToPlayer: Switching to exo player from cast player stop cast player")
        castPlayer?.stop()
      }
    }

    if (currentPlaybackSession == null) {
      Log.e(tag, "switchToPlayer: No Current playback session")
    } else {
      isSwitchingPlayer = true
    }

    // Playback session in progress syncer is a copy that is up-to-date so replace current here with
    // that
    //  TODO: bad design here implemented to prevent the session in MediaProgressSyncer from
    // changing while syncing
    if (mediaProgressSyncer.currentPlaybackSession != null) {
      currentPlaybackSession = mediaProgressSyncer.currentPlaybackSession?.clone()
    }

    currentPlayer =
            if (useCastPlayer) {
              Log.d(tag, "switchToPlayer: Using Cast Player " + castPlayer?.deviceInfo)
              playerWrapper.setActivePlayerForNotification(castPlayer)
              setMediaSessionToCastVolume()
              castPlayer as CastPlayer
            } else {
              Log.d(tag, "switchToPlayer: Using ExoPlayer")
              playerWrapper.setActivePlayerForNotification(null)
              setMediaSessionToLocalVolume()

              // For Media3Wrapper, manually restore v2 player to notification manager
              if (playerWrapper is Media3Wrapper) {
                playerNotificationManager.setPlayer(mPlayer)
                mediaSessionConnector.setPlayer(mPlayer)
                Log.d(tag, "Restored mPlayer to v2 notification manager after Cast exit")
              }

              mPlayer
            }

    clientEventEmitter?.onMediaPlayerChanged(getMediaPlayer())

    currentPlaybackSession?.let {
      Log.d(tag, "switchToPlayer: Starting new playback session ${it.displayTitle}")
      if (wasPlaying) { // media is paused when switching players
        clientEventEmitter?.onPlayingUpdate(false)
      }

      // TODO: Start a new playback session here instead of using the existing
      preparePlayer(it, false, null)
    }
  }

  private fun setMediaSessionToCastVolume() {
    val currentVol = try { castPlayer?.getDeviceVolume() ?: 0 } catch (e: Exception) { 0 }
    val provider = object : VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE, 100, currentVol) {
      override fun onSetVolumeTo(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        try {
          castPlayer?.setDeviceVolume(clamped)
          val actual = castPlayer?.getDeviceVolume() ?: clamped
          setCurrentVolume(actual)
        } catch (_: Exception) {}
      }
      override fun onAdjustVolume(direction: Int) {
        val current = try { castPlayer?.getDeviceVolume() ?: currentVolume } catch (_: Exception) { currentVolume }
        val step = if (direction > 0) 1 else if (direction < 0) -1 else 0
        if (step == 0) return
        var target = (current + step).coerceIn(0, 100)
        try {
          castPlayer?.setDeviceVolume(target)
          var actual = castPlayer?.getDeviceVolume() ?: current
          // If device ignored tiny step, try nudging up to 3 steps total.
          if (actual == current) {
            target = (current + step * 3).coerceIn(0, 100)
            castPlayer?.setDeviceVolume(target)
            actual = castPlayer?.getDeviceVolume() ?: current
          }
          setCurrentVolume(actual)
        } catch (_: Exception) {}
      }
    }
    remoteVolumeProvider = provider
    mediaSession.setPlaybackToRemote(provider)
  }

  private fun setMediaSessionToLocalVolume() {
    mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
    remoteVolumeProvider = null
  }

  private fun hasMultipleTracks(): Boolean {
    return (currentPlaybackSession?.audioTracks?.size ?: 0) > 1
  }

  private fun currentMediaItemIndexInternal(): Int {
    externalPlaybackState?.let { return it.currentMediaItemIndex() }
    return try {
      playerWrapper.getCurrentMediaItemIndex()
    } catch (_: Exception) {
      0
    }
  }

  private fun trackOffsetForIndex(index: Int): Long {
    val session = currentPlaybackSession ?: return 0L
    val safeIndex = if (session.audioTracks.isNotEmpty()) {
      index.coerceIn(0, session.audioTracks.size - 1)
    } else {
      0
    }
    return session.getTrackStartOffsetMs(safeIndex)
  }

  fun getCurrentTrackStartOffsetMs(): Long {
    return if (hasMultipleTracks()) {
      trackOffsetForIndex(currentMediaItemIndexInternal())
    } else {
      0L
    }
  }

  fun getCurrentTime(): Long {
    externalPlaybackState?.let { state ->
      val offset = if (hasMultipleTracks()) trackOffsetForIndex(state.currentMediaItemIndex()) else 0L
      return state.currentPositionMs() + offset
    }
    return playerWrapper.getCurrentPosition() + getCurrentTrackStartOffsetMs()
  }

  override fun getCurrentTimeSeconds(): Double {
    return getCurrentTime() / 1000.0
  }

  private fun getBufferedTime(): Long {
    externalPlaybackState?.let { state ->
      val offset = if (hasMultipleTracks()) trackOffsetForIndex(state.currentMediaItemIndex()) else 0L
      return state.bufferedPositionMs() + offset
    }
    return if (hasMultipleTracks()) {
      val currentIndex = currentMediaItemIndexInternal()
      val currentTrackStartOffset = currentPlaybackSession?.getTrackStartOffsetMs(currentIndex) ?: 0L
      playerWrapper.getBufferedPosition() + currentTrackStartOffset
    } else {
      playerWrapper.getBufferedPosition()
    }
  }

  fun getBufferedTimeSeconds(): Double {
    return getBufferedTime() / 1000.0
  }

  override fun isPlayerActive(): Boolean {
    return externalPlaybackState?.isPlaying() ?: playerWrapper.isPlaying()
  }

  fun currentPlaybackState(): Int {
    return externalPlaybackState?.playbackState() ?: playerWrapper.getPlaybackState()
  }

  fun getDuration(): Long {
    return currentPlaybackSession?.totalDurationMs ?: 0L
  }

  fun getCurrentPlaybackSessionCopy(): PlaybackSession? {
    return currentPlaybackSession?.clone()
  }

  fun getCurrentBookChapter(): BookChapter? {
    return currentPlaybackSession?.getChapterForTime(this.getCurrentTime())
  }

  fun getEndTimeOfChapterOrTrack(): Long? {
    return getCurrentBookChapter()?.endMs ?: currentPlaybackSession?.getCurrentTrackEndTime()
  }

  private fun getNextBookChapter(): BookChapter? {
    return currentPlaybackSession?.getNextChapterForTime(this.getCurrentTime())
  }

  fun getEndTimeOfNextChapterOrTrack(): Long? {
    return getNextBookChapter()?.endMs ?: currentPlaybackSession?.getNextTrackEndTime()
  }

  // Called from PlayerListener play event
  // check with server if progress has updated since last play and sync progress update
  fun checkCurrentSessionProgress(seekBackTime: Long): Boolean {
    if (currentPlaybackSession == null) return true

    mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
      if (!DeviceManager.checkConnectivity(ctx)) {
        return true // carry on
      }

      if (playbackSession.isLocal) {

        // Make sure this connection config exists
        val serverConnectionConfig =
                DeviceManager.getServerConnectionConfig(playbackSession.serverConnectionConfigId)
        if (serverConnectionConfig == null) {
          Log.d(
                  tag,
                  "checkCurrentSessionProgress: Local library item server connection config is not saved ${playbackSession.serverConnectionConfigId}"
          )
          return true // carry on
        }

        // Local playback session check if server has updated media progress
        Log.d(
                tag,
                "checkCurrentSessionProgress: Checking if local media progress was updated on server"
        )
        apiHandler.getMediaProgress(
                playbackSession.libraryItemId!!,
                playbackSession.episodeId,
                serverConnectionConfig
        ) { mediaProgress ->
          if (mediaProgress != null &&
                          mediaProgress.lastUpdate > playbackSession.updatedAt &&
                          mediaProgress.currentTime != playbackSession.currentTime
          ) {
            Log.d(
                    tag,
                    "checkCurrentSessionProgress: Media progress was updated since last play time updating from ${playbackSession.currentTime} to ${mediaProgress.currentTime}"
            )
            mediaProgressSyncer.syncFromServerProgress(mediaProgress)

            // Update current playback session stored in PNS since MediaProgressSyncer version is a
            // copy
            mediaProgressSyncer.currentPlaybackSession?.let { updatedPlaybackSession ->
              currentPlaybackSession = updatedPlaybackSession
            }

            Handler(Looper.getMainLooper()).post {
              seekPlayer(playbackSession.currentTimeMs)
              // Should already be playing
              playerWrapper.setVolume(1F) // Volume on sleep timer might have decreased this
              currentPlaybackSession?.let { mediaProgressSyncer.play(it) }
              clientEventEmitter?.onPlayingUpdate(true)
            }
          } else {
            Handler(Looper.getMainLooper()).post {
              if (seekBackTime > 0L) {
                seekBackward(seekBackTime)
              }

              // Should already be playing
              playerWrapper.setVolume(1F) // Volume on sleep timer might have decreased this
              mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
                mediaProgressSyncer.play(playbackSession)
              }
              clientEventEmitter?.onPlayingUpdate(true)
            }
          }
        }
      } else {
        // Streaming from server so check if playback session still exists on server
        Log.d(
                tag,
                "checkCurrentSessionProgress: Checking if playback session ${playbackSession.id} for server stream is still available"
        )
        apiHandler.getPlaybackSession(playbackSession.id) {
          if (it == null) {
            Log.d(
                    tag,
                    "checkCurrentSessionProgress: Playback session does not exist on server - start new playback session"
            )

            Handler(Looper.getMainLooper()).post {
              playerWrapper.pause()
              startNewPlaybackSession()
            }
          } else {
            Log.d(tag, "checkCurrentSessionProgress: Playback session still available on server")
            Handler(Looper.getMainLooper()).post {
              if (seekBackTime > 0L) {
                seekBackward(seekBackTime)
              }

              playerWrapper.setVolume(1F) // Volume on sleep timer might have decreased this
              mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
                mediaProgressSyncer.play(playbackSession)
              }

              clientEventEmitter?.onPlayingUpdate(true)
            }
          }
        }
      }
    }
    return false
  }

  override fun play() {
    if (isPlayerActive()) {
      Log.d(tag, "Already playing")
      return
    }
  // Use wrapper to play. Under the feature-flag the wrapper will delegate to ExoPlayer or to
  // a Media3 implementation.
  playerWrapper.setVolume(1F)
  playerWrapper.play()
  }

  override fun pause() {
    playerWrapper.pause()
  }

  fun playPause(): Boolean {
    return if (isPlayerActive()) {
      pause()
      false
    } else {
      play()
      true
    }
  }

  fun seekPlayer(time: Long) {
    var timeToSeek = time
  Log.d(tag, "seekPlayer mediaCount = ${playerWrapper.getMediaItemCount()} | $timeToSeek")
    if (timeToSeek < 0) {
      Log.w(tag, "seekPlayer invalid time $timeToSeek - setting to 0")
      timeToSeek = 0L
    } else if (timeToSeek > getDuration()) {
      Log.w(tag, "seekPlayer invalid time $timeToSeek - setting to MAX - 2000")
      timeToSeek = getDuration() - 2000L
    }

    if (playerWrapper.getMediaItemCount() > 1) {
      currentPlaybackSession?.currentTime = timeToSeek / 1000.0
      val newWindowIndex = currentPlaybackSession?.getCurrentTrackIndex() ?: 0
      val newTimeOffset = currentPlaybackSession?.getCurrentTrackTimeMs() ?: 0
      Log.d(tag, "seekPlayer seekTo $newWindowIndex | $newTimeOffset")
      playerWrapper.seekTo(newWindowIndex, newTimeOffset)
    } else {
      playerWrapper.seekTo(timeToSeek)
    }
  }

  fun skipToPrevious() {
    playerWrapper.seekToPrevious()
  }

  fun skipToNext() {
    playerWrapper.seekToNext()
  }

  fun jumpForward() {
    seekForward(deviceSettings.jumpForwardTimeMs)
  }

  fun jumpBackward() {
    seekBackward(deviceSettings.jumpBackwardsTimeMs)
  }

  fun seekForward(amount: Long) {
    seekPlayer(getCurrentTime() + amount)
  }

  override fun seekBackward(amountMs: Long) {
    seekPlayer(getCurrentTime() - amountMs)
  }

  fun setPlaybackSpeed(speed: Float) {
    mediaManager.userSettingsPlaybackRate = speed
    playerWrapper.setPlaybackSpeed(speed)

    // Refresh Android Auto actions
    mediaProgressSyncer.currentPlaybackSession?.let { setMediaSessionConnectorCustomActions(it) }
  }

  fun closePlayback(calledOnError: Boolean? = false) {
    Log.d(tag, "closePlayback")
    val config = DeviceManager.serverConnectionConfig

    val isLocal = mediaProgressSyncer.currentIsLocal
    val currentSessionId = mediaProgressSyncer.currentSessionId
    if (mediaProgressSyncer.listeningTimerRunning) {
      Log.i(tag, "About to close playback so stopping media progress syncer first")

      mediaProgressSyncer.stop(
              calledOnError == false
      ) { // If closing on error then do not sync progress (causes exception)
        Log.d(tag, "Media Progress syncer stopped")
        // If not local session then close on server
        if (!isLocal && currentSessionId != "") {
          apiHandler.closePlaybackSession(currentSessionId, config) {
            Log.d(tag, "Closed playback session $currentSessionId")
          }
        }
      }
    } else {
      // If not local session then close on server
      if (!isLocal && currentSessionId != "") {
        apiHandler.closePlaybackSession(currentSessionId, config) {
          Log.d(tag, "Closed playback session $currentSessionId")
        }
      }
    }

    // Metrics: emit a summary when closing playback (if not already logged)
    try {
      AbsLogger.info(
        "PlaybackMetrics",
        "summary player=${getMediaPlayer()} item=${currentPlaybackSession?.mediaItemId} buffers=${bufferCount} errors=${playbackErrorCount} startupReadyLatencyMs=${firstReadyLatencyMs}"
      )
    } catch (_: Exception) {}

    try {
      playerWrapper.stop()
      playerWrapper.clearMediaItems()
    } catch (e: Exception) {
      Log.e(tag, "Exception clearing player $e")
    }

    currentPlaybackSession = null
    mediaProgressSyncer.reset()
    clientEventEmitter?.onPlaybackClosed()

    PlayerListener.lastPauseTime = 0
    isClosed = true
    DeviceManager.widgetUpdater?.onPlayerClosed()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  fun sendClientMetadata(playerState: PlayerState) {
    val duration = currentPlaybackSession?.getTotalDuration() ?: 0.0
    clientEventEmitter?.onMetadata(PlaybackMetadata(duration, getCurrentTimeSeconds(), playerState))
  }

  fun getMediaPlayer(): String {
    return if (currentPlayer == castPlayer) {
      PLAYER_CAST
    } else {
      if (BuildConfig.USE_MEDIA3) PLAYER_MEDIA3 else PLAYER_EXO
    }
  }

  @SuppressLint("HardwareIds")
  fun getDeviceInfo(): DeviceInfo {
    /* EXAMPLE
     manufacturer: Google
     model: Pixel 6
     brand: google
     sdkVersion: 32
     appVersion: 0.9.46-beta
    */
    val deviceId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
    return DeviceInfo(
            deviceId,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.SDK_INT,
            BuildConfig.VERSION_NAME
    )
  }

  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  fun getPlayItemRequestPayload(forceTranscode: Boolean): PlayItemRequestPayload {
    return PlayItemRequestPayload(
            getMediaPlayer(),
            !forceTranscode,
            forceTranscode,
            getDeviceInfo()
    )
  }


  override fun alertSyncFailing() {
    clientEventEmitter?.onProgressSyncFailing()
  }

  override fun alertSyncSuccess() {
    clientEventEmitter?.onProgressSyncSuccess()
  }

  override fun notifyLocalProgressUpdate(localMediaProgress: LocalMediaProgress) {
    clientEventEmitter?.onLocalMediaProgressUpdate(localMediaProgress)
  }

  override fun checkAutoSleepTimer() {
    if (this::sleepTimerManager.isInitialized) {
      sleepTimerManager.checkAutoSleepTimer()
    }
  }

  override fun currentTimeMs(): Long = getCurrentTime()

  override fun durationMs(): Long = getDuration()

  override fun isPlaying(): Boolean = isPlayerActive()

  override fun playbackSpeed(): Float = try {
    playerWrapper.getPlaybackSpeed()
  } catch (_: Exception) {
    1f
  }

  override fun setVolume(volume: Float) {
    try {
      playerWrapper.setVolume(volume)
    } catch (_: Exception) {}
  }

  override fun endTimeOfChapterOrTrack(): Long? = getEndTimeOfChapterOrTrack()

  override fun endTimeOfNextChapterOrTrack(): Long? = getEndTimeOfNextChapterOrTrack()

  override fun notifySleepTimerSet(secondsRemaining: Int, isAuto: Boolean) {
    clientEventEmitter?.onSleepTimerSet(secondsRemaining, isAuto)
  }

  override fun notifySleepTimerEnded(currentPosition: Long) {
    clientEventEmitter?.onSleepTimerEnded(currentPosition)
  }

  //
  // MEDIA BROWSER STUFF (ANDROID AUTO)
  //
  private val VALID_MEDIA_BROWSERS =
          mutableListOf(
                  "com.audiobookshelf.app",
                  "com.audiobookshelf.app.debug",
                  ANDROID_AUTO_PKG_NAME,
                  ANDROID_AUTO_SIMULATOR_PKG_NAME,
                  ANDROID_WEARABLE_PKG_NAME,
                  ANDROID_GSEARCH_PKG_NAME,
                  ANDROID_AUTOMOTIVE_PKG_NAME
          )

  private val AUTO_MEDIA_ROOT = "/"
  private val LIBRARIES_ROOT = "__LIBRARIES__"
  private val RECENTLY_ROOT = "__RECENTLY__"
  private val DOWNLOADS_ROOT = "__DOWNLOADS__"
  private val CONTINUE_ROOT = "__CONTINUE__"
  private lateinit var browseTree: BrowseTree
  private val browseTreeInitListeners = mutableListOf<() -> Unit>()

  private fun waitForBrowseTree(cb: () -> Unit)
  {
    if (this::browseTree.isInitialized)
    {
      cb()
    }
    else
    {
      browseTreeInitListeners += cb
    }
  }

  private fun onBrowseTreeInitialized()
  {
    // Called after browseTree is assigned for the first time
    browseTreeInitListeners.forEach { it.invoke() }
    browseTreeInitListeners.clear()
  }

  // Only allowing android auto or similar to access media browser service
  //  normal loading of audiobooks is handled in webview (not natively)
  private fun isValid(packageName: String, uid: Int): Boolean {
    Log.d(tag, "onGetRoot: Checking package $packageName with uid $uid")
    if (!VALID_MEDIA_BROWSERS.contains(packageName)) {
      Log.d(tag, "onGetRoot: package $packageName not valid for the media browser service")
      return false
    }
    return true
  }

  override fun onGetRoot(
          clientPackageName: String,
          clientUid: Int,
          rootHints: Bundle?
  ): BrowserRoot? {
    // Verify that the specified package is allowed to access your content
    return if (!isValid(clientPackageName, clientUid)) {
      // No further calls will be made to other media browsing methods.
      null
    } else {
      AbsLogger.info(tag, "onGetRoot: clientPackageName: $clientPackageName, clientUid: $clientUid")
      isStarted = true

      // Reset cache if no longer connected to server or server changed
      if (mediaManager.checkResetServerItems()) {
        AbsLogger.info(tag, "onGetRoot: Reset Android Auto server items cache (${DeviceManager.serverConnectionConfigString})")
        forceReloadingAndroidAuto = true
      }

      isAndroidAuto = true

      val extras = Bundle()
      extras.putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
      extras.putInt(
              MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
              MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
      )
      extras.putInt(
              MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
              MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
      )

      BrowserRoot(AUTO_MEDIA_ROOT, extras)
    }
  }

  override fun onLoadChildren(
          parentMediaId: String,
          result: Result<MutableList<MediaBrowserCompat.MediaItem>>
  ) {
    AbsLogger.info(tag, "onLoadChildren: parentMediaId: $parentMediaId (${DeviceManager.serverConnectionConfigString})")

    result.detach()

    // Prevent crashing if app is restarted while browsing
    if ((parentMediaId != DOWNLOADS_ROOT && parentMediaId != AUTO_MEDIA_ROOT) && !firstLoadDone) {
      result.sendResult(null)
      return
    }

    if (parentMediaId == DOWNLOADS_ROOT) { // Load downloads
      val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
      val localPodcasts = DeviceManager.dbManager.getLocalLibraryItems("podcast")
      val localBrowseItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

      localBooks.forEach { localLibraryItem ->
        if (localLibraryItem.media.getAudioTracks().isNotEmpty()) {
          val progress = DeviceManager.dbManager.getLocalMediaProgress(localLibraryItem.id)
          val description = localLibraryItem.getMediaDescription(progress, ctx)

          localBrowseItems +=
                  MediaBrowserCompat.MediaItem(
                          description,
                          MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                  )
        }
      }

      localPodcasts.forEach { localLibraryItem ->
        val mediaDescription = localLibraryItem.getMediaDescription(null, ctx)
        localBrowseItems +=
                MediaBrowserCompat.MediaItem(
                        mediaDescription,
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                )
      }

      result.sendResult(localBrowseItems)
    } else if (parentMediaId == CONTINUE_ROOT) {
      val localBrowseItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      mediaManager.serverItemsInProgress.forEach { itemInProgress ->
        val progress: MediaProgressWrapper?
        val mediaDescription: MediaDescriptionCompat
        if (itemInProgress.episode != null) {
          if (itemInProgress.isLocal) {
            progress =
                    DeviceManager.dbManager.getLocalMediaProgress(
                            "${itemInProgress.libraryItemWrapper.id}-${itemInProgress.episode.id}"
                    )
          } else {
            progress =
                    mediaManager.serverUserMediaProgress.find {
                      it.libraryItemId == itemInProgress.libraryItemWrapper.id &&
                              it.episodeId == itemInProgress.episode.id
                    }

            // to show download icon
            val localLibraryItem =
                    DeviceManager.dbManager.getLocalLibraryItemByLId(
                            itemInProgress.libraryItemWrapper.id
                    )
            localLibraryItem?.let { lli ->
              val localEpisode =
                      (lli.media as Podcast).episodes?.find {
                        it.serverEpisodeId == itemInProgress.episode.id
                      }
              itemInProgress.episode.localEpisodeId = localEpisode?.id
            }
          }
          mediaDescription =
                  itemInProgress.episode.getMediaDescription(
                          itemInProgress.libraryItemWrapper,
                          progress,
                          ctx
                  )
        } else {
          if (itemInProgress.isLocal) {
            progress =
                    DeviceManager.dbManager.getLocalMediaProgress(
                            itemInProgress.libraryItemWrapper.id
                    )
          } else {
            progress =
                    mediaManager.serverUserMediaProgress.find {
                      it.libraryItemId == itemInProgress.libraryItemWrapper.id
                    }

            val localLibraryItem =
                    DeviceManager.dbManager.getLocalLibraryItemByLId(
                            itemInProgress.libraryItemWrapper.id
                    )
            (itemInProgress.libraryItemWrapper as LibraryItem).localLibraryItemId =
                    localLibraryItem?.id // To show downloaded icon
          }
          mediaDescription = itemInProgress.libraryItemWrapper.getMediaDescription(progress, ctx)
        }
        localBrowseItems +=
                MediaBrowserCompat.MediaItem(
                        mediaDescription,
                        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
      }
      result.sendResult(localBrowseItems)
    } else if (parentMediaId == AUTO_MEDIA_ROOT) {
      Log.d(tag, "Trying to initialize browseTree.")
      if (!this::browseTree.isInitialized || forceReloadingAndroidAuto) {
        forceReloadingAndroidAuto = false
        AbsLogger.info(tag, "onLoadChildren: Loading Android Auto items")
        mediaManager.loadAndroidAutoItems {
          AbsLogger.info(tag, "onLoadChildren: Loaded Android Auto data, initializing browseTree")

          browseTree =
                  BrowseTree(
                          this,
                          mediaManager.serverItemsInProgress,
                          mediaManager.serverLibraries,
                          mediaManager.allLibraryPersonalizationsDone
                  )
          onBrowseTreeInitialized()
          val children =
                  browseTree[parentMediaId]?.map { item ->
                    Log.d(tag, "Found top menu item: ${item.description.title}")
                    MediaBrowserCompat.MediaItem(
                            item.description,
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                  }

          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          firstLoadDone = true
          if (mediaManager.serverLibraries.isNotEmpty()) {
            AbsLogger.info(tag, "onLoadChildren: Android Auto fetching personalized data for all libraries")
            mediaManager.populatePersonalizedDataForAllLibraries {
              AbsLogger.info(tag, "onLoadChildren: Android Auto loaded personalized data for all libraries")
              notifyChildrenChanged("/")
            }

            AbsLogger.info(tag, "onLoadChildren: Android Auto fetching in progress items")
            mediaManager.initializeInProgressItems {
              AbsLogger.info(tag, "onLoadChildren: Android Auto loaded in progress items")
              notifyChildrenChanged("/")
            }
          }
        }
      } else {
        Log.d(tag, "Starting browseTree refresh")
        browseTree =
                BrowseTree(
                        this,
                        mediaManager.serverItemsInProgress,
                        mediaManager.serverLibraries,
                        mediaManager.allLibraryPersonalizationsDone
                )
        onBrowseTreeInitialized()
        val children =
                browseTree[parentMediaId]?.map { item ->
                  Log.d(tag, "Found top menu item: ${item.description.title}")
                  MediaBrowserCompat.MediaItem(
                          item.description,
                          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                  )
                }

        AbsLogger.info(tag, "onLoadChildren: Android auto data loaded")
        result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
      }
    } else if (parentMediaId == LIBRARIES_ROOT || parentMediaId == RECENTLY_ROOT)
    {
      Log.d(tag, "First load done: $firstLoadDone")
      if (!firstLoadDone)
      {
        result.sendResult(null)
        return
      }

      if (!this::browseTree.isInitialized)
      {
        // ✅ good: detach and wait for init
        result.detach()
        waitForBrowseTree {
          val children = browseTree[parentMediaId]?.map { item ->
            Log.d(tag, "[MENU: $parentMediaId] Showing list item ${item.description.title}")
            MediaBrowserCompat.MediaItem(
              item.description,
              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            )
          }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
        return
      }

      // Already initialized: just return
      val children = browseTree[parentMediaId]?.map { item ->
        Log.d(tag, "[MENU: $parentMediaId] Showing list item ${item.description.title}")
        MediaBrowserCompat.MediaItem(
          item.description,
          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
      }
      result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
    } else if (mediaManager.getIsLibrary(parentMediaId)) { // Load library items for library
      Log.d(tag, "Loading items for library $parentMediaId")
      val selectedLibrary = mediaManager.getLibrary(parentMediaId)
      if (selectedLibrary?.mediaType == "podcast") { // Podcasts are browseable
        mediaManager.loadLibraryPodcasts(parentMediaId) { libraryItems ->
          val children =
                  libraryItems?.map { libraryItem ->
                    val mediaDescription = libraryItem.getMediaDescription(null, ctx)
                    MediaBrowserCompat.MediaItem(
                            mediaDescription,
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else {
        val children =
                mutableListOf(
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Authors")
                                        .setMediaId("__LIBRARY__${parentMediaId}__AUTHORS")
                                        .setIconUri(getUriToAbsIconDrawable(ctx, "authors"))
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        ),
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Series")
                                        .setMediaId("__LIBRARY__${parentMediaId}__SERIES_LIST")
                                        .setIconUri(getUriToAbsIconDrawable(ctx, "columns"))
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        ),
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Collections")
                                        .setMediaId("__LIBRARY__${parentMediaId}__COLLECTIONS")
                                        .setIconUri(
                                                getUriToDrawable(
                                                        ctx,
                                                        R.drawable.md_book_multiple_outline
                                                )
                                        )
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                )
        if (mediaManager.getHasDiscovery(parentMediaId)) {
          children.add(
                  MediaBrowserCompat.MediaItem(
                          MediaDescriptionCompat.Builder()
                                  .setTitle("Discovery")
                                  .setMediaId("__LIBRARY__${parentMediaId}__DISCOVERY")
                                  .setIconUri(getUriToDrawable(ctx, R.drawable.md_telescope))
                                  .build(),
                          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                  )
          )
        }
        result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
      }
    } else if (parentMediaId.startsWith(RECENTLY_ROOT)) {
      Log.d(tag, "Browsing recently $parentMediaId")
      val mediaIdParts = parentMediaId.split("__")
      if (!mediaManager.getIsLibrary(mediaIdParts[2])) {
        Log.d(tag, "${mediaIdParts[2]} is not library")
        result.sendResult(null)
        return
      }
      Log.d(tag, "Mediaparts: ${mediaIdParts.size} | $mediaIdParts")
      if (mediaIdParts.size == 3) {
        mediaManager.getLibraryRecentShelfs(mediaIdParts[2]) { availableShelfs ->
          Log.d(tag, "Found ${availableShelfs.size} shelfs")
          val children: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
          for (shelf in availableShelfs) {
            if (shelf.type == "book") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Books")
                                      .setMediaId("${parentMediaId}__BOOK")
                                      .setIconUri(
                                              getUriToDrawable(
                                                      ctx,
                                                      R.drawable.md_book_open_blank_variant_outline
                                              )
                                      )
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "series") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Series")
                                      .setMediaId("${parentMediaId}__SERIES")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "columns"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "episode") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Episodes")
                                      .setMediaId("${parentMediaId}__EPISODE")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "microphone_2"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "podcast") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Podcast")
                                      .setMediaId("${parentMediaId}__PODCAST")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "podcast"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "authors") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Authors")
                                      .setMediaId("${parentMediaId}__AUTHORS")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "authors"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            }
          }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts.size == 4) {
        mediaManager.getLibraryRecentShelfByType(mediaIdParts[2], mediaIdParts[3]) { shelf ->
          if (shelf === null) {
            result.sendResult(mutableListOf())
          } else {
            if (shelf.type == "book") {
              val children =
                      (shelf as LibraryShelfBookEntity).entities?.map { libraryItem ->
                        val progress =
                                mediaManager.serverUserMediaProgress.find {
                                  it.libraryItemId == libraryItem.id
                                }
                        val localLibraryItem =
                                DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                        libraryItem.localLibraryItemId = localLibraryItem?.id
                        val description =
                                libraryItem.getMediaDescription(progress, ctx, null, false)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "episode") {
              val episodesWithRecentEpisode =
                      (shelf as LibraryShelfEpisodeEntity).entities?.filter { libraryItem ->
                        libraryItem.recentEpisode !== null
                      }
              val children =
                      episodesWithRecentEpisode?.map { libraryItem ->
                        libraryItem.media as Podcast
                        val progress =
                                mediaManager.serverUserMediaProgress.find {
                                  it.libraryItemId == libraryItem.libraryId &&
                                          it.episodeId == libraryItem.recentEpisode?.id
                                }

                        // to show download icon
                        val localLibraryItem =
                                DeviceManager.dbManager.getLocalLibraryItemByLId(
                                        libraryItem.recentEpisode!!.id
                                )
                        localLibraryItem?.let { lli ->
                          val localEpisode =
                                  (lli.media as Podcast).episodes?.find {
                                    it.serverEpisodeId == libraryItem.recentEpisode.id
                                  }
                          libraryItem.recentEpisode.localEpisodeId = localEpisode?.id
                        }

                        val description =
                                libraryItem.recentEpisode.getMediaDescription(
                                        libraryItem,
                                        progress,
                                        ctx
                                )
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "podcast") {
              val children =
                      (shelf as LibraryShelfPodcastEntity).entities?.map { libraryItem ->
                        val mediaDescription = libraryItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                mediaDescription,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "series") {
              val children =
                      (shelf as LibraryShelfSeriesEntity).entities?.map { librarySeriesItem ->
                        val description = librarySeriesItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "authors") {
              val children =
                      (shelf as LibraryShelfAuthorEntity).entities?.map { authorItem ->
                        val description = authorItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else {
              result.sendResult(mutableListOf())
            }
          }
        }
      }
    } else if (parentMediaId.startsWith("__LIBRARY__")) {
      Log.d(tag, "Browsing library $parentMediaId")
      val mediaIdParts = parentMediaId.split("__")
      /*
       MediaIdParts for Library
       1: LIBRARY
       2: mediaId for library
       3: Browsing style (AUTHORS, AUTHOR, AUTHOR_SERIES, SERIES_LIST, SERIES, COLLECTION, COLLECTIONS, DISCOVERY)
       4:
         - Paging: SERIES_LIST, AUTHORS
         - SeriesId: SERIES
         - AuthorId: AUTHOR, AUTHOR_SERIES
         - CollectionId: COLLECTIONS
       5: SeriesId: AUTHOR_SERIES
      */
      if (!mediaManager.getIsLibrary(mediaIdParts[2])) {
        Log.d(tag, "${mediaIdParts[2]} is not library")
        result.sendResult(null)
        return
      }
      Log.d(tag, "$mediaIdParts")
      if (mediaIdParts[3] == "SERIES_LIST" && mediaIdParts.size == 5) {
        Log.d(tag, "Loading series from library ${mediaIdParts[2]} with paging ${mediaIdParts[4]}")
        mediaManager.loadLibrarySeriesWithAudio(mediaIdParts[2], mediaIdParts[4]) { seriesItems ->
          Log.d(tag, "Received ${seriesItems.size} series")

          val seriesLetters =
                  seriesItems
                          .groupingBy { iwb ->
                            iwb.title.substring(0, mediaIdParts[4].length + 1).uppercase()
                          }
                          .eachCount()
          if (seriesItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping &&
                          seriesItems.size > 1 &&
                          seriesLetters.size > 1
          ) {
            val children =
                    seriesLetters.map { (seriesLetter, seriesCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(seriesLetter)
                                      .setMediaId("${parentMediaId}${seriesLetter.last()}")
                                      .setSubtitle("$seriesCount series")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    seriesItems.map { seriesItem ->
                      val description = seriesItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "SERIES_LIST") {
        Log.d(tag, "Loading series from library ${mediaIdParts[2]}")
        mediaManager.loadLibrarySeriesWithAudio(mediaIdParts[2]) { seriesItems ->
          Log.d(tag, "Received ${seriesItems.size} series")
          if (seriesItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping && seriesItems.size > 1
          ) {
            val seriesLetters =
                    seriesItems.groupingBy { iwb -> iwb.title.first().uppercaseChar() }.eachCount()
            val children =
                    seriesLetters.map { (seriesLetter, seriesCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(seriesLetter.toString())
                                      .setSubtitle("$seriesCount series")
                                      .setMediaId("${parentMediaId}__${seriesLetter}")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    seriesItems.map { seriesItem ->
                      val description = seriesItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "SERIES") {
        Log.d(tag, "Loading items for serie ${mediaIdParts[4]} from library ${mediaIdParts[2]}")
        mediaManager.loadLibrarySeriesItemsWithAudio(mediaIdParts[2], mediaIdParts[4]) {
                libraryItems ->
          Log.d(tag, "Received ${libraryItems.size} library items")
          var items = libraryItems
          if (DeviceManager.deviceData.deviceSettings!!.androidAutoBrowseSeriesSequenceOrder ===
                          AndroidAutoBrowseSeriesSequenceOrderSetting.DESC
          ) {
            items = libraryItems.reversed()
          }
          val children =
                  items.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx, null, true)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "AUTHORS" && mediaIdParts.size == 5) {
        Log.d(tag, "Loading authors from library ${mediaIdParts[2]} with paging ${mediaIdParts[4]}")
        mediaManager.loadAuthorsWithBooks(mediaIdParts[2], mediaIdParts[4]) { authorItems ->
          Log.d(tag, "Received ${authorItems.size} authors")

          val authorLetters =
                  authorItems
                          .groupingBy { iwb ->
                            iwb.name.substring(0, mediaIdParts[4].length + 1).uppercase()
                          }
                          .eachCount()
          if (authorItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping &&
                          authorItems.size > 1 &&
                          authorLetters.size > 1
          ) {
            val children =
                    authorLetters.map { (authorLetter, authorCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(authorLetter)
                                      .setMediaId("${parentMediaId}${authorLetter.last()}")
                                      .setSubtitle("$authorCount authors")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    authorItems.map { authorItem ->
                      val description = authorItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "AUTHORS") {
        Log.d(tag, "Loading authors from library ${mediaIdParts[2]}")
        mediaManager.loadAuthorsWithBooks(mediaIdParts[2]) { authorItems ->
          Log.d(tag, "Received ${authorItems.size} authors")
          if (authorItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping && authorItems.size > 1
          ) {
            val authorLetters =
                    authorItems.groupingBy { iwb -> iwb.name.first().uppercaseChar() }.eachCount()
            val children =
                    authorLetters.map { (authorLetter, authorCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(authorLetter.toString())
                                      .setSubtitle("$authorCount authors")
                                      .setMediaId("${parentMediaId}__${authorLetter}")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    authorItems.map { authorItem ->
                      val description = authorItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "AUTHOR") {
        mediaManager.loadAuthorBooksWithAudio(mediaIdParts[2], mediaIdParts[4]) { libraryItems ->
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    if (libraryItem.collapsedSeries != null) {
                      val description =
                              libraryItem.getMediaDescription(progress, ctx, mediaIdParts[4])
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    } else {
                      val description = libraryItem.getMediaDescription(progress, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                      )
                    }
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "AUTHOR_SERIES") {
        mediaManager.loadAuthorSeriesBooksWithAudio(
                mediaIdParts[2],
                mediaIdParts[4],
                mediaIdParts[5]
        ) { libraryItems ->
          var items = libraryItems
          if (DeviceManager.deviceData.deviceSettings!!.androidAutoBrowseSeriesSequenceOrder ===
                          AndroidAutoBrowseSeriesSequenceOrderSetting.DESC
          ) {
            items = libraryItems.reversed()
          }
          val children =
                  items.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx, null, true)
                    if (libraryItem.collapsedSeries != null) {
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    } else {
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                      )
                    }
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "COLLECTIONS") {
        Log.d(tag, "Loading collections from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryCollectionsWithAudio(mediaIdParts[2]) { collectionItems ->
          Log.d(tag, "Received ${collectionItems.size} collections")
          val children =
                  collectionItems.map { collectionItem ->
                    val description = collectionItem.getMediaDescription(null, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "COLLECTION") {
        Log.d(tag, "Loading collection ${mediaIdParts[4]} books from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryCollectionBooksWithAudio(mediaIdParts[2], mediaIdParts[4]) {
                libraryItems ->
          Log.d(tag, "Received ${libraryItems.size} collections")
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "DISCOVERY") {
        Log.d(tag, "Loading discovery from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryDiscoveryBooksWithAudio(mediaIdParts[2]) { libraryItems ->
          Log.d(tag, "Received ${libraryItems.size} libraryItems for discovery")
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else {
        result.sendResult(null)
      }
    } else {
      Log.d(tag, "Loading podcast episodes for podcast $parentMediaId")
      mediaManager.loadPodcastEpisodeMediaBrowserItems(parentMediaId, ctx) { result.sendResult(it) }
    }
  }

  override fun onSearch(
          query: String,
          extras: Bundle?,
          result: Result<MutableList<MediaBrowserCompat.MediaItem>>
  ) {
    result.detach()
    if (cachedSearch != query) {
      Log.d(tag, "Search bundle: $extras")
      var foundBooks: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      var foundPodcasts: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      var foundSeries: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      var foundAuthors: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

      mediaManager.serverLibraries.forEach { serverLibrary ->
        runBlocking {
          // Skip searching library if it doesn't have any audio files
          if (serverLibrary.stats?.numAudioFiles == 0) return@runBlocking
          val searchResult = mediaManager.doSearch(serverLibrary.id, query)
          for (resultData in searchResult.entries.iterator()) {
            when (resultData.key) {
              "book" -> foundBooks.addAll(resultData.value)
              "series" -> foundSeries.addAll(resultData.value)
              "authors" -> foundAuthors.addAll(resultData.value)
              "podcast" -> foundPodcasts.addAll(resultData.value)
            }
          }
        }
      }
      foundBooks.addAll(foundSeries)
      foundBooks.addAll(foundAuthors)
      cachedSearchResults = foundBooks
    }
    result.sendResult(cachedSearchResults)
    cachedSearch = query
    Log.d(tag, "onSearch: Done")
  }

  //
  // SHAKE SENSOR
  //
  private fun initSensor() {
    sleepTimerShakeController = SleepTimerShakeController(
      this,
      SLEEP_TIMER_WAKE_UP_EXPIRATION,
      serviceScope
    ) {
      Log.d(tag, "PHONE SHAKE!")
      (externalSleepTimerManager ?: sleepTimerManager).handleShake()
    }
  }

  // Shake sensor used for sleep timer
  override fun registerSensor() {
    sleepTimerShakeController?.register()
  }

  override fun unregisterSensor() {
    sleepTimerShakeController?.scheduleUnregister()
  }

  private val networkCallback =
          object : ConnectivityManager.NetworkCallback() {
            // Network capabilities have changed for the network
            override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
            ) {
              super.onCapabilitiesChanged(network, networkCapabilities)

                    Companion.isUnmeteredNetwork =
                      networkCapabilities.hasCapability(
                              NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                      )
              hasNetworkConnectivity =
                      networkCapabilities.hasCapability(
                              NetworkCapabilities.NET_CAPABILITY_VALIDATED
                      ) &&
                              networkCapabilities.hasCapability(
                                      NetworkCapabilities.NET_CAPABILITY_INTERNET
                              )
              Log.i(
                      tag,
                      "Network capabilities changed. hasNetworkConnectivity=$hasNetworkConnectivity | isUnmeteredNetwork=${Companion.isUnmeteredNetwork}"
              )
                    clientEventEmitter?.onNetworkMeteredChanged(Companion.isUnmeteredNetwork)
              if (hasNetworkConnectivity) {
                // Force android auto loading if libraries are empty.
                // Lack of network connectivity is most likely reason for libraries being empty
                if (isBrowseTreeInitialized() &&
                                firstLoadDone &&
                                mediaManager.serverLibraries.isEmpty()
                ) {
                  forceReloadingAndroidAuto = true
                  notifyChildrenChanged("/")
                }
              }
            }
          }

  inner class JumpBackwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_JUMP_BACKWARD,
                      ctx.getString(R.string.action_jump_backward),
                      R.drawable.exo_icon_rewind
              )
              .build()
    }
  }

  inner class JumpForwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_JUMP_FORWARD,
                      ctx.getString(R.string.action_jump_forward),
                      R.drawable.exo_icon_fastforward
              )
              .build()
    }
  }

  inner class SkipForwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_SKIP_FORWARD,
                      ctx.getString(R.string.action_skip_forward),
                      R.drawable.skip_next_24
              )
              .build()
    }
  }

  inner class SkipBackwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_SKIP_BACKWARD,
                      ctx.getString(R.string.action_skip_backward),
                      R.drawable.skip_previous_24
              )
              .build()
    }
  }

  inner class ChangePlaybackSpeedCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      val playbackRate = mediaManager.getSavedPlaybackRate()

      // Rounding values in the event a non preset value (.5, 1, 1.2, 1.5, 2, 3) is selected in the
      // phone app
      val drawable: Int =
              when (playbackRate) {
                in 0.5f..0.7f -> R.drawable.ic_play_speed_0_5x
                in 0.8f..1.0f -> R.drawable.ic_play_speed_1_0x
                in 1.1f..1.3f -> R.drawable.ic_play_speed_1_2x
                in 1.4f..1.7f -> R.drawable.ic_play_speed_1_5x
                in 1.8f..2.4f -> R.drawable.ic_play_speed_2_0x
                in 2.5f..3.0f -> R.drawable.ic_play_speed_3_0x
                // anything set above 3 will be show the 3x to save from creating 100 icons
                else -> R.drawable.ic_play_speed_3_0x
              }
      val customActionExtras = Bundle()
      customActionExtras.putFloat("speed", playbackRate)
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_CHANGE_SPEED,
                      ctx.getString(R.string.action_change_speed),
                      drawable
              )
              .setExtras(customActionExtras)
              .build()
    }
  }
}
