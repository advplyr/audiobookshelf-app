package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class HistoryMigrationState {
  NOT_MIGRATED,
  MIGRATING,
  MIGRATED
}

/**
 * Tracks migration of one book's legacy single-blob history into chunked history storage.
 *
 * Only MIGRATED changes read behavior. MIGRATING is diagnostic; a stale MIGRATING record is retried
 * because process death can leave it behind.
 *
 * `migratedAt` starts the retention window for deleting the old blob.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class MediaItemHistoryMigrationRecord(
        var state: HistoryMigrationState = HistoryMigrationState.NOT_MIGRATED,
        var attempts: Int = 0,
        var migratedAt: Long = 0L,
) {
  val isMigrated: Boolean
    get() = state == HistoryMigrationState.MIGRATED
}
