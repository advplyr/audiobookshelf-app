package com.audiobookshelf.app

import com.audiobookshelf.app.plugins.BulkPodcastManifestCache
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BulkPodcastManifestCacheTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun consumesOnlyTheRequestedManifestOnce() {
    val cache = BulkPodcastManifestCache(temporary.root)
    val first = cache.stage("""{"episode":"épisode 1"}""")
    val second = cache.stage("""{"episode":"episode 2"}""")
    assertEquals("""{"episode":"épisode 1"}""", cache.consume(first))
    assertThrows(IOException::class.java) { cache.consume(first) }
    assertEquals("""{"episode":"episode 2"}""", cache.consume(second))
    assertEquals(0, temporary.root.listFiles()!!.size)
  }

  @Test
  fun discardsEvictedAndUnconsumedManifestsOnClose() {
    val cache = BulkPodcastManifestCache(temporary.root)
    val evicted = cache.stage("evicted")
    val abandoned = cache.stage("abandoned")
    File(temporary.root, evicted).delete()
    assertThrows(IOException::class.java) { cache.consume(evicted) }
    cache.close()
    assertFalse(File(temporary.root, abandoned).exists())
    assertThrows(IllegalStateException::class.java) { cache.stage("late request") }
  }

  @Test
  fun enforcesByteBudgetAndReusesSpaceAfterDiscard() {
    val cache = BulkPodcastManifestCache(temporary.root, byteLimit = 4, freeSpaceReserve = 0)
    val first = cache.stage("éé")
    assertThrows(IOException::class.java) { cache.stage("x") }
    cache.discard(listOf(first))
    assertEquals("next", cache.consume(cache.stage("next")))
  }

  @Test
  fun prunesPriorProcessFilesWithoutRemovingAnotherLiveBridgesData() {
    val orphan = File(temporary.root, "old-process-manifest").apply { writeText("old") }
    val first = BulkPodcastManifestCache(temporary.root)
    assertFalse(orphan.exists())
    val active = first.stage("active")
    val second = BulkPodcastManifestCache(temporary.root)
    assertEquals("active", first.consume(active))
    second.close()
  }
}
