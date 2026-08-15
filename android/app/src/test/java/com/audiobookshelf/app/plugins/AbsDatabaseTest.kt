package com.audiobookshelf.app.plugins

import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.data.book
import com.audiobookshelf.app.data.localLibraryItem
import com.audiobookshelf.app.data.playbackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.support.AbsSingletonRule
import com.audiobookshelf.app.support.AbsTestEnvironment
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `AbsDatabase` is the app's biggest Capacitor plugin (377 lines, 0% before this suite) and its
 * main untrusted-input boundary. These tests cover every `@PluginMethod` that does not depend on
 * the plugin's `lateinit` fields (`mainActivity`/`apiHandler`/`secureStorage`, only set by
 * `load()`, which requires a real Capacitor `Bridge` and is out of scope here).
 *
 * `updateDeviceSettings` is a deliberate exception even though it does not touch `secureStorage`:
 * it wraps its entire body (including `call.resolve(...)`) in
 * `Handler(Looper.getMainLooper()).post { ... }`. `Looper.getMainLooper()` returns `null` under
 * the mockable `android.jar` and `Handler(null).post {}` returns `false` without ever running the
 * runnable (spike-verified) - so `call.resolve` would never fire and any test awaiting it would
 * hang until timeout. This is the same `Handler`-dispatch blocker `MediaSessionCallback`'s
 * click-count path hit in a prior pass, not a gap in this suite.
 *
 * Two gotchas verified in a throwaway spike before writing these (see
 * `TESTING.md` §8): production calls the **two-arg**
 * `call.getString(name, default)`, and a missing record resolves via the **no-arg**
 * `call.resolve()`. Stubbing only the one-arg forms silently does nothing.
 */
class AbsDatabaseTest {
  @get:Rule val absEnvironment = AbsSingletonRule()

  private lateinit var db: DbManager
  private lateinit var plugin: AbsDatabase

  @Before
  fun setUp() {
    // setCurrentServerConnectionConfig computes a new config's id via DeviceManager.getBase64Id,
    // which needs Base64.encodeToString stubbed (see TESTING.md §6.1 - this is the
    // static mock that goes inert if tests are added here) - harmless for the other tests.
    AbsTestEnvironment.mockLocalFileStatics()
    db = DbManager()
    plugin = AbsDatabase()
  }

  @After
  fun tearDown() {
    io.mockk.unmockkAll()
  }

  /** A relaxed `PluginCall` whose two-arg `getString` returns the given params, else its default. */
  private fun pluginCall(vararg params: Pair<String, String>): PluginCall {
    val call = mockk<PluginCall>(relaxed = true)
    every { call.getString(any(), any()) } answers { secondArg() }
    params.forEach { (key, value) -> every { call.getString(key, "") } returns value }
    return call
  }

  /** Stubs `resolve`/`resolve(JSObject)` to release a latch, runs [action], and awaits it. */
  private fun awaitResolve(call: PluginCall, action: () -> Unit): JSObject? {
    val latch = CountDownLatch(1)
    val captured = slot<JSObject>()
    every { call.resolve(capture(captured)) } answers { latch.countDown() }
    every { call.resolve() } answers { latch.countDown() }
    action()
    assertTrue("plugin call never resolved", latch.await(5, TimeUnit.SECONDS))
    return if (captured.isCaptured) captured.captured else null
  }

  @Test
  fun `getDeviceData resolves with the persisted device data`() {
    val call = pluginCall()

    val body = awaitResolve(call) { plugin.getDeviceData(call) }

    assertTrue(body!!.has("deviceSettings"))
  }

  @Test
  fun `getLocalFolders resolves with every saved folder`() {
    db.saveLocalFolder(com.audiobookshelf.app.data.LocalFolder("f1", "Folder", "", "", "/f1", "internal", "book"))
    val call = pluginCall()

    val body = awaitResolve(call) { plugin.getLocalFolders(call) }

    assertEquals(1, body!!.getJSONArray("value").length())
  }

  @Test
  fun `getLocalFolder resolves with the matching folder`() {
    db.saveLocalFolder(com.audiobookshelf.app.data.LocalFolder("f1", "Folder", "", "", "/f1", "internal", "book"))
    val call = pluginCall("folderId" to "f1")

    val body = awaitResolve(call) { plugin.getLocalFolder(call) }

    assertEquals("f1", body!!.getString("id"))
  }

  @Test
  fun `getLocalFolder resolves with no arguments when the folder is missing`() {
    val call = pluginCall("folderId" to "missing")

    val body = awaitResolve(call) { plugin.getLocalFolder(call) }

    assertNull("a missing folder should resolve via the no-arg resolve()", body)
  }

  @Test
  fun `getLocalLibraryItem resolves with the matching item`() {
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1"))
    val call = pluginCall("id" to "local-1")

    val body = awaitResolve(call) { plugin.getLocalLibraryItem(call) }

    assertEquals("local-1", body!!.getString("id"))
  }

  @Test
  fun `getLocalLibraryItem resolves with no arguments when the item is missing`() {
    val call = pluginCall("id" to "does-not-exist")

    val body = awaitResolve(call) { plugin.getLocalLibraryItem(call) }

    assertNull(body)
  }

  @Test
  fun `getLocalLibraryItemByLId resolves by the server library item id`() {
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1", libraryItemId = "server-item-1"))
    val call = pluginCall("libraryItemId" to "server-item-1")

    val body = awaitResolve(call) { plugin.getLocalLibraryItemByLId(call) }

    assertEquals("local-1", body!!.getString("id"))
  }

  @Test
  fun `getLocalLibraryItems filters by media type`() {
    db.saveLocalLibraryItems(
            listOf(localLibraryItem(id = "book-1", mediaType = "book"))
    )
    val call = pluginCall("mediaType" to "book")

    val body = awaitResolve(call) { plugin.getLocalLibraryItems(call) }

    assertEquals(1, body!!.getJSONArray("value").length())
  }

  @Test
  fun `getLocalLibraryItemsInFolder filters by folder id`() {
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1"))
    val call = pluginCall("folderId" to "folder")

    val body = awaitResolve(call) { plugin.getLocalLibraryItemsInFolder(call) }

    assertEquals(1, body!!.getJSONArray("value").length())
  }

  @Test
  fun `getAllLocalMediaProgress resolves with every saved entry`() {
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "p1", "local-1", null, 100.0, 0.5, 50.0, false, null, null, 0, 0, null,
                    null, null, null, null, null
            )
    )
    val call = pluginCall()

    val body = awaitResolve(call) { plugin.getAllLocalMediaProgress(call) }

    assertEquals(1, body!!.getJSONArray("value").length())
  }

  @Test
  fun `getLocalMediaProgressForServerItem matches by library item and episode id`() {
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "p1", "local-1", null, 100.0, 0.5, 50.0, false, null, null, 0, 0, null,
                    null, null, null, "item-1", "ep-1"
            )
    )
    val call = pluginCall("libraryItemId" to "item-1", "episodeId" to "ep-1")

    val body = awaitResolve(call) { plugin.getLocalMediaProgressForServerItem(call) }

    assertEquals("p1", body!!.getString("id"))
  }

  @Test
  fun `getLocalMediaProgressForServerItem treats a blank episode id as no episode filter`() {
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "p1", "local-1", null, 100.0, 0.5, 50.0, false, null, null, 0, 0, null,
                    null, null, null, "item-1", null
            )
    )
    val call = pluginCall("libraryItemId" to "item-1", "episodeId" to "")

    val body = awaitResolve(call) { plugin.getLocalMediaProgressForServerItem(call) }

    assertEquals("p1", body!!.getString("id"))
  }

  @Test
  fun `getLocalMediaProgressForServerItem resolves with no arguments when nothing matches`() {
    val call = pluginCall("libraryItemId" to "missing")

    val body = awaitResolve(call) { plugin.getLocalMediaProgressForServerItem(call) }

    assertNull(body)
  }

  @Test
  fun `removeLocalMediaProgress deletes the record and resolves`() {
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "p1", "local-1", null, 100.0, 0.5, 50.0, false, null, null, 0, 0, null,
                    null, null, null, null, null
            )
    )
    val call = pluginCall("localMediaProgressId" to "p1")

    awaitResolve(call) { plugin.removeLocalMediaProgress(call) }

    assertNull(db.getLocalMediaProgress("p1"))
  }

  @Test
  fun `getAccessToken returns the token for a known server connection`() {
    DeviceManager.deviceData.serverConnectionConfigs =
            mutableListOf(ServerConnectionConfig("srv-1", 0, "n", "https://x", null, "u", "un", "secret-token", null))
    val call = pluginCall("serverConnectionConfigId" to "srv-1")
    val captured = slot<JSObject>()
    every { call.resolve(capture(captured)) } returns Unit

    // getAccessToken resolves synchronously (no GlobalScope.launch), unlike most methods above.
    plugin.getAccessToken(call)

    assertEquals("secret-token", captured.captured.getString("token"))
  }

  @Test
  fun `getAccessToken returns an empty string for an unknown server connection`() {
    val call = pluginCall("serverConnectionConfigId" to "missing")
    val captured = slot<JSObject>()
    every { call.resolve(capture(captured)) } returns Unit

    plugin.getAccessToken(call)

    assertEquals("", captured.captured.getString("token"))
  }

  private fun mediaProgressCall(
          localLibraryItemId: String,
          localMediaProgressId: String? = null,
          localEpisodeId: String = "",
          serverProgressJson: String = """{"id":"sp1","libraryItemId":"item-1","episodeId":null,"duration":100.0,"progress":0.8,"currentTime":80.0,"isFinished":false,"ebookLocation":null,"ebookProgress":null,"lastUpdate":5000,"startedAt":0,"finishedAt":null}"""
  ): PluginCall {
    val call = pluginCall("localLibraryItemId" to localLibraryItemId, "localEpisodeId" to localEpisodeId)
    every { call.getObject("mediaProgress") } returns JSObject(serverProgressJson)
    every { call.getString("localMediaProgressId") } returns localMediaProgressId
    return call
  }

  @Test
  fun `syncServerMediaProgressWithLocalMediaProgress saves a new record when no localMediaProgressId is given`() {
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1"))
    val call = mediaProgressCall(localLibraryItemId = "local-1")

    val body = awaitResolve(call) { plugin.syncServerMediaProgressWithLocalMediaProgress(call) }

    assertEquals("local-1", body!!.getString("localLibraryItemId"))
    assertEquals(80.0, db.getLocalMediaProgress("local-1")?.currentTime)
  }

  @Test
  fun `syncServerMediaProgressWithLocalMediaProgress updates an existing record by localMediaProgressId`() {
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "p1", "local-1", null, 100.0, 0.1, 10.0, false, null, null, 0, 0, null,
                    null, null, null, "item-1", null
            )
    )
    val call = mediaProgressCall(localLibraryItemId = "local-1", localMediaProgressId = "p1")

    val body = awaitResolve(call) { plugin.syncServerMediaProgressWithLocalMediaProgress(call) }

    assertEquals(80.0, body!!.getDouble("currentTime"), 0.0)
    assertEquals(80.0, db.getLocalMediaProgress("p1")?.currentTime)
  }

  @Test
  fun `syncServerMediaProgressWithLocalMediaProgress resolves with no arguments when the localMediaProgressId is unknown`() {
    val call = mediaProgressCall(localLibraryItemId = "local-1", localMediaProgressId = "missing")

    val body = awaitResolve(call) { plugin.syncServerMediaProgressWithLocalMediaProgress(call) }

    assertNull(body)
  }

  @Test
  fun `updateLocalEbookProgress creates a new record from the library item's book duration`() {
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1", media = book().apply { duration = 3600.0 }))
    val call = pluginCall("localLibraryItemId" to "local-1", "ebookLocation" to "epubcfi(/6/4)")
    every { call.getDouble("ebookProgress") } returns 0.25

    val body = awaitResolve(call) { plugin.updateLocalEbookProgress(call) }

    val saved = db.getLocalMediaProgress("local-1")
    assertEquals("epubcfi(/6/4)", saved?.ebookLocation)
    assertEquals(0.25, saved?.ebookProgress ?: -1.0, 0.0)
    assertEquals(3600.0, saved?.duration ?: -1.0, 0.0)
    assertTrue(body!!.has("localMediaProgress"))
  }

  @Test
  fun `updateLocalEbookProgress updates an existing record's location and progress in place`() {
    db.saveLocalMediaProgress(
            LocalMediaProgress(
                    "local-1", "local-1", null, 100.0, 0.1, 10.0, false, "epubcfi(/6/2)", 0.1, 0, 0, null,
                    null, null, null, null, null
            )
    )
    val call = pluginCall("localLibraryItemId" to "local-1", "ebookLocation" to "epubcfi(/6/8)")
    every { call.getDouble("ebookProgress") } returns 0.75

    awaitResolve(call) { plugin.updateLocalEbookProgress(call) }

    val saved = db.getLocalMediaProgress("local-1")
    assertEquals("epubcfi(/6/8)", saved?.ebookLocation)
    assertEquals(0.75, saved?.ebookProgress ?: -1.0, 0.0)
    // The duration must be left untouched by an in-place ebook-progress update.
    assertEquals(100.0, saved?.duration ?: -1.0, 0.0)
  }

  @Test
  fun `updateLocalEbookProgress resolves with an error when the library item is missing`() {
    val call = pluginCall("localLibraryItemId" to "does-not-exist", "ebookLocation" to "loc")
    every { call.getDouble("ebookProgress") } returns 0.5

    val body = awaitResolve(call) { plugin.updateLocalEbookProgress(call) }

    assertTrue(body!!.has("error"))
  }

  @Test
  fun `updateLocalTrackOrder reindexes tracks to match the given order and persists the change`() {
    val trackA = com.audiobookshelf.app.data.audioTrack(index = 1, localFileId = "file-a")
    val trackB = com.audiobookshelf.app.data.audioTrack(index = 2, localFileId = "file-b")
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1", media = book(tracks = mutableListOf(trackA, trackB))))
    val call = pluginCall("localLibraryItemId" to "local-1")
    // Swap the order: file-b should become index 1, file-a index 2.
    every { call.getArray("tracks") } returns
            JSArray("""[{"localFileId":"file-b"},{"localFileId":"file-a"}]""")

    val body = awaitResolve(call) { plugin.updateLocalTrackOrder(call) }

    assertTrue("a real reorder must resolve with the updated item, not no-arg resolve()", body != null)
    val saved = db.getLocalLibraryItem("local-1")
    val tracks = saved!!.media.getAudioTracks().associateBy { it.localFileId }
    assertEquals(1, tracks["file-b"]?.index)
    assertEquals(2, tracks["file-a"]?.index)
  }

  @Test
  fun `updateLocalTrackOrder resolves with no arguments when the order already matches`() {
    val trackA = com.audiobookshelf.app.data.audioTrack(index = 1, localFileId = "file-a")
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1", media = book(tracks = mutableListOf(trackA))))
    val call = pluginCall("localLibraryItemId" to "local-1")
    every { call.getArray("tracks") } returns JSArray("""[{"localFileId":"file-a"}]""")

    val body = awaitResolve(call) { plugin.updateLocalTrackOrder(call) }

    assertNull("no changes should resolve via the no-arg resolve()", body)
  }

  @Test
  fun `updateLocalTrackOrder resolves with no arguments when the library item is missing`() {
    val call = pluginCall("localLibraryItemId" to "does-not-exist")
    every { call.getArray("tracks") } returns JSArray("[]")

    val body = awaitResolve(call) { plugin.updateLocalTrackOrder(call) }

    assertNull(body)
  }

  @Test
  fun `getMediaItemHistory resolves with no arguments when nothing is recorded for the id`() {
    val call = pluginCall()
    every { call.getString("mediaId") } returns "does-not-exist"

    val body = awaitResolve(call) { plugin.getMediaItemHistory(call) }

    assertNull(body)
  }

  @Test
  fun `getMediaItemHistory resolves with the saved history for a known id`() {
    db.saveMediaItemHistory(
            com.audiobookshelf.app.data.MediaItemHistory(
                    "media-1", "Title", "item-1", null, false, null, null, null, 1000L, mutableListOf()
            )
    )
    // getMediaItemHistory reads the id via the ONE-arg call.getString("mediaId"), unlike most
    // methods above which use the two-arg form pluginCall() stubs.
    val call = pluginCall()
    every { call.getString("mediaId") } returns "media-1"

    val body = awaitResolve(call) { plugin.getMediaItemHistory(call) }

    assertEquals("Title", body!!.getString("mediaDisplayTitle"))
  }

  // --- secureStorage-dependent methods -------------------------------------------------------
  //
  // `secureStorage`/`apiHandler` are `private lateinit var`s, only assigned by `load()` (needs a
  // real Capacitor `Bridge`). `AbsTestEnvironment.injectField` sets them directly via reflection,
  // the same way `AbsDownloaderTest` injects `AbsDownloader`'s public lateinit fields. A real
  // `SecureStorage` is deliberately NOT used here: its own AES/GCM path is a documented separate
  // blocker (`KeyGenerator.getInstance("AES", "AndroidKeyStore")` has no host-JVM test double), so
  // these tests mock `secureStorage` to isolate `AbsDatabase`'s own logic from that gap.

  private fun withSecureStorage(): SecureStorage {
    val secureStorage = mockk<SecureStorage>(relaxed = true)
    AbsTestEnvironment.injectField(plugin, "secureStorage", secureStorage)
    return secureStorage
  }

  private fun serverConfigCall(
          id: String? = null,
          index: Int = 0,
          userId: String = "u1",
          username: String = "un",
          version: String = "2.17.0",
          token: String = "tok",
          refreshToken: String? = null,
          address: String = "https://new-server.invalid"
  ): PluginCall {
    val idJson = if (id != null) "\"$id\"" else "null"
    val refreshJson = if (refreshToken != null) "\"$refreshToken\"" else "null"
    val json = """{"id":$idJson,"index":$index,"name":null,"userId":"$userId","username":"$username",
      "version":"$version","token":"$token","refreshToken":$refreshJson,"address":"$address","customHeaders":null}"""
    val call = pluginCall("address" to address, "serverConnectionConfigId" to (id ?: ""))
    every { call.data } returns JSObject(json)
    return call
  }

  @Test
  fun `setCurrentServerConnectionConfig creates a new config when none exists for the given id`() {
    withSecureStorage()
    val call = serverConfigCall(id = null, address = "https://new-server.invalid", token = "tok-1")

    val body = awaitResolve(call) { plugin.setCurrentServerConnectionConfig(call) }

    assertEquals(1, DeviceManager.deviceData.serverConnectionConfigs.size)
    val saved = DeviceManager.deviceData.serverConnectionConfigs.single()
    assertEquals("https://new-server.invalid", saved.address)
    assertEquals("tok-1", saved.token)
    assertEquals(saved.id, DeviceManager.serverConnectionConfig?.id)
    assertEquals("tok-1", body!!.getString("token"))
  }

  @Test
  fun `setCurrentServerConnectionConfig stores the refresh token when one is provided`() {
    val secureStorage = withSecureStorage()
    every { secureStorage.storeRefreshToken(any(), any()) } returns true
    val call = serverConfigCall(id = null, refreshToken = "refresh-1")

    awaitResolve(call) { plugin.setCurrentServerConnectionConfig(call) }

    verify { secureStorage.storeRefreshToken(any(), "refresh-1") }
  }

  @Test
  fun `setCurrentServerConnectionConfig updates an existing config's token in place`() {
    withSecureStorage()
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(
            ServerConnectionConfig("srv-1", 0, "old", "https://x", "2.16.0", "u1", "un", "old-tok", null)
    )
    val call = serverConfigCall(id = "srv-1", token = "new-tok", version = "2.17.0")

    awaitResolve(call) { plugin.setCurrentServerConnectionConfig(call) }

    assertEquals(1, DeviceManager.deviceData.serverConnectionConfigs.size)
    assertEquals("new-tok", DeviceManager.deviceData.serverConnectionConfigs.single().token)
  }

  @Test
  fun `removeServerConnectionConfig removes the config and clears the active connection if it was active`() {
    val secureStorage = withSecureStorage()
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(
            ServerConnectionConfig("srv-1", 0, "n", "https://x", null, "u1", "un", "tok", null)
    )
    DeviceManager.serverConnectionConfig = DeviceManager.deviceData.serverConnectionConfigs.single()
    val call = pluginCall("serverConnectionConfigId" to "srv-1")

    awaitResolve(call) { plugin.removeServerConnectionConfig(call) }

    assertTrue(DeviceManager.deviceData.serverConnectionConfigs.isEmpty())
    assertNull("the active connection must be cleared when its own config is removed", DeviceManager.serverConnectionConfig)
    verify { secureStorage.removeRefreshToken("srv-1") }
  }

  @Test
  fun `getRefreshToken resolves with no arguments when secureStorage has nothing stored`() {
    val secureStorage = withSecureStorage()
    every { secureStorage.getRefreshToken(any()) } returns null
    val call = pluginCall("serverConnectionConfigId" to "srv-1")

    val body = awaitResolve(call) { plugin.getRefreshToken(call) }

    assertNull(body)
  }

  @Test
  fun `getRefreshToken resolves with the token secureStorage returns`() {
    val secureStorage = withSecureStorage()
    every { secureStorage.getRefreshToken("srv-1") } returns "stored-refresh-token"
    val call = pluginCall("serverConnectionConfigId" to "srv-1")

    val body = awaitResolve(call) { plugin.getRefreshToken(call) }

    assertEquals("stored-refresh-token", body!!.getString("refreshToken"))
  }

  @Test
  fun `clearRefreshToken resolves with whatever removeRefreshToken reports`() {
    val secureStorage = withSecureStorage()
    every { secureStorage.removeRefreshToken("srv-1") } returns true
    val call = mockk<PluginCall>(relaxed = true)
    every { call.getString("serverConnectionConfigId", "") } returns "srv-1"
    val captured = slot<JSObject>()
    every { call.resolve(capture(captured)) } returns Unit

    plugin.clearRefreshToken(call)

    assertTrue(captured.captured.getBoolean("success"))
  }

  @Test
  fun `logout clears the active server connection without needing any injected collaborator`() {
    DeviceManager.serverConnectionConfig = ServerConnectionConfig("srv-1", 0, "n", "https://x", null, "u1", "un", "tok", null)
    DeviceManager.deviceData.lastServerConnectionConfigId = "srv-1"
    val call = pluginCall()

    awaitResolve(call) { plugin.logout(call) }

    assertNull(DeviceManager.serverConnectionConfig)
    assertNull(DeviceManager.deviceData.lastServerConnectionConfigId)
  }

  @Test
  fun `syncLocalSessionsWithServer resolves immediately when not connected to a server`() {
    // No apiHandler injected at all - proves this early-return path needs no collaborator.
    val call = pluginCall()

    val body = awaitResolve(call) { plugin.syncLocalSessionsWithServer(call) }

    assertNull(body)
  }

  private fun withApiHandler(): ApiHandler {
    val apiHandler = mockk<ApiHandler>(relaxed = true)
    AbsTestEnvironment.injectField(plugin, "apiHandler", apiHandler)
    DeviceManager.serverConnectionConfig = ServerConnectionConfig("srv-1", 0, "n", "https://x", null, "u1", "un", "tok", null)
    return apiHandler
  }

  @Test
  fun `syncLocalSessionsWithServer resolves without contacting the server when there are no saved sessions`() {
    val apiHandler = withApiHandler()
    every { apiHandler.syncLocalMediaProgressForUser(any()) } answers { firstArg<() -> Unit>().invoke() }
    val call = pluginCall()

    val body = awaitResolve(call) { plugin.syncLocalSessionsWithServer(call) }

    assertNull(body)
    verify(exactly = 0) { apiHandler.sendSyncLocalSessions(any(), any()) }
  }

  @Test
  fun `syncLocalSessionsWithServer removes synced sessions and resolves on success`() {
    val apiHandler = withApiHandler()
    val db = DbManager()
    db.savePlaybackSession(playbackSession().apply { serverConnectionConfigId = "srv-1" })
    every { apiHandler.syncLocalMediaProgressForUser(any()) } answers { firstArg<() -> Unit>().invoke() }
    every { apiHandler.sendSyncLocalSessions(any(), any()) } answers { secondArg<(Boolean, String?) -> Unit>().invoke(true, null) }
    val call = pluginCall()

    val body = awaitResolve(call) { plugin.syncLocalSessionsWithServer(call) }

    assertNull(body)
    assertTrue("synced sessions must be removed from local storage", db.getPlaybackSessions().isEmpty())
  }

  @Test
  fun `syncLocalSessionsWithServer resolves with an error when sending sessions fails`() {
    val apiHandler = withApiHandler()
    val db = DbManager()
    db.savePlaybackSession(playbackSession().apply { serverConnectionConfigId = "srv-1" })
    every { apiHandler.syncLocalMediaProgressForUser(any()) } answers { firstArg<() -> Unit>().invoke() }
    every { apiHandler.sendSyncLocalSessions(any(), any()) } answers {
      secondArg<(Boolean, String?) -> Unit>().invoke(false, "server rejected sync")
    }
    val call = pluginCall()

    val body = awaitResolve(call) { plugin.syncLocalSessionsWithServer(call) }

    assertTrue(body!!.getString("error")!!.contains("server rejected sync"))
    assertFalse("a failed sync must not delete the local session", db.getPlaybackSessions().isEmpty())
  }

  @Test
  fun `updateLocalMediaProgressFinished resolves a local-only result without needing apiHandler when not linked to the active server`() {
    // apiHandler is deliberately left uninjected: the sync-with-server branch is only reached
    // when localMediaProgress.serverConnectionConfigId matches the currently active connection.
    db.saveLocalLibraryItem(localLibraryItem(id = "local-1", media = book().apply { duration = 100.0 }))
    val call = pluginCall("localLibraryItemId" to "local-1", "localEpisodeId" to "")
    every { call.getBoolean("isFinished", false) } returns true

    val body = awaitResolve(call) { plugin.updateLocalMediaProgressFinished(call) }

    assertEquals(false, body!!.getBoolean("server"))
    assertEquals(true, body.getBoolean("local"))
    assertTrue(db.getLocalMediaProgress("local-1")!!.isFinished)
  }
}
