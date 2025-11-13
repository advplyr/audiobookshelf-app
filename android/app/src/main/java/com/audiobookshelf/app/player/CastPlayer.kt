package com.audiobookshelf.app.player

import android.annotation.SuppressLint
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.C.TrackType
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.Player.*
import com.google.android.exoplayer2.Tracks.Group
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.cast.SessionAvailabilityListener
import com.google.android.exoplayer2.source.TrackGroup
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.text.CueGroup
import com.google.android.exoplayer2.trackselection.TrackSelection
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.trackselection.TrackSelectionParameters
import com.google.android.exoplayer2.util.*
import com.google.android.exoplayer2.util.Util.castNonNull
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.gms.cast.*
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.ResultCallback
import org.json.JSONObject


import com.audiobookshelf.app.player.PlayerMediaItem

class CastPlayer(var castContext: CastContext) : BasePlayer() {
  val tag = "CastPlayer"

  private val RENDERER_COUNT = 3
  private val RENDERER_INDEX_VIDEO = 0
  private val RENDERER_INDEX_AUDIO = 1
  private val RENDERER_INDEX_TEXT = 2
  private val PROGRESS_REPORT_PERIOD_MS: Long = 1000
  private val EMPTY_TRACK_SELECTION_ARRAY = TrackSelectionArray()
  private val EMPTY_TRACK_ID_ARRAY = LongArray(0)

  private val seekBackIncrementMs: Long = 5000L
  private val seekForwardIncrementMs: Long = 5000L
  private var myPlayWhenReady:Boolean = false
  private var playbackParameters:PlaybackParameters = PlaybackParameters.DEFAULT
  private var myAvailableCommands: Commands
  private var myCurrentTracksInfo: Tracks
  private var myCurrentTrackGroups: TrackGroupArray
  private var myCurrentTrackSelections: TrackSelectionArray

  var currentMediaItems:List<MediaItem> = mutableListOf()
  var remoteMediaClient:RemoteMediaClient? = null
  var sessionAvailabilityListener:SessionAvailabilityListener? = null
  var myCurrentTimeline: CastTimeline
  val timelineTracker: CastTimelineTracker
  val period: Timeline.Period
  var listeners: ListenerSet<Player.Listener>

  /* package */
  val PERMANENT_AVAILABLE_COMMANDS = Commands.Builder()
    .addAll(
      COMMAND_PLAY_PAUSE,
      COMMAND_PREPARE,
      COMMAND_STOP,
      COMMAND_SEEK_TO_DEFAULT_POSITION,
      COMMAND_SEEK_TO_MEDIA_ITEM,
      COMMAND_SET_REPEAT_MODE,
      COMMAND_SET_SPEED_AND_PITCH,
      COMMAND_GET_CURRENT_MEDIA_ITEM,
      COMMAND_GET_TIMELINE,
      COMMAND_GET_MEDIA_ITEMS_METADATA,
      COMMAND_SET_MEDIA_ITEMS_METADATA,
      COMMAND_CHANGE_MEDIA_ITEMS,
      COMMAND_GET_TRACKS)
    .build()

  var currentPlaybackState = Player.STATE_IDLE
  var statusListener:StatusListener

  val deviceInfo get() = castContext.sessionManager.currentCastSession?.castDevice.toString()
  var lastReportedPositionMs = 0L

  private var currentMediaItemIndex = 0
  private var pendingMediaItemRemovalPosition:PositionInfo? = null
  private var pendingSeekCount = 0
  private var pendingSeekWindowIndex = C.INDEX_UNSET
  private var pendingSeekPositionMs = 0L

  init {
    Log.d(tag, "Init CastPlayer")
    myCurrentTrackGroups = TrackGroupArray.EMPTY
    myCurrentTrackSelections = EMPTY_TRACK_SELECTION_ARRAY
    myCurrentTracksInfo = Tracks.EMPTY
    statusListener = StatusListener()
    timelineTracker = CastTimelineTracker()
    myCurrentTimeline = CastTimeline.EMPTY_CAST_TIMELINE
    period = Timeline.Period()
    myAvailableCommands = Commands.Builder().addAll(PERMANENT_AVAILABLE_COMMANDS).build()

    listeners = ListenerSet(
      Looper.getMainLooper(),
      Clock.DEFAULT
    ) { listener: Player.Listener, flags: FlagSet? -> listener.onEvents(this,  Player.Events(flags ?: FlagSet.Builder().build())) }

    val sessionManager = castContext.sessionManager
    sessionManager.addSessionManagerListener(statusListener, CastSession::class.java)
    val session = sessionManager.currentCastSession
    setRemoteMediaClient(session?.remoteMediaClient)
  }

  fun load(mediaItems:List<PlayerMediaItem>, startIndex:Int, startTime:Long, playWhenReady:Boolean, playbackRate:Float, mediaType:String) {
    Log.d(tag, "Load called")

    if (remoteMediaClient == null) {
      Log.d(tag, "Remote Media Client not set")
      return
    }

    // Convert DTOs to Exo MediaItem internally so callers don't need Exo types.
    val exoItems: List<MediaItem> = mediaItems.map { dto ->
      val builder = MediaItem.Builder().setUri(dto.uri)
      dto.tag?.let { builder.setTag(it) }
      dto.mimeType?.let { builder.setMimeType(it) }
      builder.build()
    }

    currentMediaItems = exoItems

    var mediaQueueItems = exoItems.map { toMediaQueueItem(it) }

    var queueData = MediaQueueData.Builder().apply {
      setItems(mediaQueueItems)
      setQueueType(if (mediaType == "book") MediaQueueData.MEDIA_QUEUE_TYPE_AUDIO_BOOK else MediaQueueData.MEDIA_QUEUE_TYPE_PODCAST_SERIES)
      setRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_OFF)
      setStartIndex(startIndex)
      setStartTime(startTime)
    }.build()

    var loadRequestData = MediaLoadRequestData.Builder().apply {
      setPlaybackRate(playbackRate.toDouble())
      setQueueData(queueData)
      setAutoplay(playWhenReady)
      setCurrentTime(startTime)
    }.build()

    remoteMediaClient?.load(loadRequestData)?.setResultCallback {
      Log.d(tag, "Loaded cast player result ${it.status} | ${it.mediaError} | ${it.customData}")
    }

    Log.d(tag, "Loaded cast player request data $loadRequestData")
  }

  private fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
    // The MediaQueueItem you build is expected to be in the tag.
    return (mediaItem.localConfiguration!!.tag as MediaQueueItem?)!!
  }

  @JvmName("setRemoteMediaClient1")
  private fun setRemoteMediaClient(remoteMediaClient: RemoteMediaClient?) {
    if (this.remoteMediaClient === remoteMediaClient) {
      // Do nothing.
      return
    }
    if (this.remoteMediaClient != null) {
      this.remoteMediaClient?.unregisterCallback(statusListener)
      this.remoteMediaClient?.removeProgressListener(statusListener)
    }
    this.remoteMediaClient = remoteMediaClient
    if (remoteMediaClient != null) {
      sessionAvailabilityListener?.let {
        it.onCastSessionAvailable()
      }

      remoteMediaClient.registerCallback(statusListener)
      remoteMediaClient.addProgressListener(statusListener, PROGRESS_REPORT_PERIOD_MS)
      updateInternalStateAndNotifyIfChanged()
    } else {
      updateTimelineAndNotifyIfChanged()
      sessionAvailabilityListener?.let {
        it.onCastSessionUnavailable()
      }
    }
  }

  private fun setPlayerStateAndNotifyIfChanged(
    playWhenReady: Boolean,
    @PlayWhenReadyChangeReason playWhenReadyChangeReason: Int,
    @Player.State playbackState: Int) {

    val wasPlaying = this.currentPlaybackState == STATE_READY && this.playWhenReady
    val playWhenReadyChanged = this.playWhenReady != playWhenReady
    val playbackStateChanged = this.currentPlaybackState != playbackState

    Log.d(tag, "setPlayerStateAndNotifyIfChanged newPlayWhenReady:$playWhenReady | playbackStateChanged:$playbackStateChanged | playWhenReadyChanged:$playWhenReadyChanged")
    if (playWhenReadyChanged || playbackStateChanged) {
      this.currentPlaybackState = playbackState
      this.myPlayWhenReady = playWhenReady

      if (playbackStateChanged) {
        Log.d(tag, "CastPlayer About to emit onPlaybackStateChanged")
        listeners.queueEvent(EVENT_PLAYBACK_STATE_CHANGED) {
          it.onPlaybackStateChanged(playbackState)
        }
      }

      if (playWhenReadyChanged) {
        listeners.queueEvent(EVENT_PLAY_WHEN_READY_CHANGED) {
          it.onPlayWhenReadyChanged(playWhenReady, playWhenReadyChangeReason)
        }
      }

      val isPlaying = playbackState == STATE_READY && playWhenReady
      if (wasPlaying != isPlaying) {
        Log.d(tag, "CastPlayer About to emit onIsPlayingChanged $isPlaying")
        listeners.queueEvent(EVENT_IS_PLAYING_CHANGED) {
          it.onIsPlayingChanged(isPlaying)
        }
      } else {
        Log.d(tag, "Is playing and was playing are equal $wasPlaying")
      }
    } else {
      Log.d(tag, "setPlayerStateAndNotifyIfChanged No changes")
    }
    listeners.flushEvents()
  }

  private fun updatePlayerStateAndNotifyIfChanged() {
    var newPlayWhenReadyValue = remoteMediaClient?.isPaused != true

    @PlayWhenReadyChangeReason val playWhenReadyChangeReason = if (newPlayWhenReadyValue != playWhenReady) PLAY_WHEN_READY_CHANGE_REASON_REMOTE else PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    // We do not mask the playback state, so try setting it regardless of the playWhenReady masking.
    setPlayerStateAndNotifyIfChanged(
      newPlayWhenReadyValue, playWhenReadyChangeReason, fetchPlaybackState(remoteMediaClient))
  }

  private fun fetchPlaybackState(remoteMediaClient: RemoteMediaClient?): Int {
    return when (remoteMediaClient?.playerState) {
      MediaStatus.PLAYER_STATE_BUFFERING -> STATE_BUFFERING
      MediaStatus.PLAYER_STATE_PLAYING, MediaStatus.PLAYER_STATE_PAUSED -> STATE_READY
      MediaStatus.PLAYER_STATE_IDLE, MediaStatus.PLAYER_STATE_UNKNOWN -> STATE_IDLE
      else -> STATE_IDLE
    }
  }

  private fun updatePlaybackRateAndNotifyIfChanged() {
     val mediaStatus = remoteMediaClient!!.mediaStatus
      val speed = mediaStatus?.playbackRate?.toFloat() ?: PlaybackParameters.DEFAULT.speed
      if (speed > 0.0f) {
        // Set the speed if not paused.
        setPlaybackParametersAndNotifyIfChanged(PlaybackParameters(speed))
      }
  }

  private fun setPlaybackParametersAndNotifyIfChanged(playbackParameters: PlaybackParameters) {
    if (this.playbackParameters == playbackParameters) {
      return
    }
    this.playbackParameters = playbackParameters
    listeners.queueEvent(
      EVENT_PLAYBACK_PARAMETERS_CHANGED
    ) { listener -> listener.onPlaybackParametersChanged(playbackParameters) }
    listeners.flushEvents()
    updateAvailableCommandsAndNotifyIfChanged()
  }

  private fun updateTimelineAndNotifyIfChanged(): Boolean {
    val oldTimeline: Timeline = this.myCurrentTimeline
    val oldWindowIndex = currentMediaItemIndex
    var playingPeriodChanged = false
    if (updateTimeline()) {
      val timeline: Timeline = this.myCurrentTimeline
      // Call onTimelineChanged.
      listeners.queueEvent(
        EVENT_TIMELINE_CHANGED
      ) { listener: Player.Listener -> listener.onTimelineChanged(timeline, TIMELINE_CHANGE_REASON_SOURCE_UPDATE) }

      // Call onPositionDiscontinuity if required.
      val currentTimeline = currentTimeline
      var playingPeriodRemoved = false
      if (!oldTimeline.isEmpty) {
        val oldPeriodUid = castNonNull(oldTimeline.getPeriod(oldWindowIndex, period,  /* setIds= */true).uid)
        playingPeriodRemoved = currentTimeline.getIndexOfPeriod(oldPeriodUid) == C.INDEX_UNSET
      }
      if (playingPeriodRemoved) {
        var oldPosition: PositionInfo = getCurrentPositionInfo()
        if (pendingMediaItemRemovalPosition != null) {
          pendingMediaItemRemovalPosition?.let {
            oldPosition = it
          }
          pendingMediaItemRemovalPosition = null
        } else {
          // If the media item has been removed by another client, we don't know the removal
          // position. We use the current position as a fallback.
          oldTimeline.getPeriod(oldWindowIndex, period,  /* setIds= */true)
          oldTimeline.getWindow(period.windowIndex, window)
          oldPosition = PositionInfo(
            window.uid,
            period.windowIndex,
            window.mediaItem,
            period.uid,
            period.windowIndex,
            currentPosition,
            contentPosition,
            C.INDEX_UNSET,/* adGroupIndex= */
            C.INDEX_UNSET)  /* adIndexInAdGroup= */
        }

        val newPosition: PositionInfo = getCurrentPositionInfo()
        listeners.queueEvent(
          EVENT_POSITION_DISCONTINUITY
        ) { listener: Player.Listener ->
          listener.onPositionDiscontinuity(
            oldPosition, newPosition, DISCONTINUITY_REASON_REMOVE)
        }
      }

      // Call onMediaItemTransition if required.
      playingPeriodChanged = currentTimeline.isEmpty != oldTimeline.isEmpty || playingPeriodRemoved
      if (playingPeriodChanged) {
        listeners.queueEvent(
          EVENT_MEDIA_ITEM_TRANSITION
        ) { listener: Player.Listener ->
          listener.onMediaItemTransition(
            currentMediaItem, MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        }
      }
      updateAvailableCommandsAndNotifyIfChanged()
    }
    return playingPeriodChanged
  }

  private fun updateInternalStateAndNotifyIfChanged() {
    if (remoteMediaClient == null) {
      // There is no session. We leave the state of the player as it is now.
      return
    }

    val oldWindowIndex = this.currentMediaItemIndex
    val oldPeriodUid = if (!currentTimeline.isEmpty) currentTimeline.getPeriod(oldWindowIndex, period,  /* setIds= */true).uid else null
    updatePlayerStateAndNotifyIfChanged()
//    updateRepeatModeAndNotifyIfChanged( /* resultCallback= */null)
    updatePlaybackRateAndNotifyIfChanged()
    val playingPeriodChangedByTimelineChange = updateTimelineAndNotifyIfChanged()
    val currentTimeline = currentTimeline
    currentMediaItemIndex = fetchCurrentWindowIndex(remoteMediaClient, currentTimeline)
    val currentPeriodUid = if (!currentTimeline.isEmpty) currentTimeline.getPeriod(currentMediaItemIndex, period,  /* setIds= */true).uid else null
    if (!playingPeriodChangedByTimelineChange
      && !Util.areEqual(oldPeriodUid, currentPeriodUid)
      && pendingSeekCount == 0) {
      // Report discontinuity and media item auto transition.
      currentTimeline.getPeriod(oldWindowIndex, period,  /* setIds= */true)
      currentTimeline.getWindow(oldWindowIndex, window)
      val windowDurationMs = window.durationMs
      val oldPosition = PositionInfo(
        window.uid,
        period.windowIndex,
        window.mediaItem,
        period.uid,
        period.windowIndex,  /* positionMs= */
        windowDurationMs,  /* contentPositionMs= */
        windowDurationMs,  /* adGroupIndex= */
        C.INDEX_UNSET,  /* adIndexInAdGroup= */
        C.INDEX_UNSET)
      currentTimeline.getPeriod(currentMediaItemIndex, period,  /* setIds= */true)
      currentTimeline.getWindow(currentMediaItemIndex, window)
      val newPosition = PositionInfo(
        window.uid,
        period.windowIndex,
        window.mediaItem,
        period.uid,
        period.windowIndex,  /* positionMs= */
        window.defaultPositionMs,  /* contentPositionMs= */
        window.defaultPositionMs,  /* adGroupIndex= */
        C.INDEX_UNSET,  /* adIndexInAdGroup= */
        C.INDEX_UNSET)
      listeners.queueEvent(
        EVENT_POSITION_DISCONTINUITY
      ) { listener: Listener ->
        listener.onPositionDiscontinuity(
          oldPosition, newPosition, DISCONTINUITY_REASON_AUTO_TRANSITION)
      }
      listeners.queueEvent(
        EVENT_MEDIA_ITEM_TRANSITION
      ) { listener: Listener ->
        listener.onMediaItemTransition(
          currentMediaItem, MEDIA_ITEM_TRANSITION_REASON_AUTO)
      }
    }
    if (updateTracksAndSelectionsAndNotifyIfChanged()) {
      listeners.queueEvent(
        EVENT_TRACKS_CHANGED) { listener: Listener -> listener.onTracksChanged(currentTracks) }
    }
    updateAvailableCommandsAndNotifyIfChanged()
    listeners.flushEvents()
  }

  /** Updates the internal tracks and selection and returns whether they have changed.  */
  @SuppressLint("WrongConstant")
  private fun updateTracksAndSelectionsAndNotifyIfChanged(): Boolean {
    if (remoteMediaClient == null) {
      // There is no session. We leave the state of the player as it is now.
      return false
    }
    val mediaStatus = getMediaStatus()
    val mediaInfo = mediaStatus?.mediaInfo
    val castMediaTracks = mediaInfo?.mediaTracks
    if (castMediaTracks == null || castMediaTracks.isEmpty()) {
      val hasChanged = !myCurrentTrackGroups.isEmpty
      myCurrentTrackGroups = TrackGroupArray.EMPTY
      myCurrentTrackSelections = EMPTY_TRACK_SELECTION_ARRAY
      myCurrentTracksInfo = Tracks.EMPTY
      return hasChanged
    }
    var activeTrackIds = mediaStatus.activeTrackIds
    if (activeTrackIds == null) {
      activeTrackIds = EMPTY_TRACK_ID_ARRAY
    }
    val trackGroups = arrayOfNulls<TrackGroup>(castMediaTracks.size)
    val trackSelections = arrayOfNulls<TrackSelection>(RENDERER_COUNT)
    val trackGroupInfos = arrayOfNulls<Group>(castMediaTracks.size)
    for (i in castMediaTracks.indices) {
      val mediaTrack = castMediaTracks[i]
      trackGroups[i] = TrackGroup( /* id= */i.toString(), mediaTrackToFormat(mediaTrack))
      val id = mediaTrack.id
      val trackType = MimeTypes.getTrackType(mediaTrack.contentType)
      val rendererIndex: Int = getRendererIndexForTrackType(trackType)
      val supported = rendererIndex != C.INDEX_UNSET
      val selected = isTrackActive(id, activeTrackIds) && supported && trackSelections[rendererIndex] == null
      if (selected) {
        trackSelections[rendererIndex] = trackGroups[i]?.let { CastTrackSelection(it) }
      }
      val trackSupport = intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)
      val trackSelected = booleanArrayOf(selected)
      trackGroupInfos[i] = Group(trackGroups[i]!!, false, trackSupport, trackSelected)
    }

    val tg = trackGroups.filterNotNull().toTypedArray()
    val ts = trackSelections.filterNotNull().toTypedArray()
    val tgi = trackGroupInfos.filterNotNull().toMutableList()
    val newTrackGroups = TrackGroupArray(*tg)
    val newTrackSelections = TrackSelectionArray(*ts)
    val newTracksInfo = Tracks(tgi)
    if (newTracksInfo != currentTracks) {
      myCurrentTrackSelections = newTrackSelections
      myCurrentTrackGroups = newTrackGroups
      myCurrentTracksInfo = newTracksInfo
      return true
    }
    return false
  }

  /**
   * Creates a [Format] instance containing all information contained in the given [ ] object.
   *
   * @param mediaTrack The [MediaTrack].
   * @return The equivalent [Format].
   */
  fun mediaTrackToFormat(mediaTrack: MediaTrack): Format {
    return Format.Builder()
      .setId(mediaTrack.contentId)
      .setContainerMimeType(mediaTrack.contentType)
      .setLanguage(mediaTrack.language)
      .build()
  }

  private fun getRendererIndexForTrackType(trackType: @TrackType Int): Int {
    return if (trackType == C.TRACK_TYPE_VIDEO) RENDERER_INDEX_VIDEO else if (trackType == C.TRACK_TYPE_AUDIO) RENDERER_INDEX_AUDIO else if (trackType == C.TRACK_TYPE_TEXT) RENDERER_INDEX_TEXT else C.INDEX_UNSET
  }

  private fun isTrackActive(id: Long, activeTrackIds: LongArray): Boolean {
    for (activeTrackId in activeTrackIds) {
      if (activeTrackId == id) {
        return true
      }
    }
    return false
  }

  private fun getCurrentPositionInfo(): PositionInfo {
    val currentTimeline = currentTimeline
    var newPeriodUid: Any? = null
    var newWindowUid: Any? = null
    var newMediaItem: MediaItem? = null
    if (!currentTimeline.isEmpty) {
      newPeriodUid = currentTimeline.getPeriod(currentPeriodIndex, period,  /* setIds= */true).uid
      newWindowUid = currentTimeline.getWindow(period.windowIndex, window).uid
      newMediaItem = window.mediaItem
    }
    return PositionInfo(
      newWindowUid,
      currentMediaItemIndex,
      newMediaItem,
      newPeriodUid,
      currentPeriodIndex,
      currentPosition,
      contentPosition,
      C.INDEX_UNSET,/* adGroupIndex= */
      C.INDEX_UNSET)  /* adIndexInAdGroup= */
  }

  private fun updateTimeline(): Boolean {
    val oldTimeline = this.myCurrentTimeline
    val status: MediaStatus? = getMediaStatus()

    remoteMediaClient?.let {
      this.myCurrentTimeline = if (status != null) timelineTracker.getCastTimeline(it) else CastTimeline.EMPTY_CAST_TIMELINE
    }

    val timelineChanged = oldTimeline != this.myCurrentTimeline
    if (timelineChanged) {
      currentMediaItemIndex = fetchCurrentWindowIndex(remoteMediaClient, this.myCurrentTimeline)
      Log.d(tag, "timelineChanged $currentMediaItemIndex")
    }
    return timelineChanged
  }

  private fun fetchCurrentWindowIndex(remoteMediaClient: RemoteMediaClient?, timeline: Timeline): Int {
    if (remoteMediaClient == null) {
      return 0
    }
    var currentWindowIndex = C.INDEX_UNSET
    val currentItem = remoteMediaClient.currentItem
    if (currentItem != null) {
      currentWindowIndex = timeline.getIndexOfPeriod(currentItem.itemId)
    }
    if (currentWindowIndex == C.INDEX_UNSET) {
      // The timeline is empty. Fall back to index 0.
      currentWindowIndex = 0
    }
    return currentWindowIndex
  }

  private fun getMediaStatus():MediaStatus? {
    return remoteMediaClient?.mediaStatus
  }

  @JvmName("setSessionAvailabilityListener1")
  fun setSessionAvailabilityListener(listener: SessionAvailabilityListener) {
    sessionAvailabilityListener = listener
  }

  override fun getApplicationLooper(): Looper {
    return Looper.getMainLooper()
  }

  override fun addListener(listener: Player.Listener) {
    listeners.add(listener)
    Log.d(tag, "addListener player listener $listener")
  }

  override fun removeListener(listener: Player.Listener) {
    listeners.remove(listener)
  }

  override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
  }

  override fun setMediaItems(mediaItems: MutableList<MediaItem>, startWindowIndex: Int, startPositionMs: Long) {
  }

  override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
  }

  override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
  }

  override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
    Log.d(tag, "removeMediaItems called not configured yet $fromIndex to $toIndex")
  }

  override fun getAvailableCommands(): Player.Commands {
    return myAvailableCommands
  }

  override fun prepare() {
  }

  override fun getPlaybackState(): Int {
    return currentPlaybackState
  }

  override fun getPlaybackSuppressionReason(): Int {
    return Player.PLAYBACK_SUPPRESSION_REASON_NONE
  }

  override fun getPlayerError(): PlaybackException? {
    return null
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    setPlayerStateAndNotifyIfChanged(
      playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, playbackState)

    listeners.flushEvents()
    val pendingResult: PendingResult<RemoteMediaClient.MediaChannelResult> = if (playWhenReady) remoteMediaClient!!.play() else remoteMediaClient!!.pause()

    val resultCb = ResultCallback<RemoteMediaClient.MediaChannelResult?> {
      updatePlayerStateAndNotifyIfChanged()
    }

    pendingResult.setResultCallback(resultCb)
  }

  override fun getPlayWhenReady(): Boolean {
    return myPlayWhenReady
  }

  override fun setRepeatMode(repeatMode: Int) {
  }

  override fun getRepeatMode(): Int {
    return Player.REPEAT_MODE_OFF
  }

  override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
  }

  override fun getShuffleModeEnabled(): Boolean {
    return false
  }

  override fun isLoading(): Boolean {
    return false
  }

  override fun seekTo(mediaItemIndex: Int, positionMs: Long, seekCommand: Int, isRepeatingCurrentItem: Boolean) {
    Log.d(tag, "seekTo $mediaItemIndex position $positionMs")

    val resultCb = ResultCallback<RemoteMediaClient.MediaChannelResult?> {
      val statusCode: Int = it.status.statusCode
      if (statusCode != CastStatusCodes.SUCCESS && statusCode != CastStatusCodes.REPLACED) {
        Log.e(tag, "Seek failed. Error code $statusCode")
      }
      if (--pendingSeekCount == 0) {
        currentMediaItemIndex = pendingSeekWindowIndex
        Log.d(tag, "seekTo CB $currentMediaItemIndex")
        pendingSeekWindowIndex = C.INDEX_UNSET
        pendingSeekPositionMs = C.TIME_UNSET

        // Playback state change will send metadata to client and stop seek loading
        listeners.sendEvent(EVENT_PLAYBACK_STATE_CHANGED) { obj: Player.Listener -> obj.onPlaybackStateChanged(currentPlaybackState) }
      }
    }

    val mediaStatus = getMediaStatus()

    // We assume the default position is 0. There is no support for seeking to the default position
    // in RemoteMediaClient.
    val positionMsFinal = if (positionMs != C.TIME_UNSET) positionMs else 0
    if (mediaStatus != null) {
      if (currentMediaItemIndex != mediaItemIndex) {
        Log.d(tag, "seekTo: Changing media item index from $currentMediaItemIndex to $mediaItemIndex")
        remoteMediaClient?.queueJumpToItem(myCurrentTimeline.getPeriod(mediaItemIndex, period).uid as Int, positionMsFinal, JSONObject())?.setResultCallback(resultCb)
      } else {
        Log.d(tag, "seekTo: Same media index seek to position $positionMsFinal")
        val mediaSeekOptions = MediaSeekOptions.Builder().setPosition(positionMsFinal).build()
        remoteMediaClient?.seek(mediaSeekOptions)?.setResultCallback(resultCb)
      }
      val oldPosition = getCurrentPositionInfo()
      pendingSeekCount++
      pendingSeekWindowIndex = mediaItemIndex
      pendingSeekPositionMs = positionMsFinal
      val newPosition = getCurrentPositionInfo()
      listeners.queueEvent(
        EVENT_POSITION_DISCONTINUITY
      ) { listener: Player.Listener ->
        listener.onPositionDiscontinuity(oldPosition, newPosition, DISCONTINUITY_REASON_SEEK)
      }
      if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
        val mediaItem = currentTimeline.getWindow(mediaItemIndex, window).mediaItem
        listeners.queueEvent(
          EVENT_MEDIA_ITEM_TRANSITION
        ) { listener: Player.Listener -> listener.onMediaItemTransition(mediaItem, MEDIA_ITEM_TRANSITION_REASON_SEEK) }
      }
      updateAvailableCommandsAndNotifyIfChanged()
    } else if (pendingSeekCount == 0) {
      Log.w(tag, "seekTo Media Status is null")
      // Playback state change will send metadata to client and stop seek loading
      listeners.sendEvent(EVENT_PLAYBACK_STATE_CHANGED) { obj: Player.Listener -> obj.onPlaybackStateChanged(currentPlaybackState) }
    }
    listeners.flushEvents()
  }

  private fun updateAvailableCommandsAndNotifyIfChanged() {
    val previousAvailableCommands = availableCommands
    myAvailableCommands = Util.getAvailableCommands(this,PERMANENT_AVAILABLE_COMMANDS)
    if (availableCommands != previousAvailableCommands) {
      listeners.queueEvent(
        EVENT_AVAILABLE_COMMANDS_CHANGED
      ) { listener: Listener -> listener.onAvailableCommandsChanged(availableCommands) }
    }
  }

  override fun getSeekBackIncrement(): Long {
    return seekBackIncrementMs
  }

  override fun getSeekForwardIncrement(): Long {
    return seekForwardIncrementMs
  }

  override fun getMaxSeekToPreviousPosition(): Long {
    return C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS
  }

  override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
    val actualPlaybackParameters = PlaybackParameters(playbackParameters.speed)
    setPlaybackParametersAndNotifyIfChanged(actualPlaybackParameters)
    listeners.flushEvents()

    val pendingResult: PendingResult<RemoteMediaClient.MediaChannelResult>? = remoteMediaClient?.setPlaybackRate(actualPlaybackParameters.speed.toDouble(),  /* customData= */null)

    val resultCb = ResultCallback<RemoteMediaClient.MediaChannelResult?> {
      updatePlaybackRateAndNotifyIfChanged()
      listeners.flushEvents()
    }
    pendingResult?.setResultCallback(resultCb)
  }

  override fun getPlaybackParameters(): PlaybackParameters {
    return playbackParameters
  }

  override fun stop() {
    Log.d(tag, "stop CastPlayer")
    currentPlaybackState = Player.STATE_IDLE
    remoteMediaClient?.stop()
  }

  override fun stop(reset: Boolean) {
    stop()
  }

  override fun release() {
    val sessionManager = castContext.sessionManager
    sessionManager.removeSessionManagerListener(statusListener, CastSession::class.java)
    sessionManager.endCurrentSession(false)
  }

  override fun getCurrentTracks(): Tracks {
    return this.myCurrentTracksInfo
  }

  override fun getTrackSelectionParameters(): TrackSelectionParameters {
    return TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
  }

  override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {}

  override fun getMediaMetadata(): MediaMetadata {
 return MediaMetadata.EMPTY
  }

  override fun getPlaylistMetadata(): MediaMetadata {
    return MediaMetadata.EMPTY
  }

  override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {
  }

  override fun getCurrentTimeline(): Timeline {
    return this.myCurrentTimeline
  }

  override fun getCurrentPeriodIndex(): Int {
    return getCurrentMediaItemIndex()
  }

  override fun getCurrentMediaItemIndex(): Int {
    return if (pendingSeekWindowIndex != C.INDEX_UNSET) pendingSeekWindowIndex else currentMediaItemIndex
  }

  override fun getDuration(): Long {
   return contentDuration
  }

  override fun getCurrentPosition(): Long {
    return remoteMediaClient?.approximateStreamPosition ?: 0L
  }

  override fun getBufferedPosition(): Long {
    return currentPosition
  }

  override fun getTotalBufferedDuration(): Long {
   return 0L
  }

  override fun isPlayingAd(): Boolean {
    return false
  }

  override fun getCurrentAdGroupIndex(): Int {
   return 0
  }

  override fun getCurrentAdIndexInAdGroup(): Int {
   return 0
  }

  override fun getContentPosition(): Long {
    return currentPosition
  }

  override fun getContentBufferedPosition(): Long {
    return currentPosition
  }

  override fun getAudioAttributes(): AudioAttributes {
   return AudioAttributes.DEFAULT
  }

  override fun setVolume(audioVolume: Float) {

  }

  override fun getVolume(): Float {
    return 0F
  }

  override fun clearVideoSurface() {
  }

  override fun clearVideoSurface(surface: Surface?) {
  }

  override fun setVideoSurface(surface: Surface?) {
  }

  override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
  }

  override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
  }

  override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
  }

  override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
  }

  override fun setVideoTextureView(textureView: TextureView?) {

  }

  override fun clearVideoTextureView(textureView: TextureView?) {

  }

  override fun getVideoSize(): VideoSize {
   return VideoSize.UNKNOWN
  }

  override fun getSurfaceSize(): Size {
    return Size.UNKNOWN
  }

  override fun getCurrentCues(): CueGroup {
    return CueGroup.EMPTY_TIME_ZERO
  }

  override fun getDeviceInfo(): DeviceInfo {
    return DeviceInfo.UNKNOWN
  }

  override fun getDeviceVolume(): Int {
   return 0
  }

  override fun isDeviceMuted(): Boolean {
   return false
  }

  override fun setDeviceVolume(volume: Int) {

  }

  override fun increaseDeviceVolume() {

  }

  override fun decreaseDeviceVolume() {

  }

  override fun setDeviceMuted(muted: Boolean) {

  }

  inner class StatusListener() : RemoteMediaClient.Callback(), SessionManagerListener<CastSession>, RemoteMediaClient.ProgressListener {
    val TAG = "StatusListener"

    // RemoteMediaClient.ProgressListener implementation.
    override fun onProgressUpdated(progressMs: Long, unusedDurationMs: Long) {
      lastReportedPositionMs = progressMs
    }

    // RemoteMediaClient.Callback implementation.
    override fun onStatusUpdated() {
      Log.d(TAG, "StatusListener status queue items " + remoteMediaClient?.mediaStatus?.queueItemCount)
      if (remoteMediaClient?.playerState == MediaStatus.PLAYER_STATE_IDLE) {
        Log.d(TAG, "StatusListener CastPlayer STATE_IDLE")
      } else if (remoteMediaClient?.playerState == MediaStatus.PLAYER_STATE_BUFFERING) {
        Log.d(TAG, "StatusListener CastPlayer BUFFERING")
      } else if (remoteMediaClient?.playerState == MediaStatus.PLAYER_STATE_LOADING) {
        Log.d(TAG, "StatusListener CastPlayer PLAYER_STATE_LOADING")
      } else if (remoteMediaClient?.playerState == MediaStatus.PLAYER_STATE_PAUSED) {
        Log.d(TAG, "StatusListener CastPlayer PLAYER_STATE_PAUSED")
      } else if (remoteMediaClient?.playerState == MediaStatus.PLAYER_STATE_PLAYING) {
        Log.d(TAG, "StatusListener CastPlayer PLAYER_STATE_PLAYING")
      } else if (remoteMediaClient?.playerState == MediaStatus.PLAYER_STATE_UNKNOWN) {
        Log.d(TAG, "StatusListener CastPlayer PLAYER_STATE_UNKNOWN")
      }

      updateInternalStateAndNotifyIfChanged()
    }

    override fun onMetadataUpdated() {}
    override fun onQueueStatusUpdated() {
      Log.d(TAG, "onQueueStatusUpdated")
      updateTimelineAndNotifyIfChanged()
      listeners.flushEvents()
    }

    override fun onPreloadStatusUpdated() {}
    override fun onSendingRemoteMediaRequest() {}
    override fun onAdBreakStatusUpdated() {}

    // SessionManagerListener implementation.
    override fun onSessionStarted(castSession: CastSession, s: String) {
      Log.d(TAG, "StatusListener onSessionStarted")
      setRemoteMediaClient(castSession.remoteMediaClient)
    }

    override fun onSessionResumed(castSession: CastSession, b: Boolean) {
      setRemoteMediaClient(castSession.remoteMediaClient)
    }

    override fun onSessionEnded(castSession: CastSession, i: Int) {
      Log.d(TAG, "StatusListener onSessionEnded")
      setRemoteMediaClient(null)
    }

    override fun onSessionSuspended(castSession: CastSession, i: Int) {
      setRemoteMediaClient(null)
    }

    override fun onSessionResumeFailed(castSession: CastSession, statusCode: Int) {
      Log.e(
        TAG,
        "Session resume failed. Error code "
          + statusCode)
    }

    override fun onSessionStarting(castSession: CastSession) {
      Log.d(TAG, "StatusListener onSessionStarting")
      // Do nothing.
    }

    override fun onSessionStartFailed(castSession: CastSession, statusCode: Int) {
      Log.e(
        TAG,
        ("Session start failed. Error code "
          + statusCode))
    }

    override fun onSessionEnding(castSession: CastSession) {
      // Do nothing.
    }

    override fun onSessionResuming(castSession: CastSession, s: String) {
      // Do nothing.
    }
  }
}
