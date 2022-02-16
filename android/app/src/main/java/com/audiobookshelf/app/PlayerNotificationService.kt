package com.audiobookshelf.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.anggrayudi.storage.file.isExternalStorageDocument
import com.getcapacitor.Bridge
import com.getcapacitor.JSObject
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.cast.CastPlayer
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.*
import com.google.android.gms.cast.*
import com.google.android.gms.cast.framework.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.*
import kotlin.concurrent.schedule

const val SLEEP_TIMER_WAKE_UP_EXPIRATION = 120000L // 2m

class PlayerNotificationService : MediaBrowserServiceCompat()  {

  companion object {
    var isStarted = false
  }

  interface MyCustomObjectListener {
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: JSObject)
    fun onPrepare(audiobookId: String, playWhenReady: Boolean)
    fun onSleepTimerEnded(currentPosition: Long)
    fun onSleepTimerSet(sleepTimeRemaining: Int)
  }

  private val tag = "PlayerService"
  private val binder = LocalBinder()

  var listener:MyCustomObjectListener? = null

  private lateinit var ctx:Context
  private lateinit var mediaSessionConnector: MediaSessionConnector
  private lateinit var playerNotificationManager: PlayerNotificationManager
  private lateinit var mediaSession: MediaSessionCompat
  private lateinit var transportControls:MediaControllerCompat.TransportControls
  private lateinit var audiobookManager:AudiobookManager

  lateinit var mPlayer: SimpleExoPlayer
  lateinit var currentPlayer:Player
  var castPlayer:CastPlayer? = null

  lateinit var sleepTimerManager:SleepTimerManager
  lateinit var castManager:CastManager
  lateinit var audiobookProgressSyncer:AudiobookProgressSyncer

  private var notificationId = 10;
  private var channelId = "audiobookshelf_channel"
  private var channelName = "Audiobookshelf Channel"

  private var currentAudiobookStreamData:AudiobookStreamData? = null

  private var mediaButtonClickCount: Int = 0
  var mediaButtonClickTimeout: Long = 1000  //ms
  var seekAmount: Long = 20000   //ms

  private var lastPauseTime: Long = 0   //ms
  private var onSeekBack: Boolean = false

  var isAndroidAuto = false
  var webviewBridge:Bridge? = null

  // The following are used for the shake detection
  private var isShakeSensorRegistered:Boolean = false
  private var mSensorManager: SensorManager? = null
  private var mAccelerometer: Sensor? = null
  private var mShakeDetector: ShakeDetector? = null
  private var shakeSensorUnregisterTask:TimerTask? = null

  fun setCustomObjectListener(mylistener: MyCustomObjectListener) {
    listener = mylistener
  }
  fun setBridge(bridge: Bridge) {
    webviewBridge = bridge
  }
  fun getIsWebviewOpen():Boolean {
    return webviewBridge?.app?.isActive == true
  }

   /*
      Service related stuff
   */
  override fun onBind(intent: Intent): IBinder? {
    Log.d(tag, "onBind")

     // Android Auto Media Browser Service
     if (SERVICE_INTERFACE == intent.action) {
       Log.d(tag, "Is Media Browser Service")
       return super.onBind(intent);
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
    Log.d(tag, "onStartCommand $startId")
    isStarted = true

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

  private fun playLocal(local: LocalMediaManager.LocalAudio, playWhenReady: Boolean) {
    var asd = audiobookManager.initLocalPlay(local)
    asd.playWhenReady = playWhenReady
    initPlayer(asd)
  }

  private fun playFirstLocal(playWhenReady: Boolean) {
    var localAudio = audiobookManager.getFirstLocal()
    if (localAudio != null) {
      playLocal(localAudio, playWhenReady)
    }
  }

  private fun playAudiobookFromMediaBrowser(audiobook: Audiobook, playWhenReady: Boolean) {
    if (!audiobook.isDownloaded) {
      var streamListener = object : AudiobookManager.OnStreamData {
        override fun onStreamReady(asd: AudiobookStreamData) {
          Log.d(tag, "Stream Ready ${asd.playlistUrl}")
          asd.playWhenReady = playWhenReady
          initPlayer(asd)
        }
      }
      audiobookManager.openStream(audiobook, streamListener)
    } else {
      var asd = audiobookManager.initDownloadPlay(audiobook)
      asd.playWhenReady = playWhenReady
      initPlayer(asd)
    }
  }

  private fun playFirstAudiobook(playWhenReady: Boolean) {
    var firstAudiobook = audiobookManager.getFirstAudiobook()
    if (firstAudiobook != null) {
      playAudiobookFromMediaBrowser(firstAudiobook, playWhenReady)
    } else {
      playFirstLocal(playWhenReady)
    }
  }

  private fun openFromMediaId(mediaId: String, playWhenReady: Boolean) {
    var audiobook = audiobookManager.audiobooks.find { it.id == mediaId }
    if (audiobook == null) {
      var localAudio = audiobookManager.localMediaManager.localAudioFiles.find { it.id == mediaId }
      if (localAudio != null) {
        playLocal(localAudio, playWhenReady)
        return
      }

      Log.e(tag, "Audiobook NOT FOUND")
      return
    }

    playAudiobookFromMediaBrowser(audiobook, playWhenReady)
  }

  private fun openFromSearch(query: String?, playWhenReady: Boolean) {
    if (query?.isNullOrEmpty() == true) {
      Log.d(tag, "Empty search query play first audiobook")
      playFirstAudiobook(playWhenReady)
      return
    }

    var audiobook = audiobookManager.searchForAudiobook(query)
    if (audiobook == null) {
      Log.e(tag, "No Audiobook found for search $query")
      pause()
      return
    }

    playAudiobookFromMediaBrowser(audiobook, playWhenReady)
  }

  // detach player
  override fun onDestroy() {
    playerNotificationManager.setPlayer(null)
    mPlayer.release()
    mediaSession.release()
    audiobookProgressSyncer.reset()
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
    var customLoadControl:LoadControl = DefaultLoadControl.Builder().setBufferDurationsMs(
      1000 * 20, // 20s min buffer
      1000 * 45, // 45s max buffer
      1000 * 5, // 5s playback start
      1000 * 20 // 20s playback rebuffer
    ).build()

    var simpleExoPlayerBuilder = SimpleExoPlayer.Builder(this)
    simpleExoPlayerBuilder.setLoadControl(customLoadControl)
    simpleExoPlayerBuilder.setSeekBackIncrementMs(10000)
    simpleExoPlayerBuilder.setSeekForwardIncrementMs(10000)
    mPlayer = simpleExoPlayerBuilder.build()
    mPlayer.setHandleAudioBecomingNoisy(true)
    mPlayer.addListener(getPlayerListener())
    var audioAttributes:AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_SPEECH).build()
    mPlayer.setAudioAttributes(audioAttributes, true)

    currentPlayer = mPlayer

    var client: OkHttpClient = OkHttpClient()

    // Initialize sleep timer
    sleepTimerManager = SleepTimerManager(this)

    // Initialize Cast Manager
    castManager = CastManager(this)

    // Initialize Audiobook Progress Syncer (Only used for android auto when webview is not open)
    audiobookProgressSyncer = AudiobookProgressSyncer(this, client)

    // Initialize shake sensor
    Log.d(tag, "onCreate Register sensor listener ${mAccelerometer?.isWakeUpSensor}")
    initSensor()

    // Initialize audiobook manager
    audiobookManager = AudiobookManager(ctx, client)
    audiobookManager.init()

    channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createNotificationChannel(channelId, channelName)
    } else ""

    val sessionActivityPendingIntent =
      packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
        PendingIntent.getActivity(this, 0, sessionIntent, 0)
      }

    mediaSession = MediaSessionCompat(this, tag)
      .apply {
        setSessionActivity(sessionActivityPendingIntent)
        isActive = true
      }


    Log.d(tag, "Media Session Set")

    val mediaController = MediaControllerCompat(ctx, mediaSession.sessionToken)

    // This is for Media Browser
    sessionToken = mediaSession.sessionToken

    val builder = PlayerNotificationManager.Builder(
      ctx,
      notificationId,
      channelId)

    builder.setMediaDescriptionAdapter(AbMediaDescriptionAdapter(mediaController, this))

    builder.setNotificationListener(object : PlayerNotificationManager.NotificationListener {
      override fun onNotificationPosted(
        notificationId: Int,
        notification: Notification,
        onGoing: Boolean) {

        // Start foreground service
        Log.d(tag, "Notification Posted $notificationId - Start Foreground | $notification")
        startForeground(notificationId, notification)
      }

      override fun onNotificationCancelled(
        notificationId: Int,
        dismissedByUser: Boolean
      ) {
        if (dismissedByUser) {
          Log.d(tag, "onNotificationCancelled dismissed by user")
          stopSelf()
        } else {
          Log.d(tag, "onNotificationCancelled not dismissed by user")
        }
      }
    })

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

    // Color is set based on the art - cannot override
//    playerNotificationManager.setColor(Color.RED)
//    playerNotificationManager.setColorized(true)

    // Icon needs to be black and white
//    playerNotificationManager.setSmallIcon(R.drawable.icon_32)

    mediaSessionConnector = MediaSessionConnector(mediaSession)
    val queueNavigator: TimelineQueueNavigator = object : TimelineQueueNavigator(mediaSession) {
      override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
        var builder = MediaDescriptionCompat.Builder()
          .setMediaId(currentAudiobookStreamData!!.id)
          .setTitle(currentAudiobookStreamData!!.title)
          .setSubtitle(currentAudiobookStreamData!!.author)
          .setMediaUri(currentAudiobookStreamData!!.playlistUri)
          .setIconUri(currentAudiobookStreamData!!.coverUri)
        return builder.build()
      }
    }

    val myPlaybackPreparer:MediaSessionConnector.PlaybackPreparer = object :MediaSessionConnector.PlaybackPreparer {
      override fun onCommand(player: Player, controlDispatcher: ControlDispatcher, command: String, extras: Bundle?, cb: ResultReceiver?): Boolean {
        Log.d(tag, "ON COMMAND $command")
        return false
      }

      override fun getSupportedPrepareActions(): Long {
        return PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
          PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
          PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
          PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
      }

      override fun onPrepare(playWhenReady: Boolean) {
        Log.d(tag, "ON PREPARE $playWhenReady")
        playFirstAudiobook(playWhenReady)
      }

      override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
        Log.d(tag, "ON PREPARE FROM MEDIA ID $mediaId $playWhenReady")
        openFromMediaId(mediaId, playWhenReady)
      }

      override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
        Log.d(tag, "ON PREPARE FROM SEARCH $query")
        openFromSearch(query, playWhenReady)
      }

      override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {
        Log.d(tag, "ON PREPARE FROM URI $uri")
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
    mediaSessionConnector.setPlaybackPreparer(myPlaybackPreparer)
    mediaSessionConnector.setPlayer(mPlayer)

    //attach player to playerNotificationManager
    playerNotificationManager.setPlayer(mPlayer)

    mediaSession.setCallback(object : MediaSessionCompat.Callback() {
      override fun onPrepare() {
        Log.d(tag, "ON PREPARE MEDIA SESSION COMPAT")
        playFirstAudiobook(true)
      }

      override fun onPlay() {
        Log.d(tag, "ON PLAY MEDIA SESSION COMPAT")
        play()
      }

      override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
        Log.d(tag, "ON PREPARE FROM SEARCH $query")
        super.onPrepareFromSearch(query, extras)
      }

      override fun onPlayFromSearch(query: String?, extras: Bundle?) {
        Log.d(tag, "ON PLAY FROM SEARCH $query")
        openFromSearch(query, true)
      }

      override fun onPause() {
        Log.d(tag, "ON PAUSE MEDIA SESSION COMPAT")
        pause()
      }

      override fun onStop() {
        pause()
      }

      override fun onSkipToPrevious() {
        seekBackward(seekAmount)
      }

      override fun onSkipToNext() {
        seekForward(seekAmount)
      }

      override fun onFastForward() {
        seekForward(seekAmount)
      }

      override fun onRewind() {
        seekForward(seekAmount)
      }

      override fun onSeekTo(pos: Long) {
        seekPlayer(pos)
      }

      override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
        Log.d(tag, "ON PLAY FROM MEDIA ID $mediaId")
        if (mediaId.isNullOrEmpty()) {
          playFirstAudiobook(true)
          return
        }
        openFromMediaId(mediaId, true)
      }

      override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
        return handleCallMediaButton(mediaButtonEvent)
      }
    })
  }

  fun handleCallMediaButton(intent: Intent): Boolean {
    if(Intent.ACTION_MEDIA_BUTTON == intent.getAction()) {
      var keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
      if (keyEvent?.getAction() == KeyEvent.ACTION_UP) {
        when (keyEvent?.getKeyCode()) {
          KeyEvent.KEYCODE_HEADSETHOOK -> {
            if (0 == mediaButtonClickCount) {
              if (mPlayer.isPlaying)
                pause()
              else
                play()
            }
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_PLAY -> {
            if (0 == mediaButtonClickCount) {
              play()
              sleepTimerManager.checkShouldExtendSleepTimer()
            }
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            if (0 == mediaButtonClickCount) pause()
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_NEXT -> {
            seekForward(seekAmount)
          }
          KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
            seekBackward(seekAmount)
          }
          KeyEvent.KEYCODE_MEDIA_STOP -> {
            terminateStream()
          }
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            if (mPlayer.isPlaying) {
              if (0 == mediaButtonClickCount) pause()
              handleMediaButtonClickCount()
            } else {
              if (0 == mediaButtonClickCount) {
                play()
                sleepTimerManager.checkShouldExtendSleepTimer()
              }
              handleMediaButtonClickCount()
            }
          }
          else -> {
            Log.d(tag, "KeyCode:${keyEvent?.getKeyCode()}")
            return false
          }
        }
      }
    }
    return true
  }

  fun handleMediaButtonClickCount() {
    mediaButtonClickCount++
    if (1 == mediaButtonClickCount) {
      Timer().schedule(mediaButtonClickTimeout) {
        mediaBtnHandler.sendEmptyMessage(mediaButtonClickCount)
        mediaButtonClickCount = 0
      }
    }
  }

  private val mediaBtnHandler : Handler = @SuppressLint("HandlerLeak")
  object : Handler(){
    override fun handleMessage(msg: Message) {
      super.handleMessage(msg)
      if (2 == msg.what) {
        seekBackward(seekAmount)
        play()
      }
      else if (msg.what >= 3) {
        seekForward(seekAmount)
        play()
      }
    }
  }

  fun getPlayerListener(): Player.Listener {
    return object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        error.message?.let { Log.e(tag, it) }
        error.localizedMessage?.let { Log.e(tag, it) }
      }

      override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
          Log.d(tag, "EVENT_POSITION_DISCONTINUITY")
        }

        if (events.contains(Player.EVENT_IS_LOADING_CHANGED)) {
          Log.d(tag, "EVENT_IS_LOADING_CHANGED : " + mPlayer.isLoading.toString())
        }

        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
          if (currentPlayer.playbackState == Player.STATE_READY) {
            Log.d(tag, "STATE_READY : " + mPlayer.duration.toString())

            currentAudiobookStreamData!!.hasPlayerLoaded = true
            if (lastPauseTime == 0L) {
              sendClientMetadata("ready_no_sync")
              lastPauseTime = -1;
            } else sendClientMetadata("ready")
          }
          if (currentPlayer.playbackState == Player.STATE_BUFFERING) {
            Log.d(tag, "STATE_BUFFERING : " + mPlayer.currentPosition.toString())
            if (lastPauseTime == 0L) sendClientMetadata("buffering_no_sync")
            else sendClientMetadata("buffering")
          }
          if (currentPlayer.playbackState == Player.STATE_ENDED) {
            Log.d(tag, "STATE_ENDED")
            sendClientMetadata("ended")
          }
          if (currentPlayer.playbackState == Player.STATE_IDLE) {
            Log.d(tag, "STATE_IDLE")
            sendClientMetadata("idle")
          }
        }

        if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
          Log.d(tag, "EVENT_MEDIA_METADATA_CHANGED")
        }
        if (events.contains(Player.EVENT_PLAYLIST_METADATA_CHANGED)) {
          Log.d(tag, "EVENT_PLAYLIST_METADATA_CHANGED")
        }
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
          Log.d(tag, "EVENT IS PLAYING CHANGED")

          if (player.isPlaying) {
            if (lastPauseTime > 0) {
              if (onSeekBack) onSeekBack = false
              else {
                var backTime = calcPauseSeekBackTime()
                if (backTime > 0) {
                  if (backTime >= mPlayer.currentPosition) backTime = mPlayer.currentPosition - 500
                  Log.d(tag, "SeekBackTime $backTime")
                  onSeekBack = true
                  seekBackward(backTime)
                }
              }
            }
          } else lastPauseTime = System.currentTimeMillis()

          // If app is only running in android auto then webview will not be open
          //  so progress needs to be synced natively
          Log.d(tag, "Playing ${getCurrentBookTitle()} | ${currentPlayer.mediaMetadata.title} | ${currentPlayer.mediaMetadata.displayTitle}")
          if (player.isPlaying) {
            audiobookProgressSyncer.start()
          } else {
            audiobookProgressSyncer.stop()
          }

          listener?.onPlayingUpdate(player.isPlaying)
        }
      }
    }
  }


  /*
    User callable methods
  */
  fun initPlayer(audiobookStreamData: AudiobookStreamData) {
    currentAudiobookStreamData = audiobookStreamData

    Log.d(tag, "Init Player Audiobook ${currentAudiobookStreamData!!.playlistUrl} | ${currentAudiobookStreamData!!.title} | ${currentAudiobookStreamData!!.author}")

    if (mPlayer.isPlaying) {
      Log.d(tag, "Init Player audiobook already playing")
    }

    // Issue with onenote plus crashing when using local cover art. https://github.com/advplyr/audiobookshelf-app/issues/35
    // Same issue with sony xperia https://github.com/advplyr/audiobookshelf-app/issues/94
    if (currentAudiobookStreamData?.coverUri != null && currentAudiobookStreamData?.isLocal == true) {
      var deviceName = Build.DEVICE
      var deviceMan = Build.MANUFACTURER
      var deviceModel = Build.MODEL
      Log.d(tag, "Checking device $deviceName | Model $deviceModel | Manufacturer $deviceMan")
      if (deviceMan.toLowerCase().contains("oneplus") || deviceName.toLowerCase().contains("oneplus")) {
        Log.d(tag, "Detected OnePlus device - removing local cover")
        currentAudiobookStreamData?.clearCover()
      } else if (deviceName.toLowerCase().contains("xperia") || deviceModel.toLowerCase().contains("xperia")) {
        Log.d(tag, "Detected Sony Xperia device - removing local cover")
        currentAudiobookStreamData?.clearCover()
      }
    }

    var metadata = currentAudiobookStreamData!!.getMediaMetadataCompat()
    mediaSession.setMetadata(metadata)

    var mediaUri:Uri = currentAudiobookStreamData!!.getMediaUri()
    var mimeType:String = currentAudiobookStreamData!!.getMimeType()

    var mediaMetadata = currentAudiobookStreamData!!.getMediaMetadata()
    var mediaItem = MediaItem.Builder().setUri(mediaUri).setMediaMetadata(mediaMetadata).setMimeType(mimeType).build()

    if (mPlayer == currentPlayer) {
      var mediaSource:MediaSource

      if (currentAudiobookStreamData!!.isLocal) {
        Log.d(tag, "Playing Local File")
        var dataSourceFactory = DefaultDataSourceFactory(ctx, channelId)
        mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
      } else {
        Log.d(tag, "Playing HLS File")
        var dataSourceFactory = DefaultHttpDataSource.Factory()
        dataSourceFactory.setUserAgent(channelId)
        dataSourceFactory.setDefaultRequestProperties(hashMapOf("Authorization" to "Bearer ${currentAudiobookStreamData!!.token}"))
        mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
      }
      mPlayer.setMediaSource(mediaSource, currentAudiobookStreamData!!.startTime)
    } else if (castPlayer != null) {
      var mediaQueue = currentAudiobookStreamData!!.getCastQueue()
      // TODO: Start position will need to be adjusted if using multi-track queue
      castPlayer?.setMediaItems(mediaQueue, 0, 0)
    }

    currentPlayer.prepare()
    currentPlayer.playWhenReady = currentAudiobookStreamData!!.playWhenReady
    currentPlayer.setPlaybackSpeed(audiobookStreamData.playbackSpeed)

    lastPauseTime = 0
  }

  fun switchToPlayer(useCastPlayer: Boolean) {
    currentPlayer = if (useCastPlayer) {
      Log.d(tag, "switchToPlayer: Using Cast Player " + castPlayer?.deviceInfo)
      mediaSessionConnector.setPlayer(castPlayer)
      castPlayer as CastPlayer
    } else {
      Log.d(tag, "switchToPlayer: Using ExoPlayer")
      mediaSessionConnector.setPlayer(mPlayer)
      mPlayer
    }
    if (currentAudiobookStreamData != null) {
      Log.d(tag, "switchToPlayer: Initing current ab stream data")
      initPlayer(currentAudiobookStreamData!!)
    }
  }

  fun getCurrentTime() : Long {
    return currentPlayer.currentPosition
  }

  fun getBufferedTime() : Long {
    return currentPlayer.bufferedPosition
  }

  fun getTheLastPauseTime() : Long {
    return lastPauseTime
  }

  fun getDuration() : Long {
    return currentPlayer.duration
  }

  fun getCurrentBookTitle() : String? {
    return currentAudiobookStreamData?.title
  }

  fun getCurrentBookIsLocal() : Boolean {
    return currentAudiobookStreamData?.isLocal == true
  }

  fun getCurrentBookId() : String? {
    return currentAudiobookStreamData?.audiobookId
  }

  fun getCurrentStreamId() : String? {
    return currentAudiobookStreamData?.id
  }

  // The duration stored on the audiobook
  fun getAudiobookDuration() : Long {
    if (currentAudiobookStreamData == null) return 0L
    return currentAudiobookStreamData!!.duration
  }

  fun getServerUrl(): String {
    return audiobookManager.serverUrl
  }

  fun getUserToken() : String {
    return audiobookManager.token
  }

  fun calcPauseSeekBackTime() : Long {
    if (lastPauseTime <= 0) return 0
    var time: Long = System.currentTimeMillis() - lastPauseTime
    var seekback: Long = 0
    if (time < 60000) seekback = 0
    else if (time < 120000) seekback = 10000
    else if (time < 300000) seekback = 15000
    else if (time < 1800000) seekback = 20000
    else if (time < 3600000) seekback = 25000
    else seekback = 29500
    return seekback
  }

  fun getPlayStatus() : Boolean {
    return mPlayer.isPlaying
  }

  fun getCurrentAudiobookId() : String {
    return currentAudiobookStreamData?.id.toString()
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

  fun seekPlayer(time: Long) {
    currentPlayer.seekTo(time)
  }

  fun seekForward(amount: Long) {
    currentPlayer.seekTo(mPlayer.currentPosition + amount)
  }

  fun seekBackward(amount: Long) {
    currentPlayer.seekTo(mPlayer.currentPosition - amount)
  }

  fun setPlaybackSpeed(speed: Float) {
    currentPlayer.setPlaybackSpeed(speed)
  }

  fun terminateStream() {
    if (currentPlayer.playbackState == Player.STATE_READY) {
      currentPlayer.clearMediaItems()
    }
    currentAudiobookStreamData?.id = ""
    lastPauseTime = 0
  }

  fun sendClientMetadata(stateName: String) {
    var metadata = JSObject()
    var duration = mPlayer.duration
    if (duration < 0) duration = 0
    metadata.put("duration", duration)
    metadata.put("currentTime", mPlayer.currentPosition)
    metadata.put("stateName", stateName)
    listener?.onMetadata(metadata)
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
      // Flag is used to enable syncing progress natively (normally syncing is handled in webview)
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

    var flag = if (parentMediaId == AUTO_MEDIA_ROOT) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE

    if (!audiobookManager.hasLoaded) {
      result.detach()
      audiobookManager.load()
      audiobookManager.loadAudiobooks() {
        audiobookManager.isLoading = false

        Log.d(tag, "LOADED AUDIOBOOKS")

        var audiobooks:List<MediaMetadataCompat> = audiobookManager.getAudiobooksMediaMetadata()
        var downloadedBooks:List<MediaMetadataCompat> = audiobookManager.getDownloadedAudiobooksMediaMetadata()

        browseTree = BrowseTree(this, audiobookManager.audiobooksInProgress, audiobooks, downloadedBooks)
        val children = browseTree[parentMediaId]?.map { item ->
          MediaBrowserCompat.MediaItem(item.description, flag)
        }
        result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
      }
      return
    } else if (audiobookManager.isLoading) {
      Log.d(tag, "AUDIOBOOKS LOADING")
      result.detach()
      return
    }

    val children = browseTree[parentMediaId]?.map { item ->
      MediaBrowserCompat.MediaItem(item.description, flag)
    }
    if (children != null) {
      Log.d(tag, "BROWSE TREE $parentMediaId CHILDREN ${children.size}")
    }
    result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)

    // TODO: For using sub menus. Check if this is the root menu:
    if (AUTO_MEDIA_ROOT == parentMediaId) {
      // build the MediaItem objects for the top level,
      // and put them in the mediaItems list
    } else {
      // examine the passed parentMediaId to see which submenu we're at,
      // and put the children of that menu in the mediaItems list
    }
  }

  override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
    val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

    if (!audiobookManager.hasLoaded) {
      result.detach()
      audiobookManager.load()
      audiobookManager.loadAudiobooks() {
        audiobookManager.isLoading = false

        Log.d(tag, "LOADED AUDIOBOOKS")
        var audiobooks:List<MediaMetadataCompat> = audiobookManager.getAudiobooksMediaMetadata()
        var downloadedBooks:List<MediaMetadataCompat> = audiobookManager.getDownloadedAudiobooksMediaMetadata()

        browseTree = BrowseTree(this, audiobookManager.audiobooksInProgress, audiobooks, downloadedBooks)
        val children = browseTree[ALL_ROOT]?.map { item ->
          MediaBrowserCompat.MediaItem(item.description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }
        if (children != null) {
          Log.d(tag, "BROWSE TREE CHILDREN ${children.size}")
        }
        result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
      }
      return
    } else if (audiobookManager.isLoading) {
      Log.d(tag, "AUDIOBOOKS LOADING")
      result.detach()
      return
    }

    if (audiobookManager.audiobooks.size == 0) {
      Log.d(tag, "AudiobookManager: Sending no items")
      result.sendResult(mediaItems)
      return
    }

    val children = browseTree[ALL_ROOT]?.map { item ->
      MediaBrowserCompat.MediaItem(item.description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }
    if (children != null) {
      Log.d(tag, "NO CHILDREN ON SEARCH ${children.size}")
    }
    result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
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
    var success = mSensorManager!!.registerListener(
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

