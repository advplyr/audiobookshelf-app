package com.audiobookshelf.app.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
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
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.annotation.JsonIgnore
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.CustomActionProvider
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.*
import java.util.*
import kotlin.concurrent.schedule


const val SLEEP_TIMER_WAKE_UP_EXPIRATION = 120000L // 2m
const val PLAYER_CAST = "cast-player";
const val PLAYER_EXO = "exo-player";

class PlayerNotificationService : MediaBrowserServiceCompat()  {

  companion object {
    var isStarted = false
    var isClosed = false
    var isUnmeteredNetwork = false
    var isSwitchingPlayer = false // Used when switching between cast player and exoplayer
  }

  interface ClientEventEmitter {
    fun onPlaybackSession(playbackSession:PlaybackSession)
    fun onPlaybackClosed()
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: PlaybackMetadata)
    fun onSleepTimerEnded(currentPosition: Long)
    fun onSleepTimerSet(sleepTimeRemaining: Int)
    fun onLocalMediaProgressUpdate(localMediaProgress: LocalMediaProgress)
    fun onPlaybackFailed(errorMessage:String)
    fun onMediaPlayerChanged(mediaPlayer:String)
    fun onProgressSyncFailing()
    fun onProgressSyncSuccess()
    fun onNetworkMeteredChanged(isUnmetered:Boolean)
  }

  private val tag = "PlayerService"
  private val binder = LocalBinder()

  var clientEventEmitter:ClientEventEmitter? = null

  private lateinit var ctx:Context
  private lateinit var mediaSessionConnector: MediaSessionConnector
  private lateinit var playerNotificationManager: PlayerNotificationManager
  lateinit var mediaSession: MediaSessionCompat
  private lateinit var transportControls:MediaControllerCompat.TransportControls

  lateinit var mediaManager: MediaManager
  lateinit var apiHandler: ApiHandler

  lateinit var mPlayer: ExoPlayer
  lateinit var currentPlayer:Player
  var castPlayer:CastPlayer? = null

  lateinit var sleepTimerManager:SleepTimerManager
  lateinit var mediaProgressSyncer:MediaProgressSyncer

  private var notificationId = 10
  private var channelId = "audiobookshelf_channel"
  private var channelName = "Audiobookshelf Channel"

  private var currentPlaybackSession:PlaybackSession? = null
  private var initialPlaybackRate:Float? = null

  private var isAndroidAuto = false

  // The following are used for the shake detection
  private var isShakeSensorRegistered:Boolean = false
  private var mSensorManager: SensorManager? = null
  private var mAccelerometer: Sensor? = null
  private var mShakeDetector: ShakeDetector? = null
  private var shakeSensorUnregisterTask:TimerTask? = null

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

  override fun onStart(intent: Intent?, startId: Int) {
    Log.d(tag, "onStart $startId")
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createNotificationChannel(channelId: String, channelName: String): String {
    val chan = NotificationChannel(channelId,
      channelName, NotificationManager.IMPORTANCE_LOW)
    chan.lightColor = Color.DKGRAY
    chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    service.createNotificationChannel(chan)
    return channelId
  }

  // detach player
  override fun onDestroy() {
    try {
      val connectivityManager = getSystemService(ConnectivityManager::class.java) as ConnectivityManager
      connectivityManager.unregisterNetworkCallback(networkCallback)
    } catch(error:Exception) {
      Log.e(tag, "Error unregistering network listening callback $error")
    }

    Log.d(tag, "onDestroy")
    playerNotificationManager.setPlayer(null)
    mPlayer.release()
    castPlayer?.release()
    mediaSession.release()
    mediaProgressSyncer.reset()
    Log.d(tag, "onDestroy")
    isStarted = false

    super.onDestroy()
  }

  //removing service when user swipe out our app
  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    Log.d(tag, "onTaskRemoved")
    stopSelf()
  }

  override fun onCreate() {
    Log.d(tag, "onCreate")
    super.onCreate()
    ctx = this

    // To listen for network change from metered to unmetered
    val networkRequest = NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
      .build()
    val connectivityManager = getSystemService(ConnectivityManager::class.java) as ConnectivityManager
    connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

    DbManager.initialize(ctx)

    // Initialize API
    apiHandler = ApiHandler(ctx)

    // Initialize sleep timer
    sleepTimerManager = SleepTimerManager(this)

    // Initialize Media Progress Syncer
    mediaProgressSyncer = MediaProgressSyncer(this, apiHandler)

    // Initialize shake sensor
    Log.d(tag, "onCreate Register sensor listener ${mAccelerometer?.isWakeUpSensor}")
    initSensor()

    // Initialize media manager
    mediaManager = MediaManager(apiHandler, ctx)

    channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createNotificationChannel(channelId, channelName)
    } else ""

    val sessionActivityPendingIntent =
      packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
        PendingIntent.getActivity(this, 0, sessionIntent, PendingIntent.FLAG_IMMUTABLE)
      }

    mediaSession = MediaSessionCompat(this, tag)
      .apply {
        setSessionActivity(sessionActivityPendingIntent)
        isActive = true
        setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
      }

    val mediaController = MediaControllerCompat(ctx, mediaSession.sessionToken)

    // This is for Media Browser
    sessionToken = mediaSession.sessionToken

    val builder = PlayerNotificationManager.Builder(
      ctx,
      notificationId,
      channelId)

    builder.setMediaDescriptionAdapter(AbMediaDescriptionAdapter(mediaController, this))
    builder.setNotificationListener(PlayerNotificationListener(this))

    playerNotificationManager = builder.build()
    playerNotificationManager.setMediaSessionToken(mediaSession.sessionToken)
    playerNotificationManager.setUsePlayPauseActions(true)
    playerNotificationManager.setUseNextAction(false)
    playerNotificationManager.setUsePreviousAction(false)
    playerNotificationManager.setUseChronometer(false)
    playerNotificationManager.setUseStopAction(true)
    playerNotificationManager.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    playerNotificationManager.setPriority(NotificationCompat.PRIORITY_MAX)
    playerNotificationManager.setUseFastForwardActionInCompactView(true)
    playerNotificationManager.setUseRewindActionInCompactView(true)

    // Unknown action
    playerNotificationManager.setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)

    transportControls = mediaController.transportControls

    mediaSessionConnector = MediaSessionConnector(mediaSession)
    val queueNavigator: TimelineQueueNavigator = object : TimelineQueueNavigator(mediaSession) {
      override fun getSupportedQueueNavigatorActions(player: Player): Long {
        return PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE
      }

      override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
        if (currentPlaybackSession == null) {
          Log.e(tag,"Playback session is not set - returning blank MediaDescriptionCompat")
          return MediaDescriptionCompat.Builder().build()
        }

        val coverUri = currentPlaybackSession!!.getCoverUri()

        // Fix for local images crashing on Android 11 for specific devices
        // https://stackoverflow.com/questions/64186578/android-11-mediastyle-notification-crash/64232958#64232958
        try {
          ctx.grantUriPermission(
            "com.android.systemui",
            coverUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
          )
        } catch(error:Exception) {
          Log.e(tag, "Grant uri permission error $error")
        }

        return MediaDescriptionCompat.Builder()
          .setMediaId(currentPlaybackSession!!.id)
          .setTitle(currentPlaybackSession!!.displayTitle)
          .setSubtitle(currentPlaybackSession!!.displayAuthor)
          .setIconUri(coverUri).build()
      }
    }

    mediaSessionConnector.setEnabledPlaybackActions(
      PlaybackStateCompat.ACTION_PLAY_PAUSE
        or PlaybackStateCompat.ACTION_PLAY
        or PlaybackStateCompat.ACTION_PAUSE
        or PlaybackStateCompat.ACTION_SEEK_TO
        or PlaybackStateCompat.ACTION_FAST_FORWARD
        or PlaybackStateCompat.ACTION_REWIND
        or PlaybackStateCompat.ACTION_STOP
    )
    mediaSessionConnector.setQueueNavigator(queueNavigator)
    mediaSessionConnector.setPlaybackPreparer(MediaSessionPlaybackPreparer(this))

    mediaSession.setCallback(MediaSessionCallback(this))

    initializeMPlayer()
    currentPlayer = mPlayer
  }

  private fun initializeMPlayer() {
    val customLoadControl:LoadControl = DefaultLoadControl.Builder().setBufferDurationsMs(
      1000 * 20, // 20s min buffer
      1000 * 45, // 45s max buffer
      1000 * 5, // 5s playback start
      1000 * 20 // 20s playback rebuffer
    ).build()

    mPlayer = ExoPlayer.Builder(this)
      .setLoadControl(customLoadControl)
      .setSeekBackIncrementMs(deviceSettings.jumpBackwardsTimeMs)
      .setSeekForwardIncrementMs(deviceSettings.jumpForwardTimeMs)
      .build()
    mPlayer.setHandleAudioBecomingNoisy(true)
    mPlayer.addListener(PlayerListener(this))
    val audioAttributes:AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_SPEECH).build()
    mPlayer.setAudioAttributes(audioAttributes, true)

    //attach player to playerNotificationManager
    playerNotificationManager.setPlayer(mPlayer)

    mediaSessionConnector.setPlayer(mPlayer)
  }

  /*
    User callable methods
  */
  fun preparePlayer(playbackSession: PlaybackSession, playWhenReady:Boolean, playbackRate:Float?) {
    if (!isStarted) {
      Log.i(tag, "preparePlayer: foreground service not started - Starting service --")
      Intent(ctx, PlayerNotificationService::class.java).also { intent ->
        ContextCompat.startForegroundService(ctx, intent)
      }
    }

    isClosed = false
    val customActionProviders = mutableListOf(
      JumpBackwardCustomActionProvider(),
      JumpForwardCustomActionProvider(),
    )
    val metadata = playbackSession.getMediaMetadataCompat()
    mediaSession.setMetadata(metadata)
    val mediaItems = playbackSession.getMediaItems()
    val playbackRateToUse = playbackRate ?: initialPlaybackRate ?: 1f
    initialPlaybackRate = playbackRate

    if (playbackSession.mediaPlayer != PLAYER_CAST && mediaItems.size > 1) {
      customActionProviders.addAll(listOf(
        SkipBackwardCustomActionProvider(),
        SkipForwardCustomActionProvider(),
      ));
    }
    mediaSessionConnector.setCustomActionProviders(*customActionProviders.toTypedArray());

    playbackSession.mediaPlayer = getMediaPlayer()

    if (playbackSession.mediaPlayer == PLAYER_CAST && playbackSession.isLocal) {
      Log.w(tag, "Cannot cast local media item - switching player")
      currentPlaybackSession = null
      switchToPlayer(false)
      playbackSession.mediaPlayer = getMediaPlayer()
    }

    if (playbackSession.mediaPlayer == PLAYER_CAST) {
      // If cast-player is the first player to be used
      mediaSessionConnector.setPlayer(castPlayer)
      playerNotificationManager.setPlayer(castPlayer)
    }

    currentPlaybackSession = playbackSession
    Log.d(tag, "Set CurrentPlaybackSession MediaPlayer ${currentPlaybackSession?.mediaPlayer}")

    clientEventEmitter?.onPlaybackSession(playbackSession)

    if (mediaItems.isEmpty()) {
      Log.e(tag, "Invalid playback session no media items to play")
      currentPlaybackSession = null
      return
    }

    if (mPlayer == currentPlayer) {
      val mediaSource:MediaSource

      if (playbackSession.isLocal) {
        Log.d(tag, "Playing Local Item")
        val dataSourceFactory = DefaultDataSource.Factory(ctx)
        mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItems[0])
      } else if (!playbackSession.isHLS) {
        Log.d(tag, "Direct Playing Item")
        val dataSourceFactory = DefaultHttpDataSource.Factory()
        dataSourceFactory.setUserAgent(channelId)
        mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItems[0])
      } else {
        Log.d(tag, "Playing HLS Item")
        val dataSourceFactory = DefaultHttpDataSource.Factory()
        dataSourceFactory.setUserAgent(channelId)
        dataSourceFactory.setDefaultRequestProperties(hashMapOf("Authorization" to "Bearer ${DeviceManager.token}"))
        mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItems[0])
      }
      mPlayer.setMediaSource(mediaSource)


      // Add remaining media items if multi-track
      if (mediaItems.size > 1) {
        currentPlayer.addMediaItems(mediaItems.subList(1, mediaItems.size))
        Log.d(tag, "currentPlayer total media items ${currentPlayer.mediaItemCount}")

        val currentTrackIndex = playbackSession.getCurrentTrackIndex()
        val currentTrackTime = playbackSession.getCurrentTrackTimeMs()
        Log.d(tag, "currentPlayer current track index $currentTrackIndex & current track time $currentTrackTime")
        currentPlayer.seekTo(currentTrackIndex, currentTrackTime)
      } else {
        currentPlayer.seekTo(playbackSession.currentTimeMs)
      }

      Log.d(tag, "Prepare complete for session ${currentPlaybackSession?.displayTitle} | ${currentPlayer.mediaItemCount}")
      currentPlayer.playWhenReady = playWhenReady
      currentPlayer.setPlaybackSpeed(playbackRateToUse)

      currentPlayer.prepare()
    } else if (castPlayer != null) {
      val currentTrackIndex = playbackSession.getCurrentTrackIndex()
      val currentTrackTime = playbackSession.getCurrentTrackTimeMs()
      val mediaType = playbackSession.mediaType
      Log.d(tag, "Loading cast player $currentTrackIndex $currentTrackTime $mediaType")

      castPlayer?.load(mediaItems, currentTrackIndex, currentTrackTime, playWhenReady, playbackRateToUse, mediaType)
    }
  }

  fun handlePlayerPlaybackError(errorMessage:String) {
    // On error and was attempting to direct play - fallback to transcode
    currentPlaybackSession?.let { playbackSession ->
      if (playbackSession.isDirectPlay) {
        val playItemRequestPayload = getPlayItemRequestPayload(true)
        Log.d(tag, "Fallback to transcode $playItemRequestPayload.mediaPlayer")

        val libraryItemId = playbackSession.libraryItemId ?: "" // Must be true since direct play
        val episodeId = playbackSession.episodeId
        apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
          if (it == null) { // Play request failed
            clientEventEmitter?.onPlaybackFailed(errorMessage)
            closePlayback(true)
          } else {
            Handler(Looper.getMainLooper()).post {
              preparePlayer(it, true, null)
            }
          }
        }
      } else {
        clientEventEmitter?.onPlaybackFailed(errorMessage)
        closePlayback(true)
      }
    }
  }

  fun startNewPlaybackSession() {
    currentPlaybackSession?.let { playbackSession ->
      val forceTranscode = playbackSession.isHLS // If already HLS then force
      val playItemRequestPayload = getPlayItemRequestPayload(forceTranscode)

      val libraryItemId = playbackSession.libraryItemId ?: "" // Must be true since direct play
      val episodeId = playbackSession.episodeId
      apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
        if (it == null) {
          Log.e(tag, "Failed to start new playback session")
        } else {
          Handler(Looper.getMainLooper()).post {
            preparePlayer(it, true, null)
          }
        }
      }
    }
  }

  fun switchToPlayer(useCastPlayer: Boolean) {
    val wasPlaying = currentPlayer.isPlaying
    if (useCastPlayer) {
      if (currentPlayer == castPlayer) {
        Log.d(tag, "switchToPlayer: Already using Cast Player " + castPlayer?.deviceInfo)
        return
      } else {
        Log.d(tag, "switchToPlayer: Switching to cast player from exo player stop exo player")
        mPlayer.stop()
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

    // Playback session in progress syncer is a copy that is up-to-date so replace current here with that
    //  TODO: bad design here implemented to prevent the session in MediaProgressSyncer from changing while syncing
    if (mediaProgressSyncer.currentPlaybackSession != null) {
      currentPlaybackSession = mediaProgressSyncer.currentPlaybackSession?.clone()
    }

    currentPlayer = if (useCastPlayer) {
      Log.d(tag, "switchToPlayer: Using Cast Player " + castPlayer?.deviceInfo)
      mediaSessionConnector.setPlayer(castPlayer)
      playerNotificationManager.setPlayer(castPlayer)
      castPlayer as CastPlayer
    } else {
      Log.d(tag, "switchToPlayer: Using ExoPlayer")
      mediaSessionConnector.setPlayer(mPlayer)
      playerNotificationManager.setPlayer(mPlayer)
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

  fun getCurrentTime() : Long {
    return if (currentPlayer.mediaItemCount > 1) {
      val windowIndex = currentPlayer.currentMediaItemIndex
      val currentTrackStartOffset = currentPlaybackSession?.getTrackStartOffsetMs(windowIndex) ?: 0L
      currentPlayer.currentPosition + currentTrackStartOffset
    } else {
      currentPlayer.currentPosition
    }
  }

  fun getCurrentTimeSeconds() : Double {
    return getCurrentTime() / 1000.0
  }

  private fun getBufferedTime() : Long {
    return if (currentPlayer.mediaItemCount > 1) {
      val windowIndex = currentPlayer.currentMediaItemIndex
      val currentTrackStartOffset = currentPlaybackSession?.getTrackStartOffsetMs(windowIndex) ?: 0L
      currentPlayer.bufferedPosition + currentTrackStartOffset
    } else {
      currentPlayer.bufferedPosition
    }
  }

  fun getBufferedTimeSeconds() : Double {
    return getBufferedTime() / 1000.0
  }

  fun getDuration() : Long {
    return currentPlaybackSession?.totalDurationMs ?: 0L
  }

  fun getCurrentPlaybackSessionCopy() :PlaybackSession? {
    return currentPlaybackSession?.clone()
  }

  fun getCurrentPlaybackSessionId() :String? {
    return currentPlaybackSession?.id
  }

  fun getCurrentBookChapter():BookChapter? {
    return currentPlaybackSession?.getChapterForTime(this.getCurrentTime())
  }

  // Called from PlayerListener play event
  // check with server if progress has updated since last play and sync progress update
  fun checkCurrentSessionProgress(seekBackTime:Long):Boolean {
    if (currentPlaybackSession == null) return true

    mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
      if (!apiHandler.isOnline() || playbackSession.isLocalLibraryItemOnly) {
        return true // carry on
      }

      if (playbackSession.isLocal) {

        // Make sure this connection config exists
        val serverConnectionConfig = DeviceManager.getServerConnectionConfig(playbackSession.serverConnectionConfigId)
        if (serverConnectionConfig == null) {
          Log.d(tag, "checkCurrentSessionProgress: Local library item server connection config is not saved ${playbackSession.serverConnectionConfigId}")
          return true // carry on
        }

        // Local playback session check if server has updated media progress
        Log.d(tag, "checkCurrentSessionProgress: Checking if local media progress was updated on server")
        apiHandler.getMediaProgress(playbackSession.libraryItemId!!, playbackSession.episodeId, serverConnectionConfig) { mediaProgress ->

          if (mediaProgress != null && mediaProgress.lastUpdate > playbackSession.updatedAt && mediaProgress.currentTime != playbackSession.currentTime) {
            Log.d(tag, "checkCurrentSessionProgress: Media progress was updated since last play time updating from ${playbackSession.currentTime} to ${mediaProgress.currentTime}")
            mediaProgressSyncer.syncFromServerProgress(mediaProgress)

            // Update current playback session stored in PNS since MediaProgressSyncer version is a copy
            mediaProgressSyncer.currentPlaybackSession?.let { updatedPlaybackSession ->
              currentPlaybackSession = updatedPlaybackSession
            }

            Handler(Looper.getMainLooper()).post {
              seekPlayer(playbackSession.currentTimeMs)
              // Should already be playing
              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              mediaProgressSyncer.start()
              clientEventEmitter?.onPlayingUpdate(true)
            }
          } else {
            Handler(Looper.getMainLooper()).post {
              if (seekBackTime > 0L) {
                seekBackward(seekBackTime)
              }
              // Should already be playing
              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              mediaProgressSyncer.start()
              clientEventEmitter?.onPlayingUpdate(true)
            }
          }
        }
      } else {
        // Streaming from server so check if playback session still exists on server
        Log.d(
          tag,
          "checkCurrentSessionProgress: Checking if playback session for server stream is still available"
        )
        apiHandler.getPlaybackSession(playbackSession.id) {
          if (it == null) {
            Log.d(
              tag,
              "checkCurrentSessionProgress: Playback session does not exist on server - start new playback session"
            )

            Handler(Looper.getMainLooper()).post {
              currentPlayer.pause()
              startNewPlaybackSession()
            }
          } else {
              Log.d(tag, "checkCurrentSessionProgress: Playback session still available on server")
              Handler(Looper.getMainLooper()).post {
                if (seekBackTime > 0L) {
                  seekBackward(seekBackTime)
                }

                currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
                mediaProgressSyncer.start()
                clientEventEmitter?.onPlayingUpdate(true)
              }
          }
        }
      }
    }
    return false
  }

  fun play() {
    if (currentPlayer.isPlaying) {
      Log.d(tag, "Already playing")
      return
    }
    currentPlayer.volume = 1F
    currentPlayer.play()
  }

  fun pause() {
    currentPlayer.pause()
  }

  fun playPause():Boolean {
    return if (currentPlayer.isPlaying) {
      pause()
      false
    } else {
      play()
      true
    }
  }

  fun seekPlayer(time: Long) {
    Log.d(tag, "seekPlayer mediaCount = ${currentPlayer.mediaItemCount} | $time")
    if (currentPlayer.mediaItemCount > 1) {
      currentPlaybackSession?.currentTime = time / 1000.0
      val newWindowIndex = currentPlaybackSession?.getCurrentTrackIndex() ?: 0
      val newTimeOffset = currentPlaybackSession?.getCurrentTrackTimeMs() ?: 0
      currentPlayer.seekTo(newWindowIndex, newTimeOffset)
    } else {
      currentPlayer.seekTo(time)
    }
  }

  fun skipToPrevious() {
    currentPlayer.seekToPrevious()
  }

  fun skipToNext() {
    currentPlayer.seekToNext()
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

  fun seekBackward(amount: Long) {
    seekPlayer(getCurrentTime() - amount)
  }

  fun setPlaybackSpeed(speed: Float) {
    currentPlayer.setPlaybackSpeed(speed)
  }

  fun closePlayback(calledOnError:Boolean? = false) {
    Log.d(tag, "closePlayback")
    if (mediaProgressSyncer.listeningTimerRunning) {
      Log.i(tag, "About to close playback so stopping media progress syncer first")
      mediaProgressSyncer.stop(calledOnError == false) { // If closing on error then do not sync progress (causes exception)
        Log.d(tag, "Media Progress syncer stopped")
      }
    }

    try {
      currentPlayer.stop()
      currentPlayer.clearMediaItems()
    } catch(e:Exception) {
      Log.e(tag, "Exception clearing exoplayer $e")
    }

    currentPlaybackSession = null
    clientEventEmitter?.onPlaybackClosed()
    PlayerListener.lastPauseTime = 0
    isClosed = true
    stopForeground(true)
    stopSelf()
  }

  fun sendClientMetadata(playerState: PlayerState) {
    val duration = currentPlaybackSession?.getTotalDuration() ?: 0.0
    clientEventEmitter?.onMetadata(PlaybackMetadata(duration, getCurrentTimeSeconds(), playerState))
  }

  fun getMediaPlayer():String {
    return if(currentPlayer == castPlayer) PLAYER_CAST else PLAYER_EXO
  }

  fun getDeviceInfo(): DeviceInfo {
    /* EXAMPLE
      manufacturer: Google
      model: Pixel 6
      brand: google
      sdkVersion: 32
      appVersion: 0.9.46-beta
     */
    return DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.BRAND, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME)
  }

  @get:JsonIgnore
  val deviceSettings get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  fun getPlayItemRequestPayload(forceTranscode:Boolean):PlayItemRequestPayload {
    return PlayItemRequestPayload(getMediaPlayer(), !forceTranscode, forceTranscode, getDeviceInfo())
  }

  fun getContext():Context {
    return ctx
  }

  fun alertSyncFailing() {
    clientEventEmitter?.onProgressSyncFailing()
  }

  fun alertSyncSuccess() {
    clientEventEmitter?.onProgressSyncSuccess()
  }

  //
  // MEDIA BROWSER STUFF (ANDROID AUTO)
  //
  private val ANDROID_AUTO_PKG_NAME = "com.google.android.projection.gearhead"
  private val ANDROID_AUTO_SIMULATOR_PKG_NAME = "com.google.android.autosimulator"
  private val ANDROID_WEARABLE_PKG_NAME = "com.google.android.wearable.app"
  private val ANDROID_GSEARCH_PKG_NAME = "com.google.android.googlequicksearchbox"
  private val ANDROID_AUTOMOTIVE_PKG_NAME = "com.google.android.carassistant"
  private val VALID_MEDIA_BROWSERS = mutableListOf("com.audiobookshelf.app", ANDROID_AUTO_PKG_NAME, ANDROID_AUTO_SIMULATOR_PKG_NAME, ANDROID_WEARABLE_PKG_NAME, ANDROID_GSEARCH_PKG_NAME, ANDROID_AUTOMOTIVE_PKG_NAME)

  private val AUTO_MEDIA_ROOT = "/"
  private val ALL_ROOT = "__ALL__"
  private val LIBRARIES_ROOT = "__LIBRARIES__"
  private lateinit var browseTree:BrowseTree


  // Only allowing android auto or similar to access media browser service
  //  normal loading of audiobooks is handled in webview (not natively)
  private fun isValid(packageName: String, uid: Int) : Boolean {
    Log.d(tag, "onGetRoot: Checking package $packageName with uid $uid")
    if (!VALID_MEDIA_BROWSERS.contains(packageName)) {
      Log.d(tag, "onGetRoot: package $packageName not valid for the media browser service")
      return false
    }
    return true
  }

  override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
    // Verify that the specified package is allowed to access your content
    return if (!isValid(clientPackageName, clientUid)) {
      // No further calls will be made to other media browsing methods.
      null
    } else {
      isStarted = true
      mediaManager.checkResetServerItems() // Reset any server items if no longer connected to server

      isAndroidAuto = true

      val extras = Bundle()
      extras.putBoolean(
        MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)

      BrowserRoot(AUTO_MEDIA_ROOT, extras)
    }
  }

  override fun onLoadChildren(parentMediaId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
    Log.d(tag, "ON LOAD CHILDREN $parentMediaId")

    val flag = if (parentMediaId == AUTO_MEDIA_ROOT || parentMediaId == LIBRARIES_ROOT) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE

    result.detach()

    if (parentMediaId.startsWith("li_") || parentMediaId.startsWith("local_")) { // Show podcast episodes
      Log.d(tag, "Loading podcast episodes")
      mediaManager.loadPodcastEpisodeMediaBrowserItems(parentMediaId) {
        result.sendResult(it)
      }
    } else if (::browseTree.isInitialized && browseTree[parentMediaId] == null && mediaManager.getIsLibrary(parentMediaId)) { // Load library items for library

      mediaManager.loadLibraryItemsWithAudio(parentMediaId) { libraryItems ->
        val children = libraryItems.map { libraryItem ->
          val libraryItemMediaMetadata = libraryItem.getMediaMetadata()

          if (libraryItem.mediaType == "podcast") { // Podcasts are browseable
            MediaBrowserCompat.MediaItem(libraryItemMediaMetadata.description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
          } else {
            val progress = mediaManager.serverUserMediaProgress.find { it.libraryItemId == libraryItemMediaMetadata.description.mediaId }
            val description = mediaManager.getMediaDescriptionFromMediaMetadata(libraryItemMediaMetadata, progress)
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
          }
        }
        result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
      }
    } else if (parentMediaId == "__DOWNLOADS__") { // Load downloads

      val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
      val localPodcasts = DeviceManager.dbManager.getLocalLibraryItems("podcast")
      val localBrowseItems:MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

      localBooks.forEach { localLibraryItem ->
        val mediaMetadata = localLibraryItem.getMediaMetadata()
        val progress = DeviceManager.dbManager.getLocalMediaProgress(mediaMetadata.description.mediaId ?: "")
        val description = mediaManager.getMediaDescriptionFromMediaMetadata(mediaMetadata, progress)

        localBrowseItems += MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
      }

      localPodcasts.forEach { localLibraryItem ->
        val mediaMetadata = localLibraryItem.getMediaMetadata()
        localBrowseItems += MediaBrowserCompat.MediaItem(mediaMetadata.description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
      }

      result.sendResult(localBrowseItems)

    } else { // Load categories
      mediaManager.loadAndroidAutoItems { libraryCategories ->
        browseTree = BrowseTree(this, libraryCategories, mediaManager.serverLibraries)

        val children = browseTree[parentMediaId]?.map { item ->
          Log.d(tag, "Loading Browser Media Item ${item.description.title} $flag")

          if (flag == MediaBrowserCompat.MediaItem.FLAG_PLAYABLE) {
            val progress = mediaManager.serverUserMediaProgress.find { it.libraryItemId == item.description.mediaId }
            val description = mediaManager.getMediaDescriptionFromMediaMetadata(item, progress)
            MediaBrowserCompat.MediaItem(description, flag)
          } else {
            MediaBrowserCompat.MediaItem(item.description, flag)
          }
        }
        result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
      }
    }
  }

  override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
    result.detach()
    mediaManager.loadAndroidAutoItems() { libraryCategories ->
      browseTree = BrowseTree(this, libraryCategories, mediaManager.serverLibraries)
      val children = browseTree[ALL_ROOT]?.map { item ->
        MediaBrowserCompat.MediaItem(item.description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
      }
      result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
    }
  }

  //
  // SHAKE SENSOR
  //
  private fun initSensor() {
    // ShakeDetector initialization
    mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    mAccelerometer = mSensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    mShakeDetector = ShakeDetector()
    mShakeDetector!!.setOnShakeListener(object : ShakeDetector.OnShakeListener {
      override fun onShake(count: Int) {
        Log.d(tag, "PHONE SHAKE! $count")
        sleepTimerManager.handleShake()
      }
    })
  }

  // Shake sensor used for sleep timer
  fun registerSensor() {
    if (isShakeSensorRegistered) {
      Log.w(tag, "Shake sensor already registered")
      return
    }
    shakeSensorUnregisterTask?.cancel()

    Log.d(tag, "Registering shake SENSOR ${mAccelerometer?.isWakeUpSensor}")
    val success = mSensorManager!!.registerListener(
      mShakeDetector,
      mAccelerometer,
      SensorManager.SENSOR_DELAY_UI
    )
    if (success) isShakeSensorRegistered = true
  }

  fun unregisterSensor() {
    if (!isShakeSensorRegistered) return

    // Unregister shake sensor after wake up expiration
    shakeSensorUnregisterTask?.cancel()
    shakeSensorUnregisterTask = Timer("ShakeUnregisterTimer", false).schedule(SLEEP_TIMER_WAKE_UP_EXPIRATION) {
      Handler(Looper.getMainLooper()).post() {
        Log.d(tag, "wake time expired: Unregistering shake sensor")
        mSensorManager!!.unregisterListener(mShakeDetector)
        isShakeSensorRegistered = false
      }
    }
  }

  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    // Network capabilities have changed for the network
    override fun onCapabilitiesChanged(
      network: Network,
      networkCapabilities: NetworkCapabilities
    ) {
      super.onCapabilitiesChanged(network, networkCapabilities)
      val unmetered = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
      Log.i(tag, "Network capabilities changed is unmetered = $unmetered")
      isUnmeteredNetwork = unmetered
      clientEventEmitter?.onNetworkMeteredChanged(unmetered)
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
        getContext().getString(R.string.action_jump_backward),
        R.drawable.exo_icon_rewind
      ).build()
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
        getContext().getString(R.string.action_jump_forward),
        R.drawable.exo_icon_fastforward
      ).build()
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
        getContext().getString(R.string.action_skip_forward),
        R.drawable.skip_next_24
      ).build()
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
        getContext().getString(R.string.action_skip_backward),
        R.drawable.skip_previous_24
      ).build()
    }
  }
}

