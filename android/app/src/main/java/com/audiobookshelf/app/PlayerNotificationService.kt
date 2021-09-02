package com.audiobookshelf.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.getcapacitor.JSObject
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.*
import kotlinx.coroutines.*


const val NOTIFICATION_LARGE_ICON_SIZE = 144 // px

class PlayerNotificationService : Service()  {

  companion object {
    var isStarted = false
  }

  interface MyCustomObjectListener {
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: JSObject)
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

  fun setCustomObjectListener(mylistener: MyCustomObjectListener) {
    listener = mylistener
  }

   /*
      Service related stuff
   */
  override fun onBind(intent: Intent?): IBinder? {
    Log.d(tag, "onBind")
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

    mPlayer = SimpleExoPlayer.Builder(this).build()
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
        return MediaDescriptionCompat.Builder()
          .setMediaId(currentAudiobook!!.id)
          .setTitle(currentAudiobook!!.title)
          .setSubtitle(currentAudiobook!!.author)
          .setMediaUri(currentAudiobook!!.playlistUri)
          .setIconUri(currentAudiobook!!.coverUri)
          .build()
      }
    }
    mediaSessionConnector.setQueueNavigator(queueNavigator)
    mediaSessionConnector.setPlayer(mPlayer)

    //attach player to playerNotificationManager
    playerNotificationManager.setPlayer(mPlayer)
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
        if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
          Log.d(tag, "EVENT_TRACKS_CHANGED")
        }

        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
          Log.d(tag, "EVENT_TIMELINE_CHANGED")
        }

        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
          Log.d(tag, "EVENT_POSITION_DISCONTINUITY")
        }

        if (events.contains(Player.EVENT_IS_LOADING_CHANGED)) {
          Log.d(tag, "EVENT_IS_LOADING_CHANGED : " + mPlayer.isLoading.toString())
        }

        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
          if (mPlayer.playbackState == Player.STATE_READY) {
            Log.d(tag, "STATE_READY : " + mPlayer.duration.toString())

            if (!currentAudiobook!!.hasPlayerLoaded && currentAudiobook!!.startTime > 0) {
              Log.d(tag, "Should seek to ${currentAudiobook!!.startTime}")
              mPlayer.seekTo(currentAudiobook!!.startTime)
            }

            currentAudiobook!!.hasPlayerLoaded = true
            sendClientMetadata("ready")
          }
          if (mPlayer.playbackState == Player.STATE_BUFFERING) {
            Log.d(tag, "STATE_BUFFERING : " + mPlayer.currentPosition.toString())
            sendClientMetadata("buffering")
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

    val metadata = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentAudiobook!!.title)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentAudiobook!!.title)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentAudiobook!!.author)
      .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, currentAudiobook!!.author)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentAudiobook!!.author)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAudiobook!!.series)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, currentAudiobook!!.cover)
      .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, currentAudiobook!!.cover)
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentAudiobook!!.id)
      .build()

    mediaSession.setMetadata(metadata)

    var mediaMetadata = MediaMetadata.Builder().build()
    var mediaItem = MediaItem.Builder().setUri(currentAudiobook!!.playlistUri).setMediaMetadata(mediaMetadata).build()

    var dataSourceFactory = DefaultHttpDataSource.Factory()
    dataSourceFactory.setUserAgent(channelId)
    dataSourceFactory.setDefaultRequestProperties(hashMapOf("Authorization" to "Bearer ${currentAudiobook!!.token}"))

    var mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

    mPlayer.setMediaSource(mediaSource, true)
    mPlayer.prepare()
    mPlayer.playWhenReady = currentAudiobook!!.playWhenReady
  }


  fun getCurrentTime() : Long {
    return mPlayer.currentPosition
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

  fun seekForward10() {
    mPlayer.seekTo(mPlayer.currentPosition + 10000)
  }

  fun seekBackward10() {
    mPlayer.seekTo(mPlayer.currentPosition - 10000)
  }

  fun terminateStream() {
    if (mPlayer.playbackState == Player.STATE_READY) {
      mPlayer.clearMediaItems()
    }
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
}
