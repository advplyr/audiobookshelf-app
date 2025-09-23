package com.tomesonic.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.webkit.WebView
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.Player
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import java.util.concurrent.ExecutionException
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.SimpleStorageHelper
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.tomesonic.app.managers.DbManager
import com.tomesonic.app.player.PlayerNotificationService
import com.tomesonic.app.plugins.AbsAudioPlayer
import com.tomesonic.app.plugins.AbsDatabase
import com.tomesonic.app.plugins.AbsDownloader
import com.tomesonic.app.plugins.AbsFileSystem
import com.tomesonic.app.plugins.AbsLogger
// import com.tomesonic.app.plugins.AbsToast
import com.tomesonic.app.plugins.DynamicColorPlugin
import com.getcapacitor.BridgeActivity


class MainActivity : BridgeActivity() {
  private val tag = "MainActivity"

  private var mBounded = false
  private lateinit var mConnection: ServiceConnection
  lateinit var foregroundService : PlayerNotificationService
  private var mediaController: MediaController? = null

  lateinit var pluginCallback : () -> Unit

  val storageHelper = SimpleStorageHelper(this)
  val storage = SimpleStorage(this)

  val REQUEST_PERMISSIONS = 1
  var PERMISSIONS_ALL = arrayOf(
    Manifest.permission.READ_EXTERNAL_STORAGE
  )

  public override fun onCreate(savedInstanceState: Bundle?) {
    DbManager.initialize(applicationContext)

    registerPlugin(AbsAudioPlayer::class.java)
    registerPlugin(AbsDownloader::class.java)
    registerPlugin(AbsFileSystem::class.java)
    registerPlugin(AbsDatabase::class.java)
    registerPlugin(AbsLogger::class.java)
    // registerPlugin(AbsToast::class.java)
    registerPlugin(DynamicColorPlugin::class.java)

    super.onCreate(savedInstanceState)
    Log.d(tag, "onCreate")

    // Initialize Cast SDK
    initializeCast()

  // Enable edge-to-edge so the webview can render behind the system bars.
  // See: https://developer.android.com/develop/ui/views/layout/edge-to-edge
  WindowCompat.setDecorFitsSystemWindows(window, false)
    val webView: WebView = findViewById(R.id.webview)
    // Keep injecting CSS safe-area insets but DO NOT add margins so the webview
    // content draws behind the system bars (transparent nav/status bar)
    webView.setOnApplyWindowInsetsListener { v, insets ->
      // Use the modern WindowInsets API - works for API 23+
      val sysInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets.getInsets(WindowInsets.Type.systemBars())
      } else {
        // For API 23-29, use getInsets with fallback to deprecated methods
        try {
          insets.getInsets(WindowInsets.Type.systemBars())
        } catch (e: Exception) {
          // Fallback to stable insets for very old versions (API 24-29)
          // Note: stableInsets is deprecated but needed for minSdk 24 compatibility
          @Suppress("DEPRECATION")
          insets.stableInsets?.let { stableInsets ->
            android.graphics.Insets.of(stableInsets.left, stableInsets.top, stableInsets.right, stableInsets.bottom)
          } ?: android.graphics.Insets.NONE
        }
      }

      Log.d(tag, "safe sysInsets: $sysInsets")
      val (left, top, right, bottom) = arrayOf(sysInsets.left, sysInsets.top, sysInsets.right, sysInsets.bottom)

      // Inject as CSS variables so Nuxt pages can use env(safe-area-inset-*) or
      // the --safe-area-inset-* variables for layout while content stays full-bleed.
      val js = """
       document.documentElement.style.setProperty('--safe-area-inset-top', '${top}px');
       document.documentElement.style.setProperty('--safe-area-inset-bottom', '${bottom}px');
       document.documentElement.style.setProperty('--safe-area-inset-left', '${left}px');
       document.documentElement.style.setProperty('--safe-area-inset-right', '${right}px');
       document.documentElement.setAttribute('data-safe-area-ready', 'true');
       console.log('[Android] Set safe area insets - top: ${top}px, bottom: ${bottom}px');
      """.trimIndent()
      webView.evaluateJavascript(js, null)

      // Do not consume insets so underlying handling remains intact on older SDKs
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets
      } else {
        insets
      }
    }

    val permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
    if (permission != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this,
        PERMISSIONS_ALL,
        REQUEST_PERMISSIONS)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
  }

  override fun onStart() {
    super.onStart()
    Log.d(tag, "onStart MainActivity")
    // Additional sync point for when activity becomes visible
    if (::foregroundService.isInitialized) {
      try {
        val absAudioPlayer = bridge.getPlugin("AbsAudioPlayer").instance as AbsAudioPlayer
        absAudioPlayer.syncCurrentPlaybackStateWhenReady()
      } catch (e: Exception) {
        Log.e(tag, "Failed to sync playback state on start: ${e.message}")
      }
    }
  }

  override fun onResume() {
    super.onResume()
    Log.d(tag, "onResume MainActivity")
    // Trigger UI sync when app comes to foreground, waiting for UI to be ready
    if (::foregroundService.isInitialized) {
      try {
        val absAudioPlayer = bridge.getPlugin("AbsAudioPlayer").instance as AbsAudioPlayer
        // Only sync if there's already an active session - don't trigger restoration on resume
        if (foregroundService.currentPlaybackSession != null) {
          Log.d(tag, "Active session exists, syncing playback state on resume")
          absAudioPlayer.syncCurrentPlaybackStateWhenReady() // Smart sync that waits for readiness
        } else {
          Log.d(tag, "No active session, skipping sync on resume to avoid interfering with automatic restoration")
        }
        Log.d(tag, "AABrowser: Calling forceAndroidAutoReload on app resume")
        foregroundService.forceAndroidAutoReload()
      } catch (e: Exception) {
        Log.e(tag, "Failed to sync playback state on resume: ${e.message}")
      }
    }

    // Ensure safe area insets are set when app resumes
    updateSafeAreaInsets()

    // Add delayed retry to ensure WebView is fully ready
    Handler(Looper.getMainLooper()).postDelayed({
      updateSafeAreaInsets()
    }, 200)
  }

  private fun updateSafeAreaInsets() {
    val webView: WebView = findViewById(R.id.webview)
    val insets = webView.rootWindowInsets
    if (insets != null) {
      // Use the modern WindowInsets API consistently
      val sysInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets.getInsets(WindowInsets.Type.systemBars())
      } else {
        // For API 23-29, use getInsets with fallback to stable insets
        try {
          insets.getInsets(WindowInsets.Type.systemBars())
        } catch (e: Exception) {
          // Fallback to stable insets for compatibility (API 24-29)
          // Note: stableInsets is deprecated but needed for minSdk 24 compatibility
          @Suppress("DEPRECATION")
          insets.stableInsets?.let { stableInsets ->
            android.graphics.Insets.of(stableInsets.left, stableInsets.top, stableInsets.right, stableInsets.bottom)
          } ?: android.graphics.Insets.NONE
        }
      }

      Log.d(tag, "updateSafeAreaInsets sysInsets: $sysInsets")
      val (left, top, right, bottom) = arrayOf(sysInsets.left, sysInsets.top, sysInsets.right, sysInsets.bottom)

      // Inject as CSS variables so Nuxt pages can use env(safe-area-inset-*) or
      // the --safe-area-inset-* variables for layout while content stays full-bleed.
      val js = """
       document.documentElement.style.setProperty('--safe-area-inset-top', '${top}px');
       document.documentElement.style.setProperty('--safe-area-inset-bottom', '${bottom}px');
       document.documentElement.style.setProperty('--safe-area-inset-left', '${left}px');
       document.documentElement.style.setProperty('--safe-area-inset-right', '${right}px');
       document.documentElement.setAttribute('data-safe-area-ready', 'true');
       console.log('[Android] Updated safe area insets on resume - top: ${top}px, bottom: ${bottom}px');
      """.trimIndent()
      webView.evaluateJavascript(js, null)
    }
  }

    override fun onPostCreate(savedInstanceState: Bundle?) {
    super.onPostCreate(savedInstanceState)
    Log.d(tag, "onPostCreate MainActivity")

    // Bind to service for direct access (needed for plugin operations)
    mConnection = object : ServiceConnection {
      override fun onServiceDisconnected(name: ComponentName) {
        Log.w(tag, "Service Disconnected $name")
        mBounded = false
      }

      override fun onServiceConnected(name: ComponentName, service: IBinder) {
        Log.d(tag, "Service Connected $name")

        mBounded = true
        val mLocalBinder = service as PlayerNotificationService.LocalBinder
        foregroundService = mLocalBinder.getService()

        // Try to connect Media3 MediaController for playback control
        val sessionToken = SessionToken(this@MainActivity, ComponentName(this@MainActivity, PlayerNotificationService::class.java))
        val controllerFuture = MediaController.Builder(this@MainActivity, sessionToken).buildAsync()

        Futures.addCallback(controllerFuture, object : FutureCallback<MediaController> {
          override fun onSuccess(controller: MediaController) {
            Log.d(tag, "Media3 MediaController connected")
            mediaController = controller

            // Set up MediaController callback to handle custom actions
            controller.addListener(object : Player.Listener {
              override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                Log.d(tag, "Available commands changed: ${availableCommands.size()}")
                // Custom actions should be available here
              }
            })
          }

          override fun onFailure(throwable: Throwable) {
            Log.w(tag, "Media3 MediaController connection failed (expected for now): ${throwable.message}")
            // This is expected since the service might not be fully migrated to Media3 yet
          }
        }, ContextCompat.getMainExecutor(this@MainActivity))

        // Let NativeAudio know foreground service is ready and setup event listener
        if (::pluginCallback.isInitialized) {
          pluginCallback()
        }

        // Also trigger UI sync when service connects on activity creation
        try {
          val absAudioPlayer = bridge.getPlugin("AbsAudioPlayer").instance as AbsAudioPlayer
          absAudioPlayer.syncCurrentPlaybackStateWhenReady() // Smart sync that waits for readiness

          // Add a fallback sync for fresh installs/updates where timing might be critical
          Handler(Looper.getMainLooper()).post {
            try {
              Log.d(tag, "Fallback sync attempt after service connection")
              absAudioPlayer.syncCurrentPlaybackStateWhenReady()
            } catch (e: Exception) {
              Log.e(tag, "Fallback sync failed: ${e.message}")
            }
          }

        } catch (e: Exception) {
          Log.e(tag, "Failed to sync playback state on service connect: ${e.message}")
        }
      }
    }

    // Start the service (Media3 will promote to foreground when needed)
    Intent(this, PlayerNotificationService::class.java).also { intent ->
      Log.d(tag, "Starting PlayerNotificationService")
      startService(intent)
    }

    Intent(this, PlayerNotificationService::class.java).also { intent ->
      Log.d(tag, "Binding PlayerNotificationService")
      bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }
  }

  fun isPlayerNotificationServiceInitialized():Boolean {
    return ::foregroundService.isInitialized
  }

  fun startMyService() {
    Log.d(tag, "startMyService called")

    if (mBounded) {
      Log.d(tag, "Service already bound")
      return
    }

    // Start the service (Media3 will promote to foreground when needed)
    Intent(this, PlayerNotificationService::class.java).also { intent ->
      Log.d(tag, "Starting PlayerNotificationService")
      startService(intent)
    }

    // Bind to it
    Intent(this, PlayerNotificationService::class.java).also { intent ->
      Log.d(tag, "Binding PlayerNotificationService")
      bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }
  }

  fun stopMyService() {
    // Release Media3 MediaController
    mediaController?.release()
    mediaController = null

    // Unbind service
    if (mBounded && ::mConnection.isInitialized) {
      unbindService(mConnection)
      mBounded = false
    }

    val stopIntent = Intent(this, PlayerNotificationService::class.java)
    stopService(stopIntent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    storageHelper.onSaveInstanceState(outState)
    super.onSaveInstanceState(outState)
    outState.clear()
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    storageHelper.onRestoreInstanceState(savedInstanceState)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    // Mandatory for Activity, but not for Fragment & ComponentActivity
    storageHelper.storage.onActivityResult(requestCode, resultCode, data)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    Log.d(tag, "onRequestPermissionResult $requestCode")
    permissions.forEach { Log.d(tag, "PERMISSION $it") }
    grantResults.forEach { Log.d(tag, "GRANTREUSLTS $it") }
    // Mandatory for Activity, but not for Fragment & ComponentActivity
    storageHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
  }

  /**
   * Initialize Google Cast SDK
   */
  private fun initializeCast() {
    try {
      Log.d(tag, "Initializing Google Cast SDK...")

      // Initialize CastContext - this will use our CastOptionsProvider
      val castContext = CastContext.getSharedInstance(this)

      // Add cast state listener to monitor cast availability
      castContext.addCastStateListener { state ->
        when (state) {
          CastState.NO_DEVICES_AVAILABLE -> {
            Log.d(tag, "Cast: No devices available")
          }
          CastState.NOT_CONNECTED -> {
            Log.d(tag, "Cast: Not connected")
          }
          CastState.CONNECTING -> {
            Log.d(tag, "Cast: Connecting...")
          }
          CastState.CONNECTED -> {
            Log.d(tag, "Cast: Connected!")
          }
        }
      }

      Log.d(tag, "Cast SDK initialized successfully with app ID: ${castContext.castOptions.receiverApplicationId}")

    } catch (e: Exception) {
      Log.e(tag, "Failed to initialize Cast SDK", e)
    }
  }
}
