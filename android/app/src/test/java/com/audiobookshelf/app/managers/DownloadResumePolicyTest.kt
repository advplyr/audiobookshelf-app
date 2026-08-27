package com.audiobookshelf.app.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadResumePolicyTest {
  @Test
  fun completeKnownFileDoesNotIssueRequest() {
    assertEquals(
            DownloadResumePolicy.InitialAction.COMPLETE,
            DownloadResumePolicy.initialAction(100L, 100L))
  }

  @Test
  fun partialAndUnknownFilesUseRange() {
    assertEquals(
            DownloadResumePolicy.InitialAction.RANGE_DOWNLOAD,
            DownloadResumePolicy.initialAction(25L, 100L))
    assertEquals(
            DownloadResumePolicy.InitialAction.RANGE_DOWNLOAD,
            DownloadResumePolicy.initialAction(25L, 0L))
  }

  @Test
  fun oversizedFileRestartsAndEmptyFileDownloadsFully() {
    assertEquals(
            DownloadResumePolicy.InitialAction.RESTART,
            DownloadResumePolicy.initialAction(101L, 100L))
    assertEquals(
            DownloadResumePolicy.InitialAction.FULL_DOWNLOAD,
            DownloadResumePolicy.initialAction(0L, 100L))
  }

  @Test
  fun parsesUnsatisfiedContentRange() {
    assertEquals(787913771L, DownloadResumePolicy.unsatisfiedRangeSize("bytes */787913771"))
    assertNull(DownloadResumePolicy.unsatisfiedRangeSize("bytes 0-99/100"))
    assertNull(DownloadResumePolicy.unsatisfiedRangeSize(null))
  }
}
