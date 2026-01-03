package com.audiobookshelf.app.dlna

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.Player.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.text.CueGroup
import com.google.android.exoplayer2.trackselection.TrackSelectionParameters
import com.google.android.exoplayer2.util.Clock
import com.google.android.exoplayer2.util.FlagSet
import com.google.android.exoplayer2.util.ListenerSet
import com.google.android.exoplayer2.util.Size
import com.google.android.exoplayer2.video.VideoSize

class DlnaPlayer(
    private val dlnaManager: DlnaManager
) : BasePlayer(), DlnaCallback {
    private val tag = "DlnaPlayer"

    private var myPlayWhenReady = false
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var currentPlaybackState = STATE_IDLE
    private var myCurrentTimeline: Timeline = DlnaTimeline.EMPTY

    private var currentMediaItems: List<MediaItem> = mutableListOf()
    private var currentMediaItemIndex = 0
    private var currentTrackDurationsMs: List<Long> = emptyList()

    private var lastReportedPositionMs = 0L
    private var lastReportedDurationMs = 0L
    private var cachedVolume = 0

    var sessionAvailabilityListener: DlnaSessionAvailabilityListener? = null
    var trackProvider: TrackProvider? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    val listeners: ListenerSet<Listener> = ListenerSet(
        Looper.getMainLooper(),
        Clock.DEFAULT
    ) { listener: Listener, flags: FlagSet? ->
        listener.onEvents(this, Events(flags ?: FlagSet.Builder().build()))
    }

    private val PERMANENT_AVAILABLE_COMMANDS: Commands = Commands.Builder()
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
            COMMAND_GET_TRACKS
        )
        .build()

    private var myAvailableCommands: Commands = Commands.Builder()
        .addAll(PERMANENT_AVAILABLE_COMMANDS)
        .build()

    interface DlnaSessionAvailabilityListener {
        fun onDlnaSessionAvailable()
        fun onDlnaSessionUnavailable()
    }

    interface TrackProvider {
        fun getTrackInfo(trackIndex: Int): TrackInfo?
    }

    data class TrackInfo(
        val mediaUrl: String,
        val metadata: String?
    )

    init {
        dlnaManager.setCallback(this)
    }

    fun load(
        mediaItems: List<MediaItem>,
        trackDurationsMs: List<Long>,
        startIndex: Int,
        startTime: Long,
        playWhenReady: Boolean,
        playbackRate: Float,
        mediaUrl: String,
        metadata: String?
    ) {
        Log.d(tag, "Load called with ${mediaItems.size} items, startIndex=$startIndex, startTime=$startTime, playWhenReady=$playWhenReady")

        currentMediaItems = mediaItems
        currentTrackDurationsMs = trackDurationsMs
        currentMediaItemIndex = startIndex
        myCurrentTimeline = DlnaTimeline(mediaItems, trackDurationsMs)

        setPlayerStateAndNotifyIfChanged(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, STATE_BUFFERING)

        dlnaManager.play(mediaUrl, metadata) { success ->
            if (success) {
                Log.d(tag, "DLNA play successful, setting playWhenReady=true and state=READY")
                setPlayerStateAndNotifyIfChanged(true, PLAY_WHEN_READY_CHANGE_REASON_REMOTE, STATE_READY)
                if (startTime > 0) {
                    dlnaManager.seek(startTime)
                }

                preloadNextTrack()
            } else {
                Log.e(tag, "DLNA play failed")
                setPlayerStateAndNotifyIfChanged(false, PLAY_WHEN_READY_CHANGE_REASON_REMOTE, STATE_IDLE)
            }
        }
    }

    private fun preloadNextTrack() {
        if (currentMediaItemIndex + 1 < currentMediaItems.size) {
            val nextIndex = currentMediaItemIndex + 1
            val nextTrackInfo = trackProvider?.getTrackInfo(nextIndex)
            if (nextTrackInfo != null) {
                Log.d(tag, "Preloading next track (index $nextIndex): ${nextTrackInfo.mediaUrl}")
                dlnaManager.setNextTrack(nextTrackInfo.mediaUrl, nextTrackInfo.metadata) { success ->
                    if (success) {
                        Log.d(tag, "Next track preloaded successfully")
                    } else {
                        Log.w(tag, "Failed to preload next track (device may not support SetNextAVTransportURI)")
                    }
                }
            } else {
                Log.w(tag, "No track provider available for preloading")
            }
        } else {
            Log.d(tag, "No next track to preload (last track)")
        }
    }

    private fun setPlayerStateAndNotifyIfChanged(
        playWhenReady: Boolean,
        @PlayWhenReadyChangeReason playWhenReadyChangeReason: Int,
        @State playbackState: Int
    ) {
        val wasPlaying = this.currentPlaybackState == STATE_READY && this.myPlayWhenReady
        val playWhenReadyChanged = this.myPlayWhenReady != playWhenReady
        val playbackStateChanged = this.currentPlaybackState != playbackState

        if (playWhenReadyChanged || playbackStateChanged) {
            this.currentPlaybackState = playbackState
            this.myPlayWhenReady = playWhenReady

            if (playbackStateChanged) {
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
                listeners.queueEvent(EVENT_IS_PLAYING_CHANGED) {
                    it.onIsPlayingChanged(isPlaying)
                }
            }
        }
        listeners.flushEvents()
    }

    override fun onDevicesUpdated(devices: List<DlnaDevice>) {
        Log.d(tag, "Devices updated: ${devices.size}")
    }

    override fun onDeviceConnected(device: DlnaDevice) {
        Log.d(tag, "Device connected: ${device.name}")
        sessionAvailabilityListener?.onDlnaSessionAvailable()
    }

    override fun onDeviceDisconnected() {
        Log.d(tag, "Device disconnected")
        sessionAvailabilityListener?.onDlnaSessionUnavailable()
        setPlayerStateAndNotifyIfChanged(false, PLAY_WHEN_READY_CHANGE_REASON_REMOTE, STATE_IDLE)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        Log.d(tag, "Playback state changed: $isPlaying")
        setPlayerStateAndNotifyIfChanged(
            isPlaying,
            PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            if (isPlaying) STATE_READY else STATE_IDLE
        )
    }

    override fun onPositionUpdate(positionMs: Long, durationMs: Long) {
        lastReportedPositionMs = positionMs
        lastReportedDurationMs = durationMs
    }

    override fun onTrackEnded() {
        Log.d(tag, "Track ended, current index: $currentMediaItemIndex")

        if (currentMediaItemIndex + 1 < currentMediaItems.size) {
            currentMediaItemIndex++
            Log.d(tag, "Advanced to track ${currentMediaItemIndex + 1}/${currentMediaItems.size}")
            val mediaItem = currentMediaItems.getOrNull(currentMediaItemIndex)
            listeners.queueEvent(EVENT_MEDIA_ITEM_TRANSITION) { listener ->
                listener.onMediaItemTransition(mediaItem, MEDIA_ITEM_TRANSITION_REASON_AUTO)
            }
            listeners.flushEvents()

            preloadNextTrack()
        } else {
            Log.d(tag, "Reached end of playlist")
            setPlayerStateAndNotifyIfChanged(false, PLAY_WHEN_READY_CHANGE_REASON_REMOTE, STATE_ENDED)
        }
    }

    override fun onError(message: String) {
        Log.e(tag, "Error: $message")
    }

    override fun getApplicationLooper(): Looper = Looper.getMainLooper()

    override fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {}

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startWindowIndex: Int, startPositionMs: Long) {}

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {}

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {}

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {}

    override fun getAvailableCommands(): Commands = myAvailableCommands

    override fun prepare() {}

    override fun getPlaybackState(): Int = currentPlaybackState

    override fun getPlaybackSuppressionReason(): Int = PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getPlayerError(): PlaybackException? = null

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            dlnaManager.resume()
        } else {
            dlnaManager.pause()
        }
        setPlayerStateAndNotifyIfChanged(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, currentPlaybackState)
    }

    override fun getPlayWhenReady(): Boolean = myPlayWhenReady

    override fun setRepeatMode(repeatMode: Int) {}

    override fun getRepeatMode(): Int = REPEAT_MODE_OFF

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {}

    override fun getShuffleModeEnabled(): Boolean = false

    override fun isLoading(): Boolean = false

    override fun seekTo(mediaItemIndex: Int, positionMs: Long, seekCommand: Int, isRepeatingCurrentItem: Boolean) {
        Log.d(tag, "seekTo mediaItemIndex=$mediaItemIndex, positionMs=$positionMs, currentIndex=$currentMediaItemIndex")

        if (mediaItemIndex != currentMediaItemIndex) {
            Log.d(tag, "Cross-chapter seek detected, loading new track at index $mediaItemIndex")

            val trackInfo = trackProvider?.getTrackInfo(mediaItemIndex)
            if (trackInfo == null) {
                Log.e(tag, "Cannot seek to track $mediaItemIndex - no track provider or track info unavailable")
                return
            }

            currentMediaItemIndex = mediaItemIndex
            val mediaItem = currentMediaItems.getOrNull(mediaItemIndex)
            listeners.queueEvent(EVENT_MEDIA_ITEM_TRANSITION) { listener ->
                listener.onMediaItemTransition(mediaItem, MEDIA_ITEM_TRANSITION_REASON_SEEK)
            }
            listeners.flushEvents()

            setPlayerStateAndNotifyIfChanged(myPlayWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, STATE_BUFFERING)

            dlnaManager.play(trackInfo.mediaUrl, trackInfo.metadata) { success ->
                if (success) {
                    Log.d(tag, "New track loaded, seeking to position $positionMs")
                    setPlayerStateAndNotifyIfChanged(true, PLAY_WHEN_READY_CHANGE_REASON_REMOTE, STATE_READY)
                    if (positionMs > 0) {
                        lastReportedPositionMs = positionMs
                        dlnaManager.seek(positionMs)
                    }
                    preloadNextTrack()
                } else {
                    Log.e(tag, "Failed to load track at index $mediaItemIndex")
                    setPlayerStateAndNotifyIfChanged(false, PLAY_WHEN_READY_CHANGE_REASON_REMOTE, STATE_IDLE)
                }
            }
        } else {
            Log.d(tag, "Same-chapter seek, seeking to position $positionMs")
            lastReportedPositionMs = positionMs
            dlnaManager.seek(positionMs)
        }
    }

    override fun getSeekBackIncrement(): Long = 10000L

    override fun getSeekForwardIncrement(): Long = 30000L

    override fun getMaxSeekToPreviousPosition(): Long = C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        listeners.queueEvent(EVENT_PLAYBACK_PARAMETERS_CHANGED) { listener ->
            listener.onPlaybackParametersChanged(playbackParameters)
        }
        listeners.flushEvents()
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun stop() {
        Log.d(tag, "stop DlnaPlayer")
        dlnaManager.stop()
        currentPlaybackState = STATE_IDLE
    }

    @Deprecated("Use stop() instead", ReplaceWith("stop()"))
    override fun stop(reset: Boolean) {
        stop()
    }

    override fun release() {
        dlnaManager.disconnect()
    }

    override fun getCurrentTracks(): Tracks = Tracks.EMPTY

    override fun getTrackSelectionParameters(): TrackSelectionParameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {}

    override fun getMediaMetadata(): MediaMetadata = MediaMetadata.EMPTY

    override fun getPlaylistMetadata(): MediaMetadata = MediaMetadata.EMPTY

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {}

    override fun getCurrentTimeline(): Timeline = myCurrentTimeline

    override fun getCurrentPeriodIndex(): Int = currentMediaItemIndex

    override fun getCurrentMediaItemIndex(): Int = currentMediaItemIndex

    override fun getDuration(): Long = lastReportedDurationMs

    override fun getCurrentPosition(): Long = lastReportedPositionMs

    override fun getBufferedPosition(): Long = lastReportedPositionMs

    override fun getTotalBufferedDuration(): Long = 0L

    override fun isPlayingAd(): Boolean = false

    override fun getCurrentAdGroupIndex(): Int = 0

    override fun getCurrentAdIndexInAdGroup(): Int = 0

    override fun getContentPosition(): Long = currentPosition

    override fun getContentBufferedPosition(): Long = currentPosition

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun setVolume(audioVolume: Float) {
        cachedVolume = (audioVolume * 100).toInt()
        // Do not push to device — this is the ExoPlayer audio stream volume, not device volume.
        // Device volume is managed via setDeviceVolume() after fetching actual device volume.
    }

    override fun getVolume(): Float = cachedVolume / 100f

    override fun clearVideoSurface() {}

    override fun clearVideoSurface(surface: Surface?) {}

    override fun setVideoSurface(surface: Surface?) {}

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {}

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {}

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {}

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {}

    override fun setVideoTextureView(textureView: TextureView?) {}

    override fun clearVideoTextureView(textureView: TextureView?) {}

    override fun getVideoSize(): VideoSize = VideoSize.UNKNOWN

    override fun getSurfaceSize(): Size = Size.UNKNOWN

    override fun getCurrentCues(): CueGroup = CueGroup.EMPTY_TIME_ZERO

    override fun getDeviceInfo(): DeviceInfo = DeviceInfo.UNKNOWN

    override fun getDeviceVolume(): Int = cachedVolume

    override fun isDeviceMuted(): Boolean = false

    override fun setDeviceVolume(volume: Int) {
        cachedVolume = volume
        dlnaManager.setVolume(volume)
    }

    override fun increaseDeviceVolume() {
        val target = (cachedVolume + 5).coerceAtMost(100)
        cachedVolume = target
        dlnaManager.setVolume(target)
    }

    override fun decreaseDeviceVolume() {
        val target = (cachedVolume - 5).coerceAtLeast(0)
        cachedVolume = target
        dlnaManager.setVolume(target)
    }

    override fun setDeviceMuted(muted: Boolean) {
        dlnaManager.setVolume(if (muted) 0 else cachedVolume)
    }
}
