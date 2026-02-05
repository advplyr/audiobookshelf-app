package com.audiobookshelf.app.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.audiobookshelf.app.data.LibraryItemWrapper
import com.audiobookshelf.app.data.PodcastEpisode
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector

class MediaSessionPlaybackPreparer(var playerNotificationService:PlayerNotificationService) : MediaSessionConnector.PlaybackPreparer {
  companion object {
    private const val TAG = "MediaSessionPlaybackPreparer"
  }

  private val tag = TAG
  private val mainHandler = Handler(Looper.getMainLooper())

  override fun onCommand(player: Player, command: String, extras: Bundle?, cb: ResultReceiver?): Boolean {
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
    playerNotificationService.mediaManager.getFirstItem()?.let { li ->
      playerNotificationService.mediaManager.play(li, null, playerNotificationService.getPlayItemRequestPayload(false)) {
        if (it == null) {
          Log.e(tag, "Failed to play library item")
        } else {
          val playbackRate = playerNotificationService.mediaManager.getSavedPlaybackRate()
          mainHandler.post {
            playerNotificationService.preparePlayer(it, playWhenReady, playbackRate)
          }
        }
      }
    }
  }

  override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
    Log.d(tag, "ON PREPARE FROM MEDIA ID $mediaId $playWhenReady")

    val libraryItemWrapper: LibraryItemWrapper?
    var podcastEpisode: PodcastEpisode? = null

    val libraryItemWithEpisode = playerNotificationService.mediaManager.getPodcastWithEpisodeByEpisodeId(mediaId)
    if (libraryItemWithEpisode != null) {
      libraryItemWrapper = libraryItemWithEpisode.libraryItemWrapper
      podcastEpisode = libraryItemWithEpisode.episode
    } else {
      libraryItemWrapper = playerNotificationService.mediaManager.getById(mediaId)
    }

    libraryItemWrapper?.let { li ->
      playerNotificationService.mediaManager.play(li, podcastEpisode, playerNotificationService.getPlayItemRequestPayload(false)) {
        if (it == null) {
         Log.e(tag, "Failed to play library item")
        } else {
          val playbackRate = playerNotificationService.mediaManager.getSavedPlaybackRate()
          mainHandler.post {
            playerNotificationService.preparePlayer(it, playWhenReady, playbackRate)
          }
        }
      }
    }
  }

  override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
    Log.d(tag, "ON PREPARE FROM SEARCH $query")
    playerNotificationService.mediaManager.getFromSearch(query)?.let { li ->
      playerNotificationService.mediaManager.play(li, null, playerNotificationService.getPlayItemRequestPayload(false)) {
        if (it == null) {
         Log.e(tag, "Failed to play library item")
        } else {
          val playbackRate = playerNotificationService.mediaManager.getSavedPlaybackRate()
          mainHandler.post {
            playerNotificationService.preparePlayer(it, playWhenReady, playbackRate)
          }
        }
      }
    }
  }

  override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {
    Log.d(tag, "ON PREPARE FROM URI $uri")
  }
}
