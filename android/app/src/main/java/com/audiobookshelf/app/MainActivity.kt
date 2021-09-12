package com.audiobookshelf.app

import android.app.DownloadManager
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.getcapacitor.BridgeActivity


class MainActivity : BridgeActivity() {
  private val tag = "MainActivity"

  private var mBounded = false
  lateinit var foregroundService : PlayerNotificationService
  private lateinit var mConnection : ServiceConnection

  lateinit var pluginCallback : () -> Unit
  lateinit var downloaderCallback : (String, Long) -> Unit

  val broadcastReceiver = object: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
          var thisdlid = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L)

          downloaderCallback("complete", thisdlid)

          Log.d(tag, "DOWNNLAOD COMPELTE $thisdlid")
          Toast.makeText(this@MainActivity, "Download Completed  $thisdlid", Toast.LENGTH_SHORT)
        }
        DownloadManager.ACTION_NOTIFICATION_CLICKED -> {
          var thisdlid = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L)
          downloaderCallback("clicked", thisdlid)

          Log.d(tag, "CLICKED NOTFIFICAIONT $thisdlid")
          Toast.makeText(this@MainActivity, "Download CLICKED $thisdlid", Toast.LENGTH_SHORT)
        }
      }
    }
  }

  public override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(tag, "onCreate")
    registerPlugin(MyNativeAudio::class.java)
    registerPlugin(AudioDownloader::class.java)

    var filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE).apply {
      addAction(DownloadManager.ACTION_NOTIFICATION_CLICKED)
    }
    registerReceiver(broadcastReceiver, filter)
  }

  override fun onDestroy() {
    super.onDestroy()
//    unregisterReceiver(broadcastReceiver)
  }

  override fun onPostCreate(savedInstanceState: Bundle?) {
    super.onPostCreate(savedInstanceState)

    mConnection = object : ServiceConnection {
      override fun onServiceDisconnected(name: ComponentName) {
        Log.w(tag, "Service Disconnected")
        mBounded = false
      }

      override fun onServiceConnected(name: ComponentName, service: IBinder) {
        Log.d(tag, "Service Connected")


        mBounded = true
        val mLocalBinder = service as PlayerNotificationService.LocalBinder
        foregroundService = mLocalBinder.getService()

        // Let MyNativeAudio know foreground service is ready and setup event listener
        if (pluginCallback != null) {
          pluginCallback()
        }
      }
    }

    val startIntent = Intent(this, PlayerNotificationService::class.java)
    bindService(startIntent, mConnection as ServiceConnection, Context.BIND_AUTO_CREATE);
  }


  fun stopMyService() {
    if (mBounded) {
      mConnection?.let { unbindService(it) };
      mBounded = false;
    }
    val stopIntent = Intent(this, PlayerNotificationService::class.java)
    stopService(stopIntent)
  }

  fun registerBroadcastReceiver(cb: (String, Long) -> Unit) {
    downloaderCallback = cb
  }
}
