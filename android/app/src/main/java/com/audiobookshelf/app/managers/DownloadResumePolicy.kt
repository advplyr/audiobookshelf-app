package com.audiobookshelf.app.managers

internal object DownloadResumePolicy {
  enum class InitialAction { COMPLETE, RESTART, FULL_DOWNLOAD, RANGE_DOWNLOAD }

  fun initialAction(existingBytes: Long, expectedSize: Long): InitialAction =
          when {
            expectedSize > 0L && existingBytes == expectedSize -> InitialAction.COMPLETE
            expectedSize > 0L && existingBytes > expectedSize -> InitialAction.RESTART
            existingBytes > 0L -> InitialAction.RANGE_DOWNLOAD
            else -> InitialAction.FULL_DOWNLOAD
          }

  fun unsatisfiedRangeSize(contentRange: String?): Long? {
    if (contentRange == null) return null
    return UNSATISFIED_CONTENT_RANGE.matchEntire(contentRange)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
  }

  private val UNSATISFIED_CONTENT_RANGE = Regex("bytes \\*/(\\d+)")
}
