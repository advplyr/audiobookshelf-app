package com.audiobookshelf.app.plugins

import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.data.audioTrack
import com.audiobookshelf.app.data.book
import com.audiobookshelf.app.data.localLibraryItem
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `AbsAudioPlayer` is the Capacitor plugin the whole web UI drives playback through, and it was at
 * 0% before this suite.
 *
 * **Read this before adding tests here.** Twelve of its seventeen `@PluginMethod`s wrap their
 * entire body - the delegation to `PlayerNotificationService` *and* the `call.resolve(...)` - in
 * `Handler(Looper.getMainLooper()).post { ... }`. On the host JVM `Looper.getMainLooper()` returns
 * `null` and `Handler(null).post {}` returns `false` without ever running the runnable, so those
 * bodies are unreachable here: `pausePlayer`, `playPlayer`, `playPause`, `seek`, `seekForward`,
 * `seekBackward`, `setPlaybackSpeed`, `closePlayback`, `getCurrentTime`, `setSleepTimer`,
 * `increaseSleepTime` and `decreaseSleepTime` all *return without throwing* while doing nothing at
 * all. A test asserting `verify { playerNotificationService.pause() }` after `pausePlayer(call)`
 * would fail; one asserting only "did not throw" would pass vacuously forever. Neither is worth
 * writing - this is the same `Handler`-dispatch blocker `AbsDatabase.updateDeviceSettings` and
 * `MediaSessionCallback`'s click-count path hit in earlier passes, and it is recorded in the
 * blocked list in `TESTING.md` §6.
 *
 * What *is* reachable, and is what this suite covers:
 * * the three methods with no `Handler` wrapper at all (`getSleepTimerTime`, `getIsCastAvailable`,
 *   `requestSession`), plus `cancelSleepTimer`, whose `resolve` sits outside its post;
 * * every validation branch `prepareLibraryItem` evaluates **before** it posts - which is where
 *   all of its untrusted-input handling lives.
 *
 * `mainActivity` and `apiHandler` are `private lateinit var`s only assigned by `load()` (which
 * needs a real Capacitor `Bridge`); they are injected with `AbsTestEnvironment.injectField`.
 * `playerNotificationService` is a public `lateinit var` and is assigned directly.
 */
class AbsAudioPlayerTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var plugin: AbsAudioPlayer
  private lateinit var pns: PlayerNotificationService
  private lateinit var db: DbManager

  @Before
  fun setUp() {
    AbsTestEnvironment.mockLocalFileStatics()
    db = DbManager()
    pns = mockk(relaxed = true)
    plugin = AbsAudioPlayer()
    plugin.playerNotificationService = pns
    AbsTestEnvironment.injectField(plugin, "mainActivity", mockk<MainActivity>(relaxed = true))
    AbsTestEnvironment.injectField(plugin, "apiHandler", mockk<com.audiobookshelf.app.server.ApiHandler>(relaxed = true))
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  /** A relaxed `PluginCall` whose two-arg getters return the given params, else their default. */
  private fun pluginCall(vararg params: Pair<String, String>): PluginCall {
    val call = mockk<PluginCall>(relaxed = true)
    every { call.getString(any(), any()) } answers { secondArg() }
    params.forEach { (key, value) -> every { call.getString(key, "") } returns value }
    return call
  }

  /** Runs [action] and returns whatever the call resolved with, or null if it never resolved. */
  private fun resolutionOf(call: PluginCall, action: () -> Unit): Resolution {
    val captured = slot<JSObject>()
    var resolveCount = 0
    every { call.resolve(capture(captured)) } answers { resolveCount++ }
    every { call.resolve() } answers { resolveCount++ }
    action()
    return Resolution(resolveCount, if (captured.isCaptured) captured.captured else null)
  }

  private data class Resolution(val count: Int, val body: JSObject?)

  private fun podcastItem(id: String, episodes: MutableList<PodcastEpisode>) =
          localLibraryItem(
                  id = id,
                  mediaType = "podcast",
                  media =
                          Podcast(
                                  metadata = PodcastMetadata("Podcast", "Author", null, mutableListOf(), false),
                                  coverPath = null,
                                  tags = mutableListOf(),
                                  episodes = episodes,
                                  autoDownloadEpisodes = false,
                                  numEpisodes = episodes.size
                          )
          )

  private fun episode(id: String, track: AudioTrack? = audioTrack()) =
          PodcastEpisode(
                  id, 1, null, null, "Episode $id", null, null, null, null, null, track, null,
                  track?.duration, null, id, id
          )

  // --- Methods that do not dispatch through Handler -------------------------------------------

  @Test
  fun `getIsCastAvailable resolves false until a cast device has been discovered`() {
    val call = pluginCall()

    val resolution = resolutionOf(call) { plugin.getIsCastAvailable(call) }

    assertEquals(1, resolution.count)
    assertEquals(false, resolution.body!!.getBoolean("value"))
  }

  @Test
  fun `getSleepTimerTime resolves with the value the sleep timer manager reports`() {
    every { pns.sleepTimerManager.getSleepTimerTime() } returns 90_000L
    val call = pluginCall()

    val resolution = resolutionOf(call) { plugin.getSleepTimerTime(call) }

    assertEquals(90_000L, resolution.body!!.getLong("value"))
  }

  @Test
  fun `requestSession resolves exactly once even when no cast manager was initialized`() {
    // initCastManager() only runs inside load(); castManager stays null here, which is also the
    // real state on a device with no Google Play services. The method must not NPE on it.
    val call = pluginCall()

    val resolution = resolutionOf(call) { plugin.requestSession(call) }

    assertEquals(1, resolution.count)
    assertNull(plugin.castManager)
  }

  /**
   * Characterization of an asymmetry worth knowing about: `cancelSleepTimer` puts only the
   * delegation inside `Handler(...).post {}` and calls `call.resolve()` outside it
   * (`AbsAudioPlayer.kt:419-425`), unlike its eleven siblings which resolve inside the post. So it
   * reports success to the web UI before - and, on the host JVM, without - the cancel happening.
   * On a real device the post does run, so this is a sequencing quirk rather than a bug; it is
   * pinned because it is the reason this one method is observable here at all.
   */
  @Test
  fun `cancelSleepTimer resolves without waiting for the cancel to be dispatched`() {
    val call = pluginCall()

    val resolution = resolutionOf(call) { plugin.cancelSleepTimer(call) }

    assertEquals(1, resolution.count)
    verify(exactly = 0) { pns.sleepTimerManager.cancelSleepTimer() } // Handler body never runs here
  }

  // --- prepareLibraryItem: the validation it performs before dispatching -----------------------

  @Test
  fun `prepareLibraryItem rejects a call carrying no library item id`() {
    val call = pluginCall("libraryItemId" to "", "episodeId" to "")

    val resolution = resolutionOf(call) { plugin.prepareLibraryItem(call) }

    assertEquals("Invalid request", resolution.body!!.getString("error"))
  }

  @Test
  fun `prepareLibraryItem reports a podcast episode that is not on the device`() {
    db.saveLocalLibraryItem(podcastItem("local-pod", mutableListOf(episode("ep-1"))))
    val call = pluginCall("libraryItemId" to "local-pod", "episodeId" to "ep-missing")

    val resolution = resolutionOf(call) { plugin.prepareLibraryItem(call) }

    assertEquals("Podcast episode not found", resolution.body!!.getString("error"))
  }

  @Test
  fun `prepareLibraryItem reports missing audio files instead of starting playback`() {
    // Tracks whose metadata is null fail hasTracks' check - the state left behind when a download
    // recorded a track but the file never landed.
    db.saveLocalLibraryItem(
            localLibraryItem(id = "local-book", media = book(mutableListOf(audioTrack())))
    )
    val call = pluginCall("libraryItemId" to "local-book", "episodeId" to "")

    val resolution = resolutionOf(call) { plugin.prepareLibraryItem(call) }

    assertTrue(
            resolution.body!!.getString("error")!!.contains("No audio files found on device")
    )
  }

  /**
   * **Newly found this pass.** Related to the local-media-lifecycle cluster
   * ([#1680](https://github.com/advplyr/audiobookshelf-app/issues/1680),
   * [#1630](https://github.com/advplyr/audiobookshelf-app/issues/1630)): a downloaded item that was
   * moved or removed leaves the web UI holding an id whose local record is gone.
   *
   * Inputs: `prepareLibraryItem` with `libraryItemId = "local-gone"`, no such record in the local
   * database.
   *
   * Expected: the plugin call resolves - with an error, like every other failure branch in this
   * method does.
   *
   * Observed: it resolves **zero** times. `DeviceManager.dbManager.getLocalLibraryItem(id)?.let {
   * ... }` (`AbsAudioPlayer.kt:222`) simply evaluates to null, the `if` branch ends, and the
   * method returns having called neither `call.resolve` nor `call.reject`. A Capacitor call that
   * is never resolved leaves the JS promise pending forever, so the play button spins and never
   * recovers - the user-visible symptom in those reports.
   *
   * Note the sibling branch three lines down handles its failure correctly ("No audio files found
   * on device. Download book again to fix."), so this is a missing `else`, not a design choice.
   */
  @Test
  fun `prepareLibraryItem must resolve when the local library item record is gone`() {
    val call = pluginCall("libraryItemId" to "local-gone", "episodeId" to "")

    val resolution = resolutionOf(call) { plugin.prepareLibraryItem(call) }

    assertEquals(
            "a plugin call that never resolves leaves the web UI's promise pending forever",
            1,
            resolution.count
    )
  }

  /**
   * **Newly found this pass.** The same method's other unguarded input.
   *
   * Inputs: `libraryItemId` of a local **book**, with a non-empty `episodeId` - the shape the web
   * UI sends when a stale podcast route is replayed against an item that is not a podcast.
   *
   * Expected: an error resolution, as for the "Podcast episode not found" case.
   *
   * Observed: `it.media as Podcast` (`AbsAudioPlayer.kt:225`) is an unchecked cast on a `Book`,
   * throwing `ClassCastException` out of the plugin method.
   */
  @Test
  fun `prepareLibraryItem must not crash when an episode id is sent for a book`() {
    db.saveLocalLibraryItem(
            localLibraryItem(id = "local-book", media = book(mutableListOf(audioTrack())))
    )
    val call = pluginCall("libraryItemId" to "local-book", "episodeId" to "ep-1")

    var thrown: Throwable? = null
    val resolution =
            try {
              resolutionOf(call) { plugin.prepareLibraryItem(call) }
            } catch (e: Throwable) {
              thrown = e
              Resolution(0, null)
            }

    assertNull("an episode id sent for a book must not escape as an exception", thrown)
    assertEquals(1, resolution.count)
  }

  @Test
  fun `prepareLibraryItem treats a non-local item id as a server request`() {
    // Ids not starting with "local" take the server branch, which reads the play request payload
    // from the notification service before posting. That read is the only observable part of the
    // branch on the host JVM; everything after it is inside the Handler post.
    val call = pluginCall("libraryItemId" to "server-item", "episodeId" to "")

    resolutionOf(call) { plugin.prepareLibraryItem(call) }

    verify(exactly = 1) { pns.getPlayItemRequestPayload(false) }
  }

  @Test
  fun `prepareLibraryItem does not treat a local id as a server request`() {
    // The mirror of the above: the local branch must never build a server play payload, or a
    // downloaded book would hit the network on every play.
    db.saveLocalLibraryItem(
            localLibraryItem(id = "local-book", media = book(mutableListOf(audioTrack())))
    )
    val call = pluginCall("libraryItemId" to "local-book", "episodeId" to "")

    resolutionOf(call) { plugin.prepareLibraryItem(call) }

    verify(exactly = 0) { pns.getPlayItemRequestPayload(any()) }
  }
}
