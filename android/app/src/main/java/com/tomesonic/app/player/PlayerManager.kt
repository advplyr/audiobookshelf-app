package com.tomesonic.app.player

import android.content.Context
import android.util.Log
import androidx.media3.*
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.PlayerNotificationManager
import com.tomesonic.app.data.DeviceSettings
import com.tomesonic.app.data.PlaybackSession

/**
 * Manages ExoPlayer and Cast player instances and their lifecycle
 */
class PlayerManager(
    private val context: Context,
    private val deviceSettings: DeviceSettings,
    private val service: PlayerNotificationService
) {
    companion object {
        private const val TAG = "PlayerManager"
    }

    // Player instances
    lateinit var mPlayer: ExoPlayer
        private set
    lateinit var currentPlayer: Player
        private set

    fun initializeExoPlayer() {
        val customLoadControl: LoadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1000 * 20, // 20s min buffer
                    1000 * 45, // 45s max buffer
                    1000 * 5,  // 5s playback start
                    1000 * 20  // 20s playback rebuffer
                )
                .build()

        mPlayer = ExoPlayer.Builder(context)
            .setLoadControl(customLoadControl)
            .setSeekBackIncrementMs(deviceSettings.jumpBackwardsTimeMs)
            .setSeekForwardIncrementMs(deviceSettings.jumpForwardTimeMs)
            .build()

        // Add comprehensive ExoPlayer debug logging
        val eventLogger = EventLogger("ExoPlayerDebug")
        mPlayer.addAnalyticsListener(eventLogger)
        Log.d(TAG, "ExoPlayer EventLogger added for comprehensive internal state tracking")

        mPlayer.setHandleAudioBecomingNoisy(true)
        mPlayer.addListener(PlayerListener(service))

        // Add detailed debug listener
        mPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateString = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                Log.d(TAG, "DEBUG: onPlaybackStateChanged: $stateString")
                Log.d(TAG, "DEBUG: - isPlaying: ${mPlayer.isPlaying}")
                Log.d(TAG, "DEBUG: - playWhenReady: ${mPlayer.playWhenReady}")
                Log.d(TAG, "DEBUG: - mediaItemCount: ${mPlayer.mediaItemCount}")
                Log.d(TAG, "DEBUG: - currentMediaItemIndex: ${mPlayer.currentMediaItemIndex}")
                Log.d(TAG, "DEBUG: - duration: ${mPlayer.duration}")
                Log.d(TAG, "DEBUG: - position: ${mPlayer.currentPosition}")
                Log.d(TAG, "DEBUG: - bufferedPosition: ${mPlayer.bufferedPosition}")
                Log.d(TAG, "DEBUG: - isLoading: ${mPlayer.isLoading}")

                if (playbackState == Player.STATE_READY && mPlayer.playWhenReady && !mPlayer.isPlaying) {
                    Log.w(TAG, "DEBUG: WARNING - Player is READY with playWhenReady=true but isPlaying=false!")
                    Log.w(TAG, "DEBUG: This indicates a potential audio focus or state management issue")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "DEBUG: onIsPlayingChanged: $isPlaying")
                Log.d(TAG, "DEBUG: - playbackState: ${mPlayer.playbackState}")
                Log.d(TAG, "DEBUG: - playWhenReady: ${mPlayer.playWhenReady}")
                Log.d(TAG, "DEBUG: - mediaItemCount: ${mPlayer.mediaItemCount}")
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val reasonString = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                    else -> "UNKNOWN($reason)"
                }
                Log.d(TAG, "DEBUG: onPlayWhenReadyChanged: $playWhenReady, reason: $reasonString")
                Log.d(TAG, "DEBUG: - isPlaying: ${mPlayer.isPlaying}")
                Log.d(TAG, "DEBUG: - playbackState: ${mPlayer.playbackState}")

                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    Log.w(TAG, "DEBUG: Audio focus lost - this may prevent auto-start!")
                }
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                Log.d(TAG, "DEBUG: onIsLoadingChanged: $isLoading")
                Log.d(TAG, "DEBUG: - playbackState: ${mPlayer.playbackState}")
                Log.d(TAG, "DEBUG: - mediaItemCount: ${mPlayer.mediaItemCount}")
                Log.d(TAG, "DEBUG: - currentMediaItemIndex: ${mPlayer.currentMediaItemIndex}")
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                val reasonString = when (reason) {
                    Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                    Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE -> "SOURCE_UPDATE"
                    else -> "UNKNOWN($reason)"
                }
                Log.d(TAG, "DEBUG: onTimelineChanged: windowCount=${timeline.windowCount}, reason=$reasonString")

                for (i in 0 until timeline.windowCount.coerceAtMost(3)) { // Log first 3 items
                    val window = androidx.media3.common.Timeline.Window()
                    timeline.getWindow(i, window)
                    Log.d(TAG, "DEBUG: - Window $i: isSeekable=${window.isSeekable}, durationMs=${window.durationMs}, mediaItem=${window.mediaItem.mediaId}")
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val reasonString = when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                    else -> "UNKNOWN($reason)"
                }
                Log.d(TAG, "DEBUG: onMediaItemTransition: ${mediaItem?.mediaId}, reason: $reasonString")
                Log.d(TAG, "DEBUG: - currentMediaItemIndex: ${mPlayer.currentMediaItemIndex}")
                Log.d(TAG, "DEBUG: - isPlaying: ${mPlayer.isPlaying}")
            }
        })

        val audioAttributes: AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build()

        // Enable automatic audio focus handling with debug logging
        Log.d(TAG, "Setting audio attributes with automatic audio focus handling")
        mPlayer.setAudioAttributes(audioAttributes, true)

        // Log audio attributes state
        Log.d(TAG, "Audio attributes set - Usage: ${audioAttributes.usage}, ContentType: ${audioAttributes.contentType}")

        // Set as current player
        currentPlayer = mPlayer

        Log.d(TAG, "ExoPlayer initialized successfully")
    }

    fun releasePlayer() {
        if (::mPlayer.isInitialized) {
            mPlayer.release()
            Log.d(TAG, "ExoPlayer released")
        }
    }

    // Basic player controls
    fun play() {
        val stateString = when (currentPlayer.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }
        Log.d(TAG, "play() called - isPlaying: ${currentPlayer.isPlaying}, mediaItemCount: ${currentPlayer.mediaItemCount}, playbackState: $stateString (${currentPlayer.playbackState}), playWhenReady: ${currentPlayer.playWhenReady}")
        if (currentPlayer.isPlaying) {
            Log.d(TAG, "Already playing")
            return
        }
        if (currentPlayer.mediaItemCount == 0) {
            Log.e(TAG, "Cannot play - no media items loaded")
            return
        }
        if (currentPlayer.playbackState == Player.STATE_IDLE) {
            Log.w(TAG, "Player is in IDLE state, preparing first")
            currentPlayer.prepare()
        } else if (currentPlayer.playbackState == Player.STATE_ENDED) {
            Log.w(TAG, "Player is in ENDED state, seeking to start")
            currentPlayer.seekTo(0)
        }
        currentPlayer.volume = 1F
        // In Media3, use setPlayWhenReady instead of play()
        currentPlayer.setPlayWhenReady(true)
        Log.d(TAG, "After setPlayWhenReady(true) - isPlaying: ${currentPlayer.isPlaying}, playWhenReady: ${currentPlayer.playWhenReady}")
    }

    fun pause() {
        currentPlayer.setPlayWhenReady(false)
    }

    fun seekToPosition(time: Long) {
        var timeToSeek = time
        Log.d(TAG, "seekPlayer mediaCount = ${currentPlayer.mediaItemCount} | $timeToSeek")

        if (timeToSeek < 0) {
            Log.w(TAG, "seekPlayer invalid time $timeToSeek - setting to 0")
            timeToSeek = 0L
        } else if (timeToSeek > getDuration()) {
            Log.w(TAG, "seekPlayer invalid time $timeToSeek - setting to MAX - 2000")
            timeToSeek = getDuration() - 2000L
        }

        currentPlayer.seekTo(timeToSeek)
    }

    // Player state queries
    fun isPlaying(): Boolean = currentPlayer.isPlaying

    fun getCurrentPosition(): Long {
        return currentPlayer.currentPosition
    }

    fun getDuration(): Long {
        return currentPlayer.duration
    }

    fun getPlaybackSpeed(): Float {
        return currentPlayer.playbackParameters.speed
    }

    fun setPlaybackSpeed(speed: Float) {
        currentPlayer.setPlaybackSpeed(speed)
    }

    // Media item management
    fun getMediaItemCount(): Int = currentPlayer.mediaItemCount

    fun getCurrentMediaItemIndex(): Int = currentPlayer.currentMediaItemIndex

    fun addMediaItems(mediaItems: List<MediaItem>) {
        currentPlayer.addMediaItems(mediaItems)
    }

    fun setMediaItems(mediaItems: List<MediaItem>) {
        currentPlayer.setMediaItems(mediaItems)
    }

    fun seekToMediaItem(index: Int, position: Long = 0) {
        currentPlayer.seekTo(index, position)
    }

    fun prepare() {
        currentPlayer.prepare()
    }

    fun setPlayWhenReady(playWhenReady: Boolean) {
        currentPlayer.playWhenReady = playWhenReady
    }

    /**
     * Debug method to log comprehensive player state
     */
    fun logPlayerState(context: String) {
        val stateString = when (currentPlayer.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN(${currentPlayer.playbackState})"
        }

        Log.d(TAG, "PLAYER_STATE[$context]:")
        Log.d(TAG, "  - playbackState: $stateString")
        Log.d(TAG, "  - isPlaying: ${currentPlayer.isPlaying}")
        Log.d(TAG, "  - playWhenReady: ${currentPlayer.playWhenReady}")
        Log.d(TAG, "  - mediaItemCount: ${currentPlayer.mediaItemCount}")
        Log.d(TAG, "  - currentMediaItemIndex: ${currentPlayer.currentMediaItemIndex}")
        Log.d(TAG, "  - isLoading: ${currentPlayer.isLoading}")
        Log.d(TAG, "  - duration: ${currentPlayer.duration}")
        Log.d(TAG, "  - position: ${currentPlayer.currentPosition}")
        Log.d(TAG, "  - bufferedPosition: ${currentPlayer.bufferedPosition}")
        Log.d(TAG, "  - volume: ${currentPlayer.volume}")
        Log.d(TAG, "  - playbackSpeed: ${currentPlayer.playbackParameters.speed}")

        if (currentPlayer.playbackState == Player.STATE_READY && currentPlayer.playWhenReady && !currentPlayer.isPlaying) {
            Log.w(TAG, "  *** WARNING: Player should be playing but isn't - possible audio focus issue ***")
        }

        // Log current media item details if available
        if (currentPlayer.mediaItemCount > 0) {
            val currentMediaItem = currentPlayer.currentMediaItem
            Log.d(TAG, "  - currentMediaItem: ${currentMediaItem?.mediaId}")
            Log.d(TAG, "  - currentMediaItem URI: ${currentMediaItem?.localConfiguration?.uri}")
        }
    }
}

