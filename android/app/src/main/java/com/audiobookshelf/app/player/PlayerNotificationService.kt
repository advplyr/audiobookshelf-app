package com.audiobookshelf.app.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.server.ApiHandler
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.*
import java.util.*
import kotlin.concurrent.schedule

const val SLEEP_TIMER_WAKE_UP_EXPIRATION = 120000L // 2m

class PlayerNotificationService : MediaBrowserServiceCompat()  {

  companion object {
    var isStarted = false
  }

  interface ClientEventEmitter {
    fun onPlaybackSession(playbackSession:PlaybackSession)
    fun onPlaybackClosed()
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: PlaybackMetadata)
    fun onPrepare(audiobookId: String, playWhenReady: Boolean)
    fun onSleepTimerEnded(currentPosition: Long)
    fun onSleepTimerSet(sleepTimeRemaining: Int)
    fun onLocalMediaProgressUpdate(localMediaProgress: LocalMediaProgress)
    fun onPlaybackFailed(errorMessage:String)
    fun onMediaPlayerChanged(mediaPlayer:String)
  }

  private val tag = "PlayerService"
  private val binder = LocalBinder()

  var clientEventEmitter:ClientEventEmitter? = null

  private lateinit var ctx:Context
  private lateinit var mediaSessionConnector: MediaSessionConnector
  private lateinit var playerNotificationManager: PlayerNotificationManager
  private lateinit var mediaSession: MediaSessionCompat
  private lateinit var transportControls:MediaControllerCompat.TransportControls

  lateinit var mediaManager: MediaManager
  private lateinit var apiHandler: ApiHandler

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

  fun stopService(context: Context) {
    val stopIntent = Intent(context, PlayerNotificationService::class.java)
    context.stopService(stopIntent)
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
    super.onCreate()
    ctx = this

    // Initialize player
    val customLoadControl:LoadControl = DefaultLoadControl.Builder().setBufferDurationsMs(
      1000 * 20, // 20s min buffer
      1000 * 45, // 45s max buffer
      1000 * 5, // 5s playback start
      1000 * 20 // 20s playback rebuffer
    ).build()

    mPlayer = ExoPlayer.Builder(this)
      .setLoadControl(customLoadControl)
      .setSeekBackIncrementMs(10000)
      .setSeekForwardIncrementMs(10000)
      .build()
    mPlayer.setHandleAudioBecomingNoisy(true)
    mPlayer.addListener(PlayerListener(this))
    val audioAttributes:AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_SPEECH).build()
    mPlayer.setAudioAttributes(audioAttributes, true)

    currentPlayer = mPlayer

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
    playerNotificationManager.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    playerNotificationManager.setPriority(NotificationCompat.PRIORITY_MAX)
    playerNotificationManager.setUseFastForwardActionInCompactView(true)
    playerNotificationManager.setUseRewindActionInCompactView(true)

    // Unknown action
    playerNotificationManager.setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)

    transportControls = mediaController.transportControls

    mediaSessionConnector = MediaSessionConnector(mediaSession)
    val queueNavigator: TimelineQueueNavigator = object : TimelineQueueNavigator(mediaSession) {
      override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
        val coverUri = currentPlaybackSession!!.getCoverUri()

        // Fix for local images crashing on Android 10 for specific devices
        // https://stackoverflow.com/questions/64186578/android-11-mediastyle-notification-crash/64232958#64232958
        ctx.grantUriPermission(
          "com.android.systemui",
          coverUri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

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
    mediaSessionConnector.setPlayer(mPlayer)

    //attach player to playerNotificationManager
    playerNotificationManager.setPlayer(mPlayer)
    mediaSession.setCallback(MediaSessionCallback(this))
  }

  /*
    User callable methods
  */
  fun preparePlayer(playbackSession: PlaybackSession, playWhenReady:Boolean, playbackRate:Float?) {
    val playbackRateToUse = playbackRate ?: initialPlaybackRate ?: 1f
    initialPlaybackRate = playbackRate

    playbackSession.mediaPlayer = getMediaPlayer()

    if (playbackSession.mediaPlayer == "cast-player" && playbackSession.isLocal) {
      Log.w(tag, "Cannot cast local media item - switching player")
      currentPlaybackSession = null
      switchToPlayer(false)
      playbackSession.mediaPlayer = getMediaPlayer()
    }

    if (playbackSession.mediaPlayer == "cast-player") {
      // If cast-player is the first player to be used
      mediaSessionConnector.setPlayer(castPlayer)
      playerNotificationManager.setPlayer(castPlayer)
    }

    currentPlaybackSession = playbackSession
    Log.d(tag, "Set CurrentPlaybackSession MediaPlayer ${currentPlaybackSession?.mediaPlayer}")

    clientEventEmitter?.onPlaybackSession(playbackSession)

    val metadata = playbackSession.getMediaMetadataCompat()
    mediaSession.setMetadata(metadata)
    val mediaItems = playbackSession.getMediaItems()

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
        val mediaPlayer = getMediaPlayer()
        Log.d(tag, "Fallback to transcode $mediaPlayer")

        val libraryItemId = playbackSession.libraryItemId ?: "" // Must be true since direct play
        val episodeId = playbackSession.episodeId
        apiHandler.playLibraryItem(libraryItemId, episodeId, true, mediaPlayer) {
          Handler(Looper.getMainLooper()).post {
            preparePlayer(it, true, null)
          }
        }
      } else {
        clientEventEmitter?.onPlaybackFailed(errorMessage)
        closePlayback()
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

    if (currentPlaybackSession == null) {
      Log.d(tag, "switchToPlayer: No Current playback session")
    }

    currentPlaybackSession?.let {
      Log.d(tag, "switchToPlayer: Preparing current playback session ${it.displayTitle}")
      if (wasPlaying) { // media is paused when switching players
        clientEventEmitter?.onPlayingUpdate(false)
      }
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

  fun getCurrentBookTitle() : String? {
    return currentPlaybackSession?.displayTitle
  }

  fun getCurrentPlaybackSessionCopy() :PlaybackSession? {
    return currentPlaybackSession?.clone()
  }

  fun getCurrentPlaybackSessionId() :String? {
    return currentPlaybackSession?.id
  }

  fun play() {
    if (currentPlayer.isPlaying) {
      Log.d(tag, "Already playing")
      return
    }
    currentPlayer.volume = 1F
    if (currentPlayer == castPlayer) {
      Log.d(tag, "CAST Player set on play ${currentPlayer.isLoading} || ${currentPlayer.duration} | ${currentPlayer.currentPosition}")
    }

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

  fun seekForward(amount: Long) {
   seekPlayer(getCurrentTime() + amount)
//    currentPlayer.seekTo(currentPlayer.currentPosition + amount)
  }

  fun seekBackward(amount: Long) {
    seekPlayer(getCurrentTime() - amount)
//    currentPlayer.seekTo(currentPlayer.currentPosition - amount)
  }

  fun setPlaybackSpeed(speed: Float) {
    currentPlayer.setPlaybackSpeed(speed)
  }

  fun closePlayback() {
    currentPlayer.clearMediaItems()
    currentPlayer.stop()
    currentPlaybackSession = null
    clientEventEmitter?.onPlaybackClosed()
    PlayerListener.lastPauseTime = 0
  }

  fun sendClientMetadata(playerState: PlayerState) {
    val duration = currentPlaybackSession?.getTotalDuration() ?: 0.0
    clientEventEmitter?.onMetadata(PlaybackMetadata(duration, getCurrentTimeSeconds(), playerState))
  }

  fun getMediaPlayer():String {
    return if(currentPlayer == castPlayer) "cast-player" else "exo-player"
  }

  fun getContext():Context {
    return ctx
  }

  //
  // MEDIA BROWSER STUFF (ANDROID AUTO)
  //
  private val ANDROID_AUTO_PKG_NAME = "com.google.android.projection.gearhead"
  private val ANDROID_AUTO_SIMULATOR_PKG_NAME = "com.google.android.autosimulator"
  private val ANDROID_WEARABLE_PKG_NAME = "com.google.android.wearable.app"
  private val ANDROID_GSEARCH_PKG_NAME = "com.google.android.googlequicksearchbox"
  private val ANDROID_AUTOMOTIVE_PKG_NAME = "com.google.android.carassistant"
  private val VALID_MEDIA_BROWSERS = mutableListOf<String>(ANDROID_AUTO_PKG_NAME, ANDROID_AUTO_SIMULATOR_PKG_NAME, ANDROID_WEARABLE_PKG_NAME, ANDROID_GSEARCH_PKG_NAME, ANDROID_AUTOMOTIVE_PKG_NAME)

  private val AUTO_MEDIA_ROOT = "/"
  private val ALL_ROOT = "__ALL__"
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
      if (!isStarted) {
        Log.d(tag, "AA Not yet started")
        mediaManager.initializeAndroidAuto()
        isStarted = true
      }

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

    val flag = if (parentMediaId == AUTO_MEDIA_ROOT) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE

    result.detach()

    mediaManager.loadAndroidAutoItems("main") { libraryCategories ->
      browseTree = BrowseTree(this, libraryCategories)
      val children = browseTree[parentMediaId]?.map { item ->
        MediaBrowserCompat.MediaItem(item.description, flag)
      }
      result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
    }

    // TODO: For using sub menus. Check if this is the root menu:
//    if (AUTO_MEDIA_ROOT == parentMediaId) {
      // build the MediaItem objects for the top level,
      // and put them in the mediaItems list
//    } else {
      // examine the passed parentMediaId to see which submenu we're at,
      // and put the children of that menu in the mediaItems list
//    }
  }

  override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
    result.detach()
    mediaManager.loadAndroidAutoItems("main") { libraryCategories ->
      browseTree = BrowseTree(this, libraryCategories)
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
}

