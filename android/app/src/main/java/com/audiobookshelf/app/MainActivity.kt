package com.audiobookshelf.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.view.updateLayoutParams
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.SimpleStorageHelper
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.plugins.AbsAudioPlayer
import com.audiobookshelf.app.plugins.AbsDatabase
import com.audiobookshelf.app.plugins.AbsDownloader
import com.audiobookshelf.app.plugins.AbsFileSystem
import com.audiobookshelf.app.plugins.AbsLogger
import com.getcapacitor.BridgeActivity


class MainActivity : BridgeActivity() {
  private val tag = "MainActivity"

  private var mBounded = false
  lateinit var foregroundService : PlayerNotificationService
  private lateinit var mConnection : ServiceConnection

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

    super.onCreate(savedInstanceState)
    Log.d(tag, "onCreate")

    // Update the margins to handle edge-to-edge enforced in SDK 35
    // See: https://developer.android.com/develop/ui/views/layout/edge-to-edge
    val webView: WebView = findViewById(R.id.webview)
    webView.setOnApplyWindowInsetsListener { v, insets ->
      val (left, top, right, bottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val sysInsets = insets.getInsets(WindowInsets.Type.systemBars())
        Log.d(tag, "safe sysInsets: $sysInsets")
        arrayOf(sysInsets.left, sysInsets.top, sysInsets.right, sysInsets.bottom)
      } else {
        arrayOf(
          insets.systemWindowInsetLeft,
          insets.systemWindowInsetTop,
          insets.systemWindowInsetRight,
          insets.systemWindowInsetBottom
        )
      }

      // Inject as CSS variables
      // NOTE: Possibly able to use in the future to support edge-to-edge better.
       val js = """
       document.documentElement.style.setProperty('--safe-area-inset-top', '${top}px');
       document.documentElement.style.setProperty('--safe-area-inset-bottom', '${bottom}px');
       document.documentElement.style.setProperty('--safe-area-inset-left', '${left}px');
       document.documentElement.style.setProperty('--safe-area-inset-right', '${right}px');
      """.trimIndent()
      webView.evaluateJavascript(js, null)

      // Set margins
      v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        leftMargin = left
        bottomMargin = bottom
        rightMargin = right
        topMargin = top
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowInsets.CONSUMED
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

  override fun onPostCreate(savedInstanceState: Bundle?) {
    super.onPostCreate(savedInstanceState)
    Log.d(tag, "onPostCreate MainActivity")

    // Request POST_NOTIFICATIONS permission on Android 13+
    if (Build.VERSION.SDK_INT >= 33) {
      val notifGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
      if (!notifGranted) {
        Log.d(tag, "Requesting POST_NOTIFICATIONS runtime permission")
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
      }
    }

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
        try {
          pluginCallback()
        } catch (e: UninitializedPropertyAccessException) {
          Log.w(tag, "Plugin callback not yet initialized")
        }
      }
    }
    Intent(this, PlayerNotificationService::class.java).also { intent ->
      Log.d(tag, "Binding PlayerNotificationService")
      bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }

  }

  fun isPlayerNotificationServiceInitialized():Boolean {
    return ::foregroundService.isInitialized
  }

  fun stopMyService() {
    if (mBounded) {
      mConnection.let { unbindService(it) };
      mBounded = false;
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
}
