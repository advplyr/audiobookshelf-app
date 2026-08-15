package com.audiobookshelf.app.support

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.webkit.MimeTypeMap
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.paperdb.Paper
import java.io.File
import java.security.Provider
import java.security.Security
import okhttp3.mockwebserver.MockWebServer

/**
 * Shared host-JVM test harness for code that touches the `DeviceManager`/Paper singleton stack.
 *
 * `DeviceManager`, `MediaEventManager`, and `AbsLogger` are process-wide Kotlin `object`s that
 * persist state across every test class run in the same Gradle test JVM (Gradle does not fork a
 * new JVM per class by default). [reset] repoints Paper at a fresh temp directory and clears the
 * mutable singleton state that would otherwise leak between tests - call it from a `@Before`
 * method in any test that touches `DeviceManager`, `DbManager`, `ApiHandler`, `MediaEventManager`,
 * or `AbsLogger`.
 */
object AbsTestEnvironment {
  init {
    // Registered once per JVM: SecureStorage's property initializer calls
    // KeyStore.getInstance("AndroidKeyStore"), which does not exist on a host JVM. A JCA
    // provider under that exact name backed by the JDK's own JKS keystore clears it.
    if (Security.getProvider("AndroidKeyStore") == null) {
      Security.addProvider(FakeAndroidKeyStoreProvider())
    }
  }

  /**
   * Repoints Paper at a fresh temp directory and clears mutable singleton state. Call this from
   * both `@Before` (so each test starts clean) and `@After` (so a test doesn't leave
   * `DeviceManager.serverConnectionConfig` pointing at a `MockWebServer` instance it already shut
   * down, which the next test class to run in this JVM would otherwise inherit).
   */
  fun reset() {
    val dir = File(System.getProperty("java.io.tmpdir"), "abs-test-${System.nanoTime()}")
    dir.mkdirs()
    // One directory per call, and reset() is called from both @Before and @After of ~30
    // classes, so a full run creates on the order of 800 of them. Paper holds the directory
    // open for the life of the book, so it cannot be deleted eagerly here - registering it
    // for deletion at JVM exit is what keeps the system temp dir from growing every run.
    dir.deleteOnExit()
    val ctx = mockk<Context>(relaxed = true)
    every { ctx.filesDir } returns dir
    every { ctx.applicationContext } returns ctx
    Paper.init(ctx)
    clearPaperBookCache()

    // DeviceManager.deviceData is cached in a `var` from whenever DeviceManager was first
    // touched in this JVM; force it to re-read from the freshly-pointed Paper directory.
    DeviceManager.deviceData = DeviceManager.dbManager.getDeviceData()
    DeviceManager.serverConnectionConfig = null
    MediaEventManager.clientEventEmitter = null
    AbsLogger.onLogEmitter = null
  }

  /**
   * Paper caches constructed `Book` instances forever in a static `ConcurrentHashMap` keyed only
   * by book name (see `Paper.getBook`) - a later `Paper.init()` call updates where *new* books are
   * created but never evicts books already touched earlier in this JVM, so without this a "log" or
   * "device" book opened by one test class keeps reading/writing the *first* test's temp directory
   * for the rest of the run. Clearing the map forces every book to be re-created against the
   * directory just set by [reset].
   */
  private fun clearPaperBookCache() {
    val field = Paper::class.java.getDeclaredField("mBookMap")
    field.isAccessible = true
    (field.get(null) as MutableMap<*, *>).clear()
  }

  /** A relaxed mock Android `Context`, sufficient for code paths that don't inspect it. */
  fun mockContext(): Context = mockk(relaxed = true)

  /**
   * Sets a `private lateinit var` instance field (e.g. a Capacitor plugin's `secureStorage`,
   * normally only assigned by `load()`, which needs a real Capacitor `Bridge`) via reflection.
   * Walks up the class hierarchy since Kotlin backing fields for a subclass's `private` property
   * are declared on that exact class, not necessarily [target]'s runtime class.
   */
  fun injectField(target: Any, fieldName: String, value: Any) {
    var clazz: Class<*>? = target.javaClass
    while (clazz != null) {
      try {
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
        return
      } catch (e: NoSuchFieldException) {
        clazz = clazz.superclass
      }
    }
    throw NoSuchFieldException("$fieldName not found on ${target.javaClass} or its superclasses")
  }

  /**
   * Overwrites a `public static final` field (e.g. `android.os.Build.MANUFACTURER`) for the rest
   * of this JVM's life.
   *
   * AGP's mockable `android.jar` nulls out the initializers of static String fields like
   * `Build.MANUFACTURER`/`Build.MODEL` (they're non-null on a real device, but null under this
   * stub) - code that reads them directly (not through a method) and forwards them into a
   * non-null Kotlin parameter throws `NullPointerException` at that call site.
   * `mockkStatic` cannot help here: it intercepts static *method* calls, but a Kotlin/Java static
   * field read compiles to a `getstatic` bytecode instruction, not a method call, so there is
   * nothing for MockK to hook. Reflection alone cannot un-final a field either - JDK 12+ blocks
   * mutating `Field`'s own `modifiers` field. `sun.misc.Unsafe` bypasses both problems by writing
   * the field's memory directly. This is inherently JVM-internals-fragile; use it only when a
   * production code path unconditionally reads a static field that the mockable jar has nulled.
   */
  fun setStaticField(clazz: Class<*>, fieldName: String, value: Any?) {
    val (unsafe, base, offset) = unsafeFieldHandle(clazz, fieldName)
    unsafe.javaClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(unsafe, base, offset, value)
  }

  /**
   * Scoped variant of [setStaticField]: writes [value] for the duration of [action], then restores
   * whatever the field held before, even if [action] throws. Prefer this over the raw setter -
   * [setStaticField] left uncorrected in a prior pass (`ApiHandlerContractTest`'s
   * `Build.MANUFACTURER`/`MODEL` override) leaked into every test class that ran afterward in the
   * same JVM.
   */
  fun <T> withStaticField(clazz: Class<*>, fieldName: String, value: Any?, action: () -> T): T {
    val previous = clazz.getField(fieldName).get(null)
    setStaticField(clazz, fieldName, value)
    try {
      return action()
    } finally {
      setStaticField(clazz, fieldName, previous)
    }
  }

  /**
   * Scoped override of `Build.VERSION.SDK_INT`, which reads `0` by default on the host JVM -
   * silently pinning every untouched test to whichever SDK-gated branch guards `< 28`. `SDK_INT`
   * is a primitive `int`, so it needs `Unsafe.putInt`, not the `Any?`-typed `putObject` used for
   * the `Build.*` string fields above.
   */
  fun <T> withSdkInt(version: Int, action: () -> T): T {
    val (unsafe, base, offset) = unsafeFieldHandle(Build.VERSION::class.java, "SDK_INT")
    val putInt = unsafe.javaClass.getMethod(
            "putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    val previous = Build.VERSION.SDK_INT
    putInt.invoke(unsafe, base, offset, version)
    try {
      return action()
    } finally {
      putInt.invoke(unsafe, base, offset, previous)
    }
  }

  private data class UnsafeFieldHandle(val unsafe: Any, val base: Any, val offset: Long)

  private fun unsafeFieldHandle(clazz: Class<*>, fieldName: String): UnsafeFieldHandle {
    val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    val unsafe = unsafeField.get(null)
    val unsafeClass = unsafe.javaClass
    val field = clazz.getField(fieldName)
    val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)!!
    val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long
    return UnsafeFieldHandle(unsafe!!, base, offset)
  }

  /**
   * Stubs `Uri.parse`/`Uri.fromFile` with mocks that retain `toString()`/`scheme`/`path`, unlike a
   * bare `mockk(relaxed = true)` (used by `IconsTest`/`BrowseTreeTest`) which is fine for "was a
   * URI built" assertions but returns empty strings for any assertion on the URI's actual value.
   * Call `unmockkStatic(Uri::class)` (or `unmockkAll()`) in `@After` to undo it.
   *
   * `scheme` and `path` are derived only for an absolute `scheme://authority/path` input, which is
   * every URI this app builds. For anything else - a bare relative path, a `mailto:`-style opaque
   * URI - both return `null`, matching `android.net.Uri`. An earlier version derived them by
   * unconditional string surgery, so `Uri.parse("cover.jpg").scheme` confidently returned the whole
   * string `"cover.jpg"`; a wrong answer is worse than no answer, because a test asserting on it
   * would have been green for the wrong reason.
   */
  fun mockUriParse() {
    mockkStatic(Uri::class)
    every { Uri.parse(any()) } answers {
      val s = firstArg<String>()
      val u = mockk<Uri>(relaxed = true)
      val separator = s.indexOf("://")
      every { u.toString() } returns s
      every { u.scheme } returns if (separator > 0) s.substring(0, separator) else null
      every { u.path } returns
              if (separator > 0) {
                val afterAuthority = s.substring(separator + 3)
                val slash = afterAuthority.indexOf('/')
                if (slash >= 0) afterAuthority.substring(slash) else "/"
              } else null
      u
    }
    every { Uri.fromFile(any()) } answers {
      val f = firstArg<File>()
      val u = mockk<Uri>(relaxed = true)
      every { u.toString() } returns "file://${f.absolutePath}"
      every { u.scheme } returns "file"
      every { u.path } returns f.absolutePath
      u
    }
  }

  /**
   * Stubs the three static methods needed to reach `java.io.File`-based local-file code
   * (`DeviceManager.getBase64Id`, the `com.anggrayudi.storage` `File.getBasePath`/`File.mimeType`
   * extensions used by `FolderScanner`'s internal-storage path and `DownloadItemPart.make`) that
   * are otherwise null under the mockable `android.jar` and throw `NullPointerException` one level
   * inside a third-party library, not in this app's own code - each was found by running a spike
   * and reading the next NPE. Call `unmockkAll()` in `@After` to undo it.
   */
  fun mockLocalFileStatics() {
    mockkStatic(MimeTypeMap::class)
    val mimeMap = mockk<MimeTypeMap>(relaxed = true)
    every { MimeTypeMap.getSingleton() } returns mimeMap
    every { mimeMap.getMimeTypeFromExtension(any()) } answers {
      when (firstArg<String>().lowercase()) {
        "mp3" -> "audio/mpeg"
        "jpg", "jpeg" -> "image/jpeg"
        "epub" -> "application/epub+zip"
        else -> null
      }
    }
    mockkStatic(Environment::class)
    every { Environment.getExternalStorageDirectory() } returns File("/storage/emulated/0")
    mockkStatic(Base64::class)
    every { Base64.encodeToString(any(), any()) } answers {
      firstArg<ByteArray>().toString(Charsets.UTF_8).replace('/', '_').replace(':', '-')
    }
    mockUriParse()
  }

  /** An [ApiHandler] wired to a mock `Context`; construction alone exercises `SecureStorage`. */
  fun apiHandler(ctx: Context = mockContext()): ApiHandler = ApiHandler(ctx)

  /**
   * Starts a [MockWebServer] and points `DeviceManager.serverConnectionConfig` at it so
   * `ApiHandler`/`MediaManager` requests reach it without any production change. The server is
   * shut down and the config cleared automatically.
   */
  fun withMockServer(
          serverVersion: String = "2.17.0",
          token: String = "test-token",
          userId: String = "test-user",
          customHeaders: Map<String, String>? = null,
          action: (MockWebServer) -> Unit
  ) {
    val server = MockWebServer()
    server.start()
    try {
      DeviceManager.serverConnectionConfig =
              ServerConnectionConfig(
                      "test-server",
                      0,
                      "Test Server",
                      server.url("/").toString().trimEnd('/'),
                      serverVersion,
                      userId,
                      "test-username",
                      token,
                      customHeaders
              )
      action(server)
    } finally {
      server.shutdown()
      DeviceManager.serverConnectionConfig = null
    }
  }
}

private class FakeAndroidKeyStoreProvider : Provider("AndroidKeyStore", 1.0, "host-JVM test double backed by JKS") {
  init {
    put("KeyStore.AndroidKeyStore", "sun.security.provider.JavaKeyStore\$JKS")
  }
}
