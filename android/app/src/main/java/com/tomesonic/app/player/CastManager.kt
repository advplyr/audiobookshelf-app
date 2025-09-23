// MIGRATION-DEFERRED: CAST - This entire file is temporarily disabled for migration
package com.tomesonic.app.player

import android.content.Context
import org.json.JSONObject

// Temporary stub to prevent compilation errors during migration
class CastManager(
    private val context: Context
) {
    companion object {
        const val PLAYER_EXO = "exo"
        const val PLAYER_CAST = "cast"
    }

    abstract class ChromecastListener {
        open fun onReceiverAvailableUpdate(available: Boolean) {}
        open fun onSessionRejoin(success: Boolean) {}
        open fun onMediaLoaded() {}
        open fun onMediaUpdate() {}
        open fun onSessionUpdate() {}
        open fun onSessionEnd() {}
        open fun onMessageReceived(device: Any, namespace: String, message: String) {}
    }

    abstract class RequestSessionCallback {
        open fun onError(errorCode: Int) {}
        open fun onCancel() {}
        open fun onJoin(jsonSession: JSONObject?) {}
    }

    fun startRouteScan(listener: ChromecastListener) {
        // Stub implementation - no actual cast functionality during migration
    }

    fun requestSession(service: Any, callback: RequestSessionCallback) {
        // Stub implementation - no actual cast functionality during migration
    }
}
