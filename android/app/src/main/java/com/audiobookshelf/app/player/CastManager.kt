package com.audiobookshelf.app.player

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.R
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.getcapacitor.PluginCall
import com.google.android.exoplayer2.ext.cast.SessionAvailabilityListener
import com.google.android.gms.cast.*
import com.google.android.gms.cast.framework.*
import org.json.JSONObject

class CastManager constructor(val mainActivity:Activity) {
  private val tag = "CastManager"

  private var playerNotificationService:PlayerNotificationService? = null
  private var newConnectionListener: SessionListener? = null

  private fun switchToPlayer(useCastPlayer:Boolean) {
    Handler(Looper.getMainLooper()).post() {
      playerNotificationService?.switchToPlayer(useCastPlayer)
    }
  }

  private inner class CastSessionAvailabilityListener : SessionAvailabilityListener {

    /**
     * Called when a Cast session has started and the user wishes to control playback on a
     * remote Cast receiver rather than play audio locally.
     */
    override fun onCastSessionAvailable() {
      Log.d(tag, "SessionAvailabilityListener: onCastSessionAvailable")
      switchToPlayer(true)
    }

    /**
     * Called when a Cast session has ended and the user wishes to control playback locally.
     */
    override fun onCastSessionUnavailable() {
      Log.d(tag, "onCastSessionUnavailable")
      switchToPlayer(false)
    }
  }

  fun requestSession(playerNotificationService: PlayerNotificationService, callback: RequestSessionCallback) {
    this.playerNotificationService = playerNotificationService

    mainActivity.runOnUiThread {
      val session: CastSession? = getSession()
      if (session == null) {
        // show the "choose a connection" dialog
        // Add the connection listener callback
        listenForConnection(callback)

        val builder = MediaRouteChooserDialog(mainActivity, R.style.Theme_AppCompat_NoActionBar)
        builder.routeSelector = MediaRouteSelector.Builder()
          .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
          .build()
        builder.setCanceledOnTouchOutside(true)
        builder.setOnCancelListener {
          newConnectionListener?.let { ncl ->
            getSessionManager()?.removeSessionManagerListener(ncl, CastSession::class.java)
          }
          callback.onCancel()
        }
        builder.show()
      } else {
        // We are are already connected, so show the "connection options" Dialog
        val builder: AlertDialog.Builder = AlertDialog.Builder(mainActivity)
        session.castDevice?.let {
          builder.setTitle(it.friendlyName)
        }
        builder.setOnDismissListener { callback.onCancel() }
        builder.setPositiveButton("Stop Casting") { dialog, which -> endSession(true, null) }
        builder.show()
      }
    }
  }

  abstract class RequestSessionCallback : ConnectionCallback {
    abstract fun onError(errorCode: Int)
    abstract fun onCancel()
    override fun onSessionEndedBeforeStart(errorCode: Int): Boolean {
      onSessionStartFailed(errorCode)
      return true
    }

    override fun onSessionStartFailed(errorCode: Int): Boolean {
      onError(errorCode)
      return true
    }
  }

  fun endSession(stopCasting: Boolean, pluginCall: PluginCall?) {

    getSessionManager()!!.addSessionManagerListener(object : SessionListener() {
      override fun onSessionEnded(castSession: CastSession, error: Int) {
        getSessionManager()!!.removeSessionManagerListener(this, CastSession::class.java)
        Log.d(tag, "CAST END SESSION")
        pluginCall?.resolve()
      }
    }, CastSession::class.java)
    getSessionManager()!!.endCurrentSession(stopCasting)

  }

  open class SessionListener : SessionManagerListener<CastSession> {
    override fun onSessionStarting(castSession: CastSession) {}
    override fun onSessionStarted(castSession: CastSession, sessionId: String) {}
    override fun onSessionStartFailed(castSession: CastSession, error: Int) {}
    override fun onSessionEnding(castSession: CastSession) {}
    override fun onSessionEnded(castSession: CastSession, error: Int) {}
    override fun onSessionResuming(castSession: CastSession, sessionId: String) {}
    override fun onSessionResumed(castSession: CastSession, wasSuspended: Boolean) {}
    override fun onSessionResumeFailed(castSession: CastSession, error: Int) {}
    override fun onSessionSuspended(castSession: CastSession, reason: Int) {}
  }

  fun startRouteScan(connListener:ChromecastListener) {
    var callback = object : ScanCallback() {
      override fun onRouteUpdate(routes: List<MediaRouter.RouteInfo>?) {
        Log.d(tag, "CAST On ROUTE UPDATED ${routes?.size} | ${getContext().castState}")
        // if the routes have changed, we may have an available device
        // If there is at least one device available
        if (getContext().castState != CastState.NO_DEVICES_AVAILABLE) {
          routes?.forEach { Log.d(tag, "CAST ROUTE ${it.description} | ${it.deviceType} | ${it.isBluetooth} | ${it.name}") }

          // Stop the scan
          stopRouteScan(this, null)
          // Let the client know a receiver is available
          connListener.onReceiverAvailableUpdate(true)
          // Since we have a receiver we may also have an active session
          var session = getSessionManager()?.currentCastSession
          // If we do have a session
          if (session != null) {
            // Let the client know
          }
        } else {
          Log.d(tag, "No cast devices available")
        }
      }
    }

    callback.setMediaRouter(getMediaRouter())

    callback.onFilteredRouteUpdate()

    getMediaRouter()?.addCallback(MediaRouteSelector.Builder()
      .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
      .build(),
      callback,
      MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
  }

  internal interface CastListener : Cast.MessageReceivedCallback {
    fun onMediaLoaded(jsonMedia: JSONObject?)
    fun onMediaUpdate(jsonMedia: JSONObject?)
    fun onSessionUpdate(jsonSession: JSONObject?)
    fun onSessionEnd(jsonSession: JSONObject?)
  }

  abstract class ChromecastListener : CastStateListener, CastListener {
    abstract fun onReceiverAvailableUpdate(available: Boolean)
    abstract fun onSessionRejoin(jsonSession: JSONObject?)

    /** CastStateListener functions.  */
    override fun onCastStateChanged(state: Int) {
      onReceiverAvailableUpdate(state != CastState.NO_DEVICES_AVAILABLE)
    }
  }

  fun stopRouteScan(callback: ScanCallback?, completionCallback: Runnable?) {
    Log.d(tag, "stopRouteScan")
    if (callback == null) {
      completionCallback?.run()
      return
    }

//    mainActivity.runOnUiThread {
      Log.d(tag, "Removing callback on media router")
      callback.stop()
      getMediaRouter()?.removeCallback(callback)
      completionCallback?.run()
//    }

  }

  abstract class ScanCallback : MediaRouter.Callback() {
    /**
     * Called whenever a route is updated.
     * @param routes the currently available routes
     */
    abstract fun onRouteUpdate(routes: List<MediaRouter.RouteInfo>?)

    /** records whether we have been stopped or not.  */
    private var stopped = false

    /** Global mediaRouter object.  */
    private var mediaRouter: MediaRouter? = null

    /**
     * Sets the mediaRouter object.
     * @param router mediaRouter object
     */
    fun setMediaRouter(router: MediaRouter?) {
      mediaRouter = router
    }

    /**
     * Call this method when you wish to stop scanning.
     * It is important that it is called, otherwise battery
     * life will drain more quickly.
     */
    fun stop() {
      stopped = true
    }

    fun onFilteredRouteUpdate() {
      if (stopped || mediaRouter == null) {
        return
      }
      val outRoutes: MutableList<MediaRouter.RouteInfo> = ArrayList()
      // Filter the routes
      for (route in mediaRouter!!.routes) {
        // We don't want default routes, or duplicate active routes
        // or multizone duplicates https://github.com/jellyfin/cordova-plugin-chromecast/issues/32
        val extras: Bundle? = route.extras
        if (extras != null) {
          CastDevice.getFromBundle(extras)
          if (extras.getString("com.google.android.gms.cast.EXTRA_SESSION_ID") != null) {
            continue
          }
        }
        if (!route.isDefault
          && !route.description.equals("Google Cast Multizone Member")
          && route.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE) {
          outRoutes.add(route)
        }
      }
      onRouteUpdate(outRoutes)
    }

    override fun onRouteAdded(router: MediaRouter?, route: MediaRouter.RouteInfo?) {
      onFilteredRouteUpdate()
    }

    override fun onRouteChanged(router: MediaRouter?, route: MediaRouter.RouteInfo?) {
      onFilteredRouteUpdate()
    }

    override fun onRouteRemoved(router: MediaRouter?, route: MediaRouter.RouteInfo?) {
      onFilteredRouteUpdate()
    }
  }

  private fun listenForConnection(callback: ConnectionCallback) {
    // We should only ever have one of these listeners active at a time, so remove previous
    newConnectionListener?.let { ncl ->
      getSessionManager()?.removeSessionManagerListener(ncl, CastSession::class.java)
    }

    newConnectionListener = object : SessionListener() {
      override fun onSessionStarted(castSession: CastSession, sessionId: String) {
        Log.d(tag, "CAST SESSION STARTED ${castSession.castDevice?.friendlyName}")
        getSessionManager()?.removeSessionManagerListener(this, CastSession::class.java)

        val castContext = CastContext.getSharedInstance(mainActivity)

        playerNotificationService?.let {
          if (it.castPlayer == null) {
            Log.d(tag, "Initializing castPlayer on session started - switch to cast player")
            it.castPlayer = CastPlayer(castContext).apply {
              addListener(PlayerListener(it))
              setSessionAvailabilityListener(CastSessionAvailabilityListener())
            }
            switchToPlayer(true)
          } else {
            Log.d(tag, "castPlayer is already initialized on session started")
          }
        }
      }

      override fun onSessionStartFailed(castSession: CastSession, errCode: Int) {
        if (callback.onSessionStartFailed(errCode)) {
          getSessionManager()?.removeSessionManagerListener(this, CastSession::class.java)
        }
      }

      override fun onSessionEnded(castSession: CastSession, errCode: Int) {
        if (callback.onSessionEndedBeforeStart(errCode)) {
          getSessionManager()?.removeSessionManagerListener(this, CastSession::class.java)
        }
      }
    }

    newConnectionListener?.let {
      Log.d(tag, "Add session manager listener")
      getSessionManager()?.addSessionManagerListener(it, CastSession::class.java)
    }
  }

  private fun getContext(): CastContext {
    return CastContext.getSharedInstance(mainActivity)
  }

  private fun getSessionManager(): SessionManager? {
    return getContext().sessionManager
  }

  private fun getMediaRouter(): MediaRouter? {
    return MediaRouter.getInstance(mainActivity)
  }

  private fun getSession(): CastSession? {
    return getSessionManager()?.currentCastSession
  }

  internal interface ConnectionCallback {
    /**
     * Successfully joined a session on a route.
     * @param jsonSession the session we joined
     */
    fun onJoin(jsonSession: JSONObject?)

    /**
     * Called if we received an error.
     * @param errorCode You can find the error meaning here:
     * https://developers.google.com/android/reference/com/google/android/gms/cast/CastStatusCodes
     * @return true if we are done listening for join, false, if we to keep listening
     */
    fun onSessionStartFailed(errorCode: Int): Boolean

    /**
     * Called when we detect a session ended event before session started.
     * See issues:
     * https://github.com/jellyfin/cordova-plugin-chromecast/issues/49
     * https://github.com/jellyfin/cordova-plugin-chromecast/issues/48
     * @param errorCode error to output
     * @return true if we are done listening for join, false, if we to keep listening
     */
    fun onSessionEndedBeforeStart(errorCode: Int): Boolean
  }
}
