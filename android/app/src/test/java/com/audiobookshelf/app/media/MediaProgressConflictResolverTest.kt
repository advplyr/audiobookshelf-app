package com.audiobookshelf.app.media

import com.audiobookshelf.app.media.MediaProgressConflictResolver.SyncDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaProgressConflictResolverTest {
  @Test
  fun keepsFurtherLocalProgress() {
    val result = MediaProgressConflictResolver.resolveAudioProgress(
      localCurrentTime = 900.0,
      localIsFinished = false,
      localLastUpdate = 100L,
      serverCurrentTime = 300.0,
      serverIsFinished = false,
      serverLastUpdate = 200L
    )

    assertEquals(SyncDirection.LOCAL_TO_SERVER, result)
  }

  @Test
  fun acceptsFurtherServerProgress() {
    val result = MediaProgressConflictResolver.resolveAudioProgress(
      localCurrentTime = 300.0,
      localIsFinished = false,
      localLastUpdate = 200L,
      serverCurrentTime = 900.0,
      serverIsFinished = false,
      serverLastUpdate = 100L
    )

    assertEquals(SyncDirection.SERVER_TO_LOCAL, result)
  }

  @Test
  fun keepsRecentLocalFinishedStateWhenCurrentTimeWasReset() {
    val result = MediaProgressConflictResolver.resolveAudioProgress(
      localCurrentTime = 0.0,
      localIsFinished = true,
      localLastUpdate = 200L,
      serverCurrentTime = 900.0,
      serverIsFinished = false,
      serverLastUpdate = 100L
    )

    assertEquals(SyncDirection.LOCAL_TO_SERVER, result)
  }

  @Test
  fun keepsARecentLocalRestartOfFinishedMedia() {
    val result = MediaProgressConflictResolver.resolveAudioProgress(
      localCurrentTime = 0.0,
      localIsFinished = false,
      localLastUpdate = 200L,
      serverCurrentTime = 0.0,
      serverIsFinished = true,
      serverLastUpdate = 100L
    )

    assertEquals(SyncDirection.LOCAL_TO_SERVER, result)
  }

  @Test
  fun skipsEqualProgress() {
    val result = MediaProgressConflictResolver.resolveAudioProgress(
      localCurrentTime = 900.0,
      localIsFinished = false,
      localLastUpdate = 100L,
      serverCurrentTime = 900.0,
      serverIsFinished = false,
      serverLastUpdate = 200L
    )

    assertEquals(SyncDirection.NONE, result)
  }
}
