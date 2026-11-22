package com.audiobookshelf.app.media

object MediaProgressConflictResolver {
  enum class SyncDirection {
    LOCAL_TO_SERVER,
    SERVER_TO_LOCAL,
    NONE
  }

  fun resolveAudioProgress(
          localCurrentTime: Double,
          localIsFinished: Boolean,
          localLastUpdate: Long,
          serverCurrentTime: Double,
          serverIsFinished: Boolean,
          serverLastUpdate: Long
  ): SyncDirection {
    if (localIsFinished != serverIsFinished) {
      return when {
        localLastUpdate > serverLastUpdate -> SyncDirection.LOCAL_TO_SERVER
        serverLastUpdate > localLastUpdate -> SyncDirection.SERVER_TO_LOCAL
        else -> SyncDirection.NONE
      }
    }

    return when {
      localCurrentTime > serverCurrentTime -> SyncDirection.LOCAL_TO_SERVER
      serverCurrentTime > localCurrentTime -> SyncDirection.SERVER_TO_LOCAL
      else -> SyncDirection.NONE
    }
  }
}
