package com.audiobookshelf.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.cast.CastPlayer
import com.google.android.exoplayer2.ext.cast.SessionAvailabilityListener
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.*
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.gms.cast.Cast.MessageReceivedCallback
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.*
import kotlin.concurrent.schedule


const val NOTIFICATION_LARGE_ICON_SIZE = 144 // px

class PlayerNotificationService : MediaBrowserServiceCompat()  {

  companion object {
    var isStarted = false
  }

  interface MyCustomObjectListener {
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: JSObject)
    fun onPrepare(audiobookId: String, playWhenReady: Boolean)
    fun onSleepTimerEnded(currentPosition: Long)
  }


  private val tag = "PlayerService"

  private var listener:MyCustomObjectListener? = null

  private lateinit var ctx:Context
  private lateinit var mPlayer: SimpleExoPlayer
  private lateinit var currentPlayer:Player
  private var castPlayer:CastPlayer? = null
  private lateinit var mediaSessionConnector: MediaSessionConnector
  private lateinit var playerNotificationManager: PlayerNotificationManager
  private lateinit var mediaSession: MediaSessionCompat
  private lateinit var transportControls:MediaControllerCompat.TransportControls

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
  private val binder = LocalBinder()
  private val glideOptions = RequestOptions()
    .fallback(R.drawable.icon)
    .diskCacheStrategy(DiskCacheStrategy.DATA)

  private var notificationId = 10;
  private var channelId = "audiobookshelf_channel"
  private var channelName = "Audiobookshelf Channel"

  private var currentAudiobookStreamData:AudiobookStreamData? = null

  private var mediaButtonClickCount: Int = 0
  var mediaButtonClickTimeout: Long = 1000  //ms
  var seekAmount: Long = 20000   //ms

  private var lastPauseTime: Long = 0   //ms
  private var onSeekBack: Boolean = false

  private var sleepTimerTask:TimerTask? = null
  private var sleepChapterTime:Long = 0L

  private lateinit var audiobookManager:AudiobookManager
  private var newConnectionListener:SessionListener? = null
  private var mainActivity:Activity? = null

  fun setCustomObjectListener(mylistener: MyCustomObjectListener) {
    listener = mylistener
  }

   /*
      Service related stuff
   */
  override fun onBind(intent: Intent): IBinder? {
    Log.d(tag, "onBind")

     // Android Auto Media Browser Service
     if (SERVICE_INTERFACE.equals(intent.getAction())) {
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
      channelName, NotificationManager.IMPORTANCE_HIGH)
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
    var client: OkHttpClient = OkHttpClient()
    audiobookManager = AudiobookManager(ctx, client)
    audiobookManager.init()

  channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    createNotificationChannel(channelId, channelName)
  } else ""


    var simpleExoPlayerBuilder = SimpleExoPlayer.Builder(this)
    simpleExoPlayerBuilder.setSeekBackIncrementMs(10000)
    simpleExoPlayerBuilder.setSeekForwardIncrementMs(10000)
    mPlayer = simpleExoPlayerBuilder.build()
    currentPlayer = mPlayer
    mPlayer.setHandleAudioBecomingNoisy(true)

    var audioAttributes:AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_SPEECH).build()
    mPlayer.setAudioAttributes(audioAttributes, true)

    setPlayerListeners()

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

    builder.setMediaDescriptionAdapter(DescriptionAdapter(mediaController))

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
        Log.d(tag, "ON PLAY MEDIA SESSION COMPAT")
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
            if (0 == mediaButtonClickCount) play()
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
              if (0 == mediaButtonClickCount) play()
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

  private inner class DescriptionAdapter(private val controller: MediaControllerCompat) :
    PlayerNotificationManager.MediaDescriptionAdapter {

    var currentIconUri: Uri? = null
    var currentBitmap: Bitmap? = null

    override fun createCurrentContentIntent(player: Player): PendingIntent? =
      controller.sessionActivity

    override fun getCurrentContentText(player: Player) = controller.metadata.description.subtitle.toString()

    override fun getCurrentContentTitle(player: Player) = controller.metadata.description.title.toString()

    override fun getCurrentLargeIcon(
      player: Player,
      callback: PlayerNotificationManager.BitmapCallback
    ): Bitmap? {
      val albumArtUri = controller.metadata.description.iconUri

      return if (currentIconUri != albumArtUri || currentBitmap == null) {
        // Cache the bitmap for the current audiobook so that successive calls to
        // `getCurrentLargeIcon` don't cause the bitmap to be recreated.
        currentIconUri = albumArtUri
        Log.d(tag, "ART $currentIconUri")
        serviceScope.launch {
          currentBitmap = albumArtUri?.let {
            resolveUriAsBitmap(it)
          }
          currentBitmap?.let { callback.onBitmap(it) }
        }
        null
      } else {
        currentBitmap
      }
    }

    private suspend fun resolveUriAsBitmap(uri: Uri): Bitmap? {
      return withContext(Dispatchers.IO) {
        // Block on downloading artwork.
       try {
         Glide.with(ctx).applyDefaultRequestOptions(glideOptions)
           .asBitmap()
           .load(uri)
           .placeholder(R.drawable.icon)
           .error(R.drawable.icon)
           .submit(NOTIFICATION_LARGE_ICON_SIZE, NOTIFICATION_LARGE_ICON_SIZE)
           .get()
         } catch (e: Exception) {
            e.printStackTrace()

           Glide.with(ctx).applyDefaultRequestOptions(glideOptions)
             .asBitmap()
             .load(Uri.parse("android.resource://com.audiobookshelf.app/" + R.drawable.icon))
             .submit(NOTIFICATION_LARGE_ICON_SIZE, NOTIFICATION_LARGE_ICON_SIZE)
             .get()
          }
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

            /*if (!currentAudiobook!!.hasPlayerLoaded && currentAudiobook!!.startTime > 0) {
              Log.d(tag, "Should seek to ${currentAudiobook!!.startTime}")
              mPlayer.seekTo(currentAudiobook!!.startTime)
            }*/

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
          listener?.onPlayingUpdate(player.isPlaying)
        }
      }
    }
  }

  private fun setPlayerListeners() {
    mPlayer.addListener(getPlayerListener())
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

    var metadataBuilder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentAudiobookStreamData!!.title)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentAudiobookStreamData!!.title)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentAudiobookStreamData!!.author)
      .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, currentAudiobookStreamData!!.author)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentAudiobookStreamData!!.author)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAudiobookStreamData!!.series)
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentAudiobookStreamData!!.id)

    if (currentAudiobookStreamData!!.cover != "") {
      metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, currentAudiobookStreamData!!.cover)
      metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, currentAudiobookStreamData!!.cover)
    }

    var metadata = metadataBuilder.build()
    mediaSession.setMetadata(metadata)

    var mediaMetadata = MediaMetadata.Builder().build()


    var mediaSource:MediaSource
    if (currentAudiobookStreamData!!.isLocal) {
      Log.d(tag, "Playing Local File")
      var mediaItem = MediaItem.Builder().setUri(currentAudiobookStreamData!!.contentUri).setMediaMetadata(mediaMetadata).build()
      var dataSourceFactory = DefaultDataSourceFactory(ctx, channelId)
      mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
    } else {
      Log.d(tag, "Playing HLS File")
      var mediaItem = MediaItem.Builder().setUri(currentAudiobookStreamData!!.playlistUri).setMediaMetadata(mediaMetadata).build()
      var dataSourceFactory = DefaultHttpDataSource.Factory()
      dataSourceFactory.setUserAgent(channelId)
      dataSourceFactory.setDefaultRequestProperties(hashMapOf("Authorization" to "Bearer ${currentAudiobookStreamData!!.token}"))

      mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
    }


    if (mPlayer == currentPlayer) {
      mPlayer.setMediaSource(mediaSource, currentAudiobookStreamData!!.startTime)
    } else if (castPlayer != null) {
      val mediaItem: MediaItem = MediaItem.Builder()
        .setUri(currentAudiobookStreamData!!.contentUri)
        .setMediaId(currentAudiobookStreamData!!.id)
        .setTag(metadata)
        .build()

      castPlayer?.setMediaItem(mediaItem, currentAudiobookStreamData!!.startTime)
    }
    currentPlayer.prepare()
    currentPlayer.playWhenReady = currentAudiobookStreamData!!.playWhenReady
    currentPlayer.setPlaybackSpeed(audiobookStreamData.playbackSpeed)

    lastPauseTime = 0
  }


  fun getCurrentTime() : Long {
    return mPlayer.currentPosition
  }

  fun getTheLastPauseTime() : Long {
    return lastPauseTime
  }

  fun getDuration() : Long {
    return mPlayer.duration
  }

  fun calcPauseSeekBackTime() : Long {
    if (lastPauseTime <= 0) return 0
    var time: Long = System.currentTimeMillis() - lastPauseTime
    var seekback: Long = 0
    if (time < 3000) seekback = 0
    else if (time < 60000) seekback = time / 6
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
  private val AUTO_MEDIA_ROOT = "/"
  private val ALL_ROOT = "__ALL__"
  private lateinit var browseTree:BrowseTree


  private fun isValid(packageName: String, uid: Int) : Boolean {
    Log.d(tag, "Check package $packageName is valid with uid $uid")
    return true
  }

  override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
    // Verify that the specified package is allowed to access your
    // content! You'll need to write your own logic to do this.
    return if (!isValid(clientPackageName, clientUid)) {
      // If the request comes from an untrusted package, return null.
      // No further calls will be made to other media browsing methods.
      null
    } else {
//
//      val maximumRootChildLimit = rootHints?.getInt(
//        MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT,
//        /* defaultValue= */ 4)
//      val supportedRootChildFlags = rootHints.getInt(
//        MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS,
//        /* defaultValue= */ android.media.browse.MediaBrowser.MediaItem.FLAG_BROWSABLE)


      val extras = Bundle()
      extras.putBoolean(
        MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)

      BrowserRoot(AUTO_MEDIA_ROOT, extras)
    }
  }

  override fun onLoadChildren(parentMediaId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
    val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
    Log.d(tag, "ON LOAD CHILDREN $parentMediaId")
    var flag = if (parentMediaId == AUTO_MEDIA_ROOT) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE

    if (!audiobookManager.hasLoaded) {
      result.detach()
      audiobookManager.load()
      audiobookManager.loadAudiobooks() {
        audiobookManager.isLoading = false

        Log.d(tag, "LOADED AUDIOBOOKS")
        browseTree = BrowseTree(this, audiobookManager.audiobooks, audiobookManager.localMediaManager.localAudioFiles, null)
        val children = browseTree[parentMediaId]?.map { item ->
          MediaBrowserCompat.MediaItem(item.description, flag)
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

//    if (audiobookManager.audiobooks.size == 0) {
//      Log.d(tag, "AudiobookManager: Sending no items")
//      result.sendResult(mediaItems)
//      return
//    }

    val children = browseTree[parentMediaId]?.map { item ->
      MediaBrowserCompat.MediaItem(item.description, flag)
    }
    if (children != null) {
      Log.d(tag, "BROWSE TREE $parentMediaId CHILDREN ${children.size}")
    }
    result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)

    // Check if this is the root menu:
    if (AUTO_MEDIA_ROOT == parentMediaId) {
      // build the MediaItem objects for the top level,
      // and put them in the mediaItems list
    } else {

      // examine the passed parentMediaId to see which submenu we're at,
      // and put the children of that menu in the mediaItems list
    }
//    Log.d(tag, "AudiobookManager: Sending ${mediaItems.size} Aduiobooks")
//    result.sendResult(mediaItems)
  }

  override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
    val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

    if (!audiobookManager.hasLoaded) {
      result.detach()
      audiobookManager.load()
      audiobookManager.loadAudiobooks() {
        audiobookManager.isLoading = false

        Log.d(tag, "LOADED AUDIOBOOKS")
        browseTree = BrowseTree(this, audiobookManager.audiobooks, audiobookManager.localMediaManager.localAudioFiles, null)
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
  // SLEEP TIMER STUFF
  //

  fun setSleepTimer(time: Long, isChapterTime: Boolean) : Boolean {
    Log.d(tag, "Setting Sleep Timer for $time is chapter time $isChapterTime")
    sleepTimerTask?.cancel()
    sleepChapterTime = 0L

    if (isChapterTime) {
      // Validate time
      if (currentPlayer.isPlaying) {
        if (currentPlayer.currentPosition >= time) {
          Log.d(tag, "Invalid setSleepTimer chapter time is already passed")
          return false
        }
      }

      sleepChapterTime = time
      sleepTimerTask = Timer("SleepTimer", false).schedule(0L, 1000L) {
        Handler(Looper.getMainLooper()).post() {
          if (currentPlayer.isPlaying && currentPlayer.currentPosition > sleepChapterTime) {
            Log.d(tag, "Sleep Timer Pausing Player on Chapter")
            currentPlayer.pause()

            listener?.onSleepTimerEnded(currentPlayer.currentPosition)
            sleepTimerTask?.cancel()
          }
        }
      }
    } else {
      sleepTimerTask = Timer("SleepTimer", false).schedule(time) {
        Log.d(tag, "Sleep Timer Done")
        Handler(Looper.getMainLooper()).post() {
          if (currentPlayer.isPlaying) {
            Log.d(tag, "Sleep Timer Pausing Player")
            currentPlayer.pause()
          }
          listener?.onSleepTimerEnded(currentPlayer.currentPosition)
        }
      }
    }
    return true
  }

  fun getSleepTimerTime():Long? {
    var time = sleepTimerTask?.scheduledExecutionTime()
    Log.d(tag, "Sleep Timer execution time $time")
    return time
  }

  fun cancelSleepTimer() {
    Log.d(tag, "Canceling Sleep Timer")
    sleepTimerTask?.cancel()
    sleepTimerTask = null
    sleepChapterTime = 0L
  }

  private inner class CastSessionAvailabilityListener : SessionAvailabilityListener {

    /**
     * Called when a Cast session has started and the user wishes to control playback on a
     * remote Cast receiver rather than play audio locally.
     */
    override fun onCastSessionAvailable() {
//      switchToPlayer(currentPlayer, castPlayer!!)
      Log.d(tag, "CAST SeSSION AVAILABLE " + castPlayer?.deviceInfo)
        mediaSessionConnector.setPlayer(castPlayer)
      currentPlayer = castPlayer as CastPlayer
    }

    /**
     * Called when a Cast session has ended and the user wishes to control playback locally.
     */
    override fun onCastSessionUnavailable() {
//      switchToPlayer(currentPlayer, exoPlayer)
      Log.d(tag, "CAST SESSION UNAVAILABLE")
      mediaSessionConnector.setPlayer(mPlayer)
      currentPlayer = mPlayer
    }
  }

  fun requestSession(mainActivity: Activity, callback: RequestSessionCallback) {
    this.mainActivity = mainActivity

  mainActivity.runOnUiThread(object : Runnable {
    override fun run() {
      Log.d(tag, "CAST RUNNING ON MAIN THREAD")

      val session: CastSession? = getSession()
      if (session == null) {
        // show the "choose a connection" dialog

        // Add the connection listener callback
        listenForConnection(callback)

        // Create the dialog
        // TODO accept theme as a config.xml option
        val builder = MediaRouteChooserDialog(mainActivity, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        builder.routeSelector = MediaRouteSelector.Builder()
          .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
          .build()
        builder.setCanceledOnTouchOutside(true)
        builder.setOnCancelListener {
          getSessionManager()!!.removeSessionManagerListener(newConnectionListener, CastSession::class.java)
          callback.onCancel()
        }
        builder.show()
      } else {
        // We are are already connected, so show the "connection options" Dialog
        val builder: AlertDialog.Builder = AlertDialog.Builder(mainActivity)
        if (session.castDevice != null) {
          builder.setTitle(session.castDevice.friendlyName)
        }
        builder.setOnDismissListener { callback.onCancel() }
        builder.setPositiveButton("Stop Casting") { dialog, which -> endSession(true, null) }
        builder.show()
      }
    }
  })
  }

  abstract class RequestSessionCallback : ConnectionCallback {
    abstract fun onError(errorCode: Int)
    abstract fun onCancel()
    override fun onSessionEndedBeforeStart(errorCode: Int): Boolean {
      onSessionStartFailed(errorCode)
      return true
    }

    override fun onSessionStartFailed(errorCode: Int): Boolean {
      onError(errorCode)
      return true
    }
  }

  fun endSession(stopCasting: Boolean, pluginCall: PluginCall?) {

    getSessionManager()!!.addSessionManagerListener(object : SessionListener() {
      override fun onSessionEnded(castSession: CastSession?, error: Int) {
        getSessionManager()!!.removeSessionManagerListener(this, CastSession::class.java)
        Log.d(tag, "CAST END SESSION")
//        media.setSession(null)
        pluginCall?.resolve()
//        listener.onSessionEnd(ChromecastUtilities.createSessionObject(castSession, if (stopCasting) "stopped" else "disconnected"))
      }
    }, CastSession::class.java)
    getSessionManager()!!.endCurrentSession(stopCasting)

  }

  open class SessionListener : SessionManagerListener<CastSession> {
    override fun onSessionStarting(castSession: CastSession?) {}
    override fun onSessionStarted(castSession: CastSession?, sessionId: String) {}
    override fun onSessionStartFailed(castSession: CastSession?, error: Int) {}
    override fun onSessionEnding(castSession: CastSession?) {}
    override fun onSessionEnded(castSession: CastSession?, error: Int) {}
    override fun onSessionResuming(castSession: CastSession?, sessionId: String) {}
    override fun onSessionResumed(castSession: CastSession?, wasSuspended: Boolean) {}
    override fun onSessionResumeFailed(castSession: CastSession?, error: Int) {}
    override fun onSessionSuspended(castSession: CastSession?, reason: Int) {}
  }

  private fun startRouteScan() {
    var connListener = object: ChromecastListener() {
      override fun onReceiverAvailableUpdate(available: Boolean) {
        Log.d(tag, "CAST RECEIVER UPDATE AVAILABLE $available")
      }

      override fun onSessionRejoin(jsonSession: JSONObject?) {
        Log.d(tag, "CAST onSessionRejoin")
      }

      override fun onMediaLoaded(jsonMedia: JSONObject?) {
        Log.d(tag, "CAST onMediaLoaded")
      }

      override fun onMediaUpdate(jsonMedia: JSONObject?) {
        Log.d(tag, "CAST onMediaUpdate")
      }

      override fun onSessionUpdate(jsonSession: JSONObject?) {
        Log.d(tag, "CAST onSessionUpdate")
      }

      override fun onSessionEnd(jsonSession: JSONObject?) {
        Log.d(tag, "CAST onSessionEnd")
      }

      override fun onMessageReceived(p0: CastDevice, p1: String, p2: String) {
        Log.d(tag, "CAST onMessageReceived")
      }
    }

    var callback = object : ScanCallback() {
      override fun onRouteUpdate(routes: List<MediaRouter.RouteInfo>?) {
        Log.d(tag, "CAST On ROUTE UPDATED ${routes?.size} | ${getContext().castState}")
        // if the routes have changed, we may have an available device
        // If there is at least one device available
        if (getContext().castState != CastState.NO_DEVICES_AVAILABLE) {

          routes?.forEach { Log.d(tag, "CAST ROUTE ${it.description} | ${it.deviceType} | ${it.isBluetooth} | ${it.name}") }

          // Stop the scan
          stopRouteScan(this, null);
          // Let the client know a receiver is available
          connListener.onReceiverAvailableUpdate(true);
          // Since we have a receiver we may also have an active session
          var session = getSessionManager()?.currentCastSession;
          // If we do have a session
          if (session != null) {
            // Let the client know
            Log.d(tag, "LET SESSION KNOW ABOUT")
//            media.setSession(session);
//            connListener.onSessionRejoin(ChromecastUtilities.createSessionObject(session));
          }
        }
      }
    }
    callback.setMediaRouter(getMediaRouter())

    callback.onFilteredRouteUpdate();

    getMediaRouter()!!.addCallback(MediaRouteSelector.Builder()
      .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
      .build(),
      callback,
      MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
  }

  internal interface CastListener : MessageReceivedCallback {
    fun onMediaLoaded(jsonMedia: JSONObject?)
    fun onMediaUpdate(jsonMedia: JSONObject?)
    fun onSessionUpdate(jsonSession: JSONObject?)
    fun onSessionEnd(jsonSession: JSONObject?)
  }

  internal abstract class ChromecastListener : CastStateListener, CastListener {
    abstract fun onReceiverAvailableUpdate(available: Boolean)
    abstract fun onSessionRejoin(jsonSession: JSONObject?)

    /** CastStateListener functions.  */
    override fun onCastStateChanged(state: Int) {
      onReceiverAvailableUpdate(state != CastState.NO_DEVICES_AVAILABLE)
    }
  }

  fun stopRouteScan(callback: ScanCallback?, completionCallback: Runnable?) {
    if (callback == null) {
      completionCallback!!.run()
      return
    }
//    ctx.runOnUiThread(Runnable {
      callback.stop()
      getMediaRouter()!!.removeCallback(callback)
      completionCallback?.run()
//    })
  }

  abstract class ScanCallback : MediaRouter.Callback() {
    /**
     * Called whenever a route is updated.
     * @param routes the currently available routes
     */
    abstract fun onRouteUpdate(routes: List<MediaRouter.RouteInfo>?)

    /** records whether we have been stopped or not.  */
    private var stopped = false

    /** Global mediaRouter object.  */
    private var mediaRouter: MediaRouter? = null

    /**
     * Sets the mediaRouter object.
     * @param router mediaRouter object
     */
    fun setMediaRouter(router: MediaRouter?) {
      mediaRouter = router
    }

    /**
     * Call this method when you wish to stop scanning.
     * It is important that it is called, otherwise battery
     * life will drain more quickly.
     */
    fun stop() {
      stopped = true
    }

    fun onFilteredRouteUpdate() {
      if (stopped || mediaRouter == null) {
        return
      }
      val outRoutes: MutableList<MediaRouter.RouteInfo> = ArrayList()
      // Filter the routes
      for (route in mediaRouter!!.routes) {
        // We don't want default routes, or duplicate active routes
        // or multizone duplicates https://github.com/jellyfin/cordova-plugin-chromecast/issues/32
        val extras: Bundle? = route.extras
        if (extras != null) {
          CastDevice.getFromBundle(extras)
          if (extras.getString("com.google.android.gms.cast.EXTRA_SESSION_ID") != null) {
            continue
          }
        }
        if (!route.isDefault
          && !route.description.equals("Google Cast Multizone Member")
          && route.playbackType === MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE) {
          outRoutes.add(route)
        }
      }
      onRouteUpdate(outRoutes)
    }

    override fun onRouteAdded(router: MediaRouter?, route: MediaRouter.RouteInfo?) {
      onFilteredRouteUpdate()
    }

    override fun onRouteChanged(router: MediaRouter?, route: MediaRouter.RouteInfo?) {
      onFilteredRouteUpdate()
    }

    override fun onRouteRemoved(router: MediaRouter?, route: MediaRouter.RouteInfo?) {
      onFilteredRouteUpdate()
    }
  }

  private fun listenForConnection(callback: ConnectionCallback) {
    // We should only ever have one of these listeners active at a time, so remove previous
    getSessionManager()?.removeSessionManagerListener(newConnectionListener, CastSession::class.java)

    newConnectionListener = object : SessionListener() {
      override fun onSessionStarted(castSession: CastSession?, sessionId: String) {
        Log.d(tag, "CAST SESSION STARTED ${castSession?.castDevice?.friendlyName}")
        getSessionManager()?.removeSessionManagerListener(this, CastSession::class.java)

        try {
          val castContext = CastContext.getSharedInstance(mainActivity)
          castPlayer = CastPlayer(castContext).apply {
            setSessionAvailabilityListener(CastSessionAvailabilityListener())
            addListener(getPlayerListener())
          }

          currentPlayer = castPlayer as CastPlayer

          if (currentAudiobookStreamData != null) {
            var mimeType = MimeTypes.AUDIO_AAC

            val mediaItem: MediaItem = MediaItem.Builder()
              .setUri(currentAudiobookStreamData!!.contentUri)
              .setMediaId(currentAudiobookStreamData!!.id).setMimeType(mimeType)
//              .setTag(metadata)
              .build()

            castPlayer?.setMediaItem(mediaItem, currentAudiobookStreamData!!.startTime)
          }
          Log.d(tag, "CAST Cast Player Applied")
        } catch (e: Exception) {
          // We wouldn't normally catch the generic `Exception` however
          // calling `CastContext.getSharedInstance` can throw various exceptions, all of which
          // indicate that Cast is unavailable.
          // Related internal bug b/68009560.
          Log.i(tag, "Cast is not available on this device. " +
            "Exception thrown when attempting to obtain CastContext. " + e.message)
          null
        }
//        media.setSession(castSession)
//        callback.onJoin(ChromecastUtilities.createSessionObject(castSession))
      }

      override fun onSessionStartFailed(castSession: CastSession?, errCode: Int) {
        if (callback.onSessionStartFailed(errCode)) {
          getSessionManager()?.removeSessionManagerListener(this, CastSession::class.java)
        }
      }

      override fun onSessionEnded(castSession: CastSession?, errCode: Int) {
        if (callback.onSessionEndedBeforeStart(errCode)) {
          getSessionManager()?.removeSessionManagerListener(this, CastSession::class.java)
        }
      }
    }

    getSessionManager()?.addSessionManagerListener(newConnectionListener, CastSession::class.java)
  }

  private fun getContext(): CastContext {
    return CastContext.getSharedInstance(ctx)
  }

  private fun getSessionManager(): SessionManager? {
    return getContext().sessionManager
  }

  private fun getMediaRouter(): MediaRouter? {
    return MediaRouter.getInstance(ctx)
  }

  private fun getSession(): CastSession? {
    return getSessionManager()?.currentCastSession
  }

  internal interface ConnectionCallback {
    /**
     * Successfully joined a session on a route.
     * @param jsonSession the session we joined
     */
    fun onJoin(jsonSession: JSONObject?)

    /**
     * Called if we received an error.
     * @param errorCode You can find the error meaning here:
     * https://developers.google.com/android/reference/com/google/android/gms/cast/CastStatusCodes
     * @return true if we are done listening for join, false, if we to keep listening
     */
    fun onSessionStartFailed(errorCode: Int): Boolean

    /**
     * Called when we detect a session ended event before session started.
     * See issues:
     * https://github.com/jellyfin/cordova-plugin-chromecast/issues/49
     * https://github.com/jellyfin/cordova-plugin-chromecast/issues/48
     * @param errorCode error to output
     * @return true if we are done listening for join, false, if we to keep listening
     */
    fun onSessionEndedBeforeStart(errorCode: Int): Boolean
  }
}

