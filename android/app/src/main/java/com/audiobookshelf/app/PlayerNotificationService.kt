package com.audiobookshelf.app

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
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.getcapacitor.JSObject
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.*
import kotlinx.coroutines.*
import android.view.KeyEvent
import java.io.File
import java.util.*
import kotlin.concurrent.schedule
import android.annotation.SuppressLint


const val NOTIFICATION_LARGE_ICON_SIZE = 144 // px

class PlayerNotificationService : MediaBrowserServiceCompat()  {

  companion object {
    var isStarted = false
  }

  interface MyCustomObjectListener {
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: JSObject)
    fun onPrepare(audiobookId:String, playWhenReady:Boolean)
  }

  private val tag = "PlayerService"

  private lateinit var listener:MyCustomObjectListener
  private lateinit var ctx:Context
  private lateinit var mPlayer: SimpleExoPlayer
  private lateinit var mediaSessionConnector: MediaSessionConnector
  private lateinit var playerNotificationManager: PlayerNotificationManager
  private lateinit var mediaSession: MediaSessionCompat

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
  private val binder = LocalBinder()
  private val glideOptions = RequestOptions()
    .fallback(R.drawable.icon)
    .diskCacheStrategy(DiskCacheStrategy.DATA)

  private var notificationId = 10;
  private var channelId = "audiobookshelf_channel"
  private var channelName = "Audiobookshelf Channel"

  private var currentAudiobook:Audiobook? = null

  private var audiobooks = mutableListOf<Audiobook>()

  private var mediaButtonClickCount: Int = 0
  var mediaButtonClickTimeout: Long = 1000  //ms
  var seekAmount: Long = 20000   //ms

  private var lastPauseTime: Long = 0   //ms
  private var onSeekBack: Boolean = false

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
    Log.d(tag, "onStart $startId" )
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createNotificationChannel(channelId: String, channelName: String): String{
    val chan = NotificationChannel(channelId,
      channelName, NotificationManager.IMPORTANCE_HIGH)
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

  channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    createNotificationChannel(channelId, channelName)
  } else ""


    var simpleExoPlayerBuilder = SimpleExoPlayer.Builder(this)
    simpleExoPlayerBuilder.setSeekBackIncrementMs(10000)
    simpleExoPlayerBuilder.setSeekForwardIncrementMs(10000)
    mPlayer = simpleExoPlayerBuilder.build()
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

    // Color is set based on the art - cannot override
//    playerNotificationManager.setColor(Color.RED)
//    playerNotificationManager.setColorized(true)

    // Icon needs to be black and white
//    playerNotificationManager.setSmallIcon(R.drawable.icon_32)

    mediaSessionConnector = MediaSessionConnector(mediaSession)
    val queueNavigator: TimelineQueueNavigator = object : TimelineQueueNavigator(mediaSession) {
      override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
        var builder = MediaDescriptionCompat.Builder()
          .setMediaId(currentAudiobook!!.id)
          .setTitle(currentAudiobook!!.title)
          .setSubtitle(currentAudiobook!!.author)
          .setMediaUri(currentAudiobook!!.playlistUri)
          .setIconUri(currentAudiobook!!.coverUri)
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

        var audiobook = audiobooks[0]
        if (audiobook == null) {
          Log.e(tag, "Audiobook NOT FOUND")
          return
        }
        listener.onPrepare(audiobook.id, playWhenReady)
      }

      override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
        Log.d(tag, "ON PREPARE FROM MEDIA ID $mediaId $playWhenReady")
        var audiobook = audiobooks.find { it.id == mediaId }
        if (audiobook == null) {
          Log.e(tag, "Audiobook NOT FOUND")
          return
        }
        listener.onPrepare(audiobook.id, playWhenReady)
      }

      override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
        Log.d(tag, "ON PREPARE FROM SEARCH $query")
      }

      override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {
        Log.d(tag, "ON PREPARE FROM URI $uri")
      }

    }
    mediaSessionConnector.setQueueNavigator(queueNavigator)
    mediaSessionConnector.setPlaybackPreparer(myPlaybackPreparer)
    mediaSessionConnector.setPlayer(mPlayer)

    //attach player to playerNotificationManager
    playerNotificationManager.setPlayer(mPlayer)

    mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
    mediaSession.setCallback(object : MediaSessionCompat.Callback() {
      override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
        return handleCallMediaButton(mediaButtonEvent)
      }
    })
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
        Glide.with(ctx).applyDefaultRequestOptions(glideOptions)
          .asBitmap()
          .load(uri)
          .submit(NOTIFICATION_LARGE_ICON_SIZE, NOTIFICATION_LARGE_ICON_SIZE)
          .get()
      }
    }
  }

  private fun setPlayerListeners() {
    mPlayer.addListener(object : Player.Listener {
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
          if (mPlayer.playbackState == Player.STATE_READY) {
            Log.d(tag, "STATE_READY : " + mPlayer.duration.toString())

            /*if (!currentAudiobook!!.hasPlayerLoaded && currentAudiobook!!.startTime > 0) {
              Log.d(tag, "Should seek to ${currentAudiobook!!.startTime}")
              mPlayer.seekTo(currentAudiobook!!.startTime)
            }*/

            currentAudiobook!!.hasPlayerLoaded = true
            if (lastPauseTime == 0L) {
              sendClientMetadata("ready_no_sync")
              lastPauseTime = -1;
            }
            else sendClientMetadata("ready")
          }
          if (mPlayer.playbackState == Player.STATE_BUFFERING) {
            Log.d(tag, "STATE_BUFFERING : " + mPlayer.currentPosition.toString())
            if (lastPauseTime == 0L) sendClientMetadata("buffering_no_sync")
            else sendClientMetadata("buffering")
          }
          if (mPlayer.playbackState == Player.STATE_ENDED) {
            Log.d(tag, "STATE_ENDED")
            sendClientMetadata("ended")
          }
          if (mPlayer.playbackState == Player.STATE_IDLE) {
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
          }
          else lastPauseTime = System.currentTimeMillis()
          if (listener != null) listener.onPlayingUpdate(player.isPlaying)
        }
      }
    })
  }


  /*
    User callable methods
  */

//  fun initPlayer(token: String, playlistUri: String, playWhenReady: Boolean, currentTime: Long, title: String, artist: String, albumArt: String) {
  fun initPlayer(audiobook: Audiobook) {
    currentAudiobook = audiobook

    Log.d(tag, "Init Player Audiobook ${currentAudiobook!!.playlistUrl} | ${currentAudiobook!!.title} | ${currentAudiobook!!.author}")

    if (mPlayer.isPlaying) {
      Log.d(tag, "Init Player audiobook already playing")
    }

    var metadataBuilder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentAudiobook!!.title)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentAudiobook!!.title)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentAudiobook!!.author)
      .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, currentAudiobook!!.author)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentAudiobook!!.author)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAudiobook!!.series)
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentAudiobook!!.id)

    if (currentAudiobook!!.cover != "") {
      metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, currentAudiobook!!.cover)
      metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, currentAudiobook!!.cover)
    }

    var metadata = metadataBuilder.build()
    mediaSession.setMetadata(metadata)

    var mediaMetadata = MediaMetadata.Builder().build()


    var mediaSource:MediaSource
    if (currentAudiobook!!.isLocal) {
      Log.d(tag, "Playing Local File")
      var mediaItem = MediaItem.Builder().setUri(currentAudiobook!!.contentUri).setMediaMetadata(mediaMetadata).build()
      var dataSourceFactory = DefaultDataSourceFactory(ctx, channelId)
      mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
    } else {
      Log.d(tag, "Playing HLS File")
      var mediaItem = MediaItem.Builder().setUri(currentAudiobook!!.playlistUri).setMediaMetadata(mediaMetadata).build()
      var dataSourceFactory = DefaultHttpDataSource.Factory()
      dataSourceFactory.setUserAgent(channelId)
      dataSourceFactory.setDefaultRequestProperties(hashMapOf("Authorization" to "Bearer ${currentAudiobook!!.token}"))

      mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
    }


    //mPlayer.setMediaSource(mediaSource, true)
    mPlayer.setMediaSource(mediaSource, currentAudiobook!!.startTime)
    mPlayer.prepare()
    mPlayer.playWhenReady = currentAudiobook!!.playWhenReady
    mPlayer.setPlaybackSpeed(audiobook.playbackSpeed)

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
    return currentAudiobook?.id.toString()
  }

  fun play() {
    if (mPlayer.isPlaying) {
      Log.d(tag, "Already playing")
      return
    }
    mPlayer.play()
  }

  fun pause() {
    mPlayer.pause()
  }

  fun seekPlayer(time: Long) {
    mPlayer.seekTo(time)
  }

  fun seekForward(amount:Long) {
    mPlayer.seekTo(mPlayer.currentPosition + amount)
  }

  fun seekBackward(amount:Long) {
    mPlayer.seekTo(mPlayer.currentPosition - amount)
  }

  fun setPlaybackSpeed(speed:Float) {
    mPlayer.setPlaybackSpeed(speed)
  }

  fun terminateStream() {
    if (mPlayer.playbackState == Player.STATE_READY) {
      mPlayer.clearMediaItems()
    }
    currentAudiobook?.id = ""
    lastPauseTime = 0
  }

  fun sendClientMetadata(stateName: String) {
    var metadata = JSObject()
    var duration = mPlayer.duration
    if (duration < 0) duration = 0
    metadata.put("duration", duration)
    metadata.put("currentTime", mPlayer.currentPosition)
    metadata.put("stateName", stateName)
    if (listener != null) listener.onMetadata(metadata)
  }


  //
  // MEDIA BROWSER STUFF (ANDROID AUTO)
  //
  private val MY_MEDIA_ROOT_ID = "audiobookshelf"

  fun setAudiobooks(_audiobooks:MutableList<Audiobook>) {
    audiobooks = _audiobooks
  }

  private fun isValid(packageName:String, uid:Int) : Boolean {
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
      val extras = Bundle()
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
      MediaBrowserServiceCompat.BrowserRoot(MY_MEDIA_ROOT_ID, extras)
    }
  }

  override fun onLoadChildren(parentMediaId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
    val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

    if (audiobooks.size == 0) {
      result.sendResult(mediaItems)
      return
    }

    audiobooks.forEach {
      var builder = MediaDescriptionCompat.Builder()
        .setMediaId(it.id)
        .setTitle(it.title)
        .setSubtitle(it.author)
        .setMediaUri(it.playlistUri)
        .setIconUri(it.coverUri)

//      val extras = Bundle()
//      var startsWithA = it.title.toLowerCase().startsWith("a")
//      var groupTitle = "test group
//      extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
//      builder.setExtras(extras)\
//      Log.d(tag, "Load Media Item for AUTO ${it.title} - ${it.author}")

      var mediaDescription = builder.build()
      var newMediaItem = MediaBrowserCompat.MediaItem(mediaDescription, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
      mediaItems.add(newMediaItem)
    }

    // Check if this is the root menu:
    if (MY_MEDIA_ROOT_ID == parentMediaId) {
      // build the MediaItem objects for the top level,
      // and put them in the mediaItems list
    } else {

      // examine the passed parentMediaId to see which submenu we're at,
      // and put the children of that menu in the mediaItems list
    }
    result.sendResult(mediaItems)
  }

  fun handleCallMediaButton(intent: Intent): Boolean {
    if(Intent.ACTION_MEDIA_BUTTON == intent.getAction()) {
      var keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
      if (keyEvent?.getAction() == KeyEvent.ACTION_UP) {
        when (keyEvent?.getKeyCode()) {
          KeyEvent.KEYCODE_HEADSETHOOK -> {
            if(0 == mediaButtonClickCount) {
              if (mPlayer.isPlaying)
                pause()
              else
                play()
            }
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_PLAY -> {
            if(0 == mediaButtonClickCount) play()
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            if(0 == mediaButtonClickCount) pause()
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
        handler.sendEmptyMessage(mediaButtonClickCount)
        mediaButtonClickCount = 0
      }
    }
  }

  private val handler : Handler = @SuppressLint("HandlerLeak")
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
}

