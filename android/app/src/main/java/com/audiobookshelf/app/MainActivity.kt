package com.audiobookshelf.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.SimpleStorageHelper
import com.audiobookshelf.app.data.AbsDatabase
import com.audiobookshelf.app.data.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.plugins.AbsAudioPlayer
import com.audiobookshelf.app.plugins.AbsDownloader
import com.audiobookshelf.app.plugins.AbsFileSystem
import com.getcapacitor.BridgeActivity


class MainActivity : BridgeActivity() {
  private val tag = "MainActivity"

  private var mBounded = false
  lateinit var foregroundService : PlayerNotificationService
  private lateinit var mConnection : ServiceConnection

  lateinit var pluginCallback : () -> Unit

  val storageHelper = SimpleStorageHelper(this)
  val storage = SimpleStorage(this)

  public override fun onCreate(savedInstanceState: Bundle?) {
    // TODO: Optimize using strict mode logs
//    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
//      .detectDiskReads()
//      .detectDiskWrites().detectAll()
//      .detectNetwork() // or .detectAll() for all detectable problems
//      .penaltyLog()
//      .build())
//    StrictMode.setVmPolicy(VmPolicy.Builder()
//      .detectLeakedSqlLiteObjects()
//      .detectLeakedClosableObjects()
//      .penaltyLog()
//      .build())

    super.onCreate(savedInstanceState)
    Log.d(tag, "onCreate")

    DbManager.initialize(applicationContext)


    registerPlugin(AbsAudioPlayer::class.java)
    registerPlugin(AbsDownloader::class.java)
    registerPlugin(AbsFileSystem::class.java)
    registerPlugin(AbsDatabase::class.java)
  }

  override fun onDestroy() {
    super.onDestroy()
  }

  override fun onPostCreate(savedInstanceState: Bundle?) {
    super.onPostCreate(savedInstanceState)
    Log.d(tag, "onPostCreate MainActivity")

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

        // Let NativeAudio know foreground service is ready and setup event listener
        if (pluginCallback != null) {
          pluginCallback()
        }
      }
    }

    Intent(this, PlayerNotificationService::class.java).also { intent ->
      Log.d(tag, "Binding PlayerNotificationService")
      bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }
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
