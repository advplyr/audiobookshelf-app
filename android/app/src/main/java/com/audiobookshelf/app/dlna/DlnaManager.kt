package com.audiobookshelf.app.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import net.mm2d.upnp.ControlPoint
import net.mm2d.upnp.ControlPointFactory
import net.mm2d.upnp.Device
import net.mm2d.upnp.Service
import java.io.IOException

class DlnaManager(private val context: Context) {
    private val tag = "DlnaManager"

    private var controlPoint: ControlPoint? = null
    private var wifiLock: WifiManager.MulticastLock? = null

    private val discoveredDevices = mutableMapOf<String, DlnaDevice>()
    private var connectedDevice: DlnaDevice? = null
    private var callback: DlnaCallback? = null

    private var isDiscovering = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pollingIntervalMs = 1000L
    private var positionPollingRunnable: Runnable? = null
    private var isPolling = false
    @Volatile private var positionPollInFlight = false
    private var lastKnownTrackDurationMs = 0L

    private val eventListener = object : ControlPoint.EventListener {
        override fun onEvent(service: Service, seq: Long, properties: List<Pair<String, String>>) {
            if (service != connectedDevice?.avTransportService) return
            val lastChangeXml = properties.find { it.first == "LastChange" }?.second ?: return
            Log.d(tag, "AVTransport LastChange: $lastChangeXml")
            val transportState = parseTransportStateFromLastChange(lastChangeXml) ?: return
            Log.d(tag, "TransportState from event: $transportState")
            if (transportState == "STOPPED" && lastKnownTrackDurationMs > 0) {
                mainHandler.post {
                    Log.d(tag, "Track ended via UPnP event (lastDuration=${lastKnownTrackDurationMs}ms)")
                    lastKnownTrackDurationMs = 0L
                    callback?.onTrackEnded()
                }
            }
        }
    }

    private fun parseTransportStateFromLastChange(xml: String): String? {
        return try {
            Regex("""TransportState[^>]*val="([^"]+)"""").find(xml)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse LastChange XML", e)
            null
        }
    }

    private val discoveryListener = object : ControlPoint.DiscoveryListener {
        override fun onDiscover(device: Device) {
            Log.d(tag, "=== Device discovered ===")
            Log.d(tag, "  Name: ${device.friendlyName}")
            Log.d(tag, "  Type: ${device.deviceType}")
            Log.d(tag, "  UDN: ${device.udn}")
            Log.d(tag, "  Location: ${device.location}")
            Log.d(tag, "  Services: ${device.serviceList.map { it.serviceType }}")

            val dlnaDevice = DlnaDevice.fromDevice(device)
            Log.d(tag, "  -> AVTransport: ${dlnaDevice.avTransportService != null}")
            Log.d(tag, "  -> RenderingControl: ${dlnaDevice.renderingControlService != null}")

            if (dlnaDevice.isValid) {
                Log.d(tag, "  -> ACCEPTED (has AVTransport)")
                discoveredDevices[dlnaDevice.id] = dlnaDevice
                notifyDevicesUpdated()
            } else {
                Log.d(tag, "  -> Skipped (no AVTransport service)")
            }
        }

        override fun onLost(device: Device) {
            Log.d(tag, "Device lost: ${device.friendlyName}")
            discoveredDevices.remove(device.udn)
            notifyDevicesUpdated()
        }
    }

    fun setCallback(callback: DlnaCallback?) {
        this.callback = callback
    }

    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(tag, "Discovery already running")
            return
        }

        Log.d(tag, "Starting DLNA discovery")
        isDiscovering = true

        if (!acquireWifiLock()) {
            Log.e(tag, "Failed to acquire multicast lock, discovery may not work")
        }

        try {
            controlPoint = ControlPointFactory.create().also { cp ->
                cp.addDiscoveryListener(discoveryListener)
                cp.addEventListener(eventListener)
                cp.initialize()
                cp.start()
                Log.d(tag, "ControlPoint started, waiting before search...")
            }

            mainHandler.postDelayed({
                controlPoint?.let { cp ->
                    Log.d(tag, "Sending ssdp:all search")
                    cp.search()

                    mainHandler.postDelayed({
                        Log.d(tag, "Sending MediaRenderer-specific search")
                        cp.search("urn:schemas-upnp-org:device:MediaRenderer:1")
                    }, 1000)
                }
            }, 500)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start UPnP control point", e)
            callback?.onError("Failed to start device discovery: ${e.message}")
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return

        Log.d(tag, "Stopping DLNA discovery")
        isDiscovering = false

        try {
            controlPoint?.removeDiscoveryListener(discoveryListener)
            controlPoint?.removeEventListener(eventListener)
            controlPoint?.stop()
            controlPoint?.terminate()
            controlPoint = null
        } catch (e: Exception) {
            Log.e(tag, "Error stopping UPnP control point", e)
        }

        releaseWifiLock()
    }

    fun getDevices(): List<DlnaDevice> {
        return discoveredDevices.values.toList()
    }

    fun getConnectedDevice(): DlnaDevice? {
        return connectedDevice
    }

    fun connectToDevice(deviceId: String): Boolean {
        val device = discoveredDevices[deviceId]
        if (device == null) {
            Log.e(tag, "Device not found: $deviceId")
            callback?.onError("Device not found")
            return false
        }

        connectedDevice = device
        Thread {
            try {
                device.avTransportService?.subscribe(true) { success ->
                    Log.d(tag, "AVTransport subscribe: $success")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to subscribe to AVTransport events", e)
            }
        }.start()
        callback?.onDeviceConnected(device)
        return true
    }

    fun disconnect() {
        Thread {
            try {
                connectedDevice?.avTransportService?.unsubscribe()
            } catch (e: Exception) {
                Log.e(tag, "Failed to unsubscribe from AVTransport events", e)
            }
        }.start()
        stopPositionPolling()
        connectedDevice = null
        callback?.onDeviceDisconnected()
    }

    fun play(mediaUrl: String, metadata: String?, onComplete: ((Boolean) -> Unit)? = null) {
        val device = connectedDevice
        if (device == null) {
            Log.e(tag, "No device connected")
            onComplete?.invoke(false)
            return
        }

        val avTransport = device.avTransportService
        if (avTransport == null) {
            Log.e(tag, "AVTransport service not available")
            onComplete?.invoke(false)
            return
        }

        lastKnownTrackDurationMs = 0L
        Log.d(tag, "Setting AV Transport URI: $mediaUrl")
        Log.d(tag, "Metadata: $metadata")

        Thread {
            try {
                val setUriAction = avTransport.findAction("SetAVTransportURI")
                if (setUriAction == null) {
                    Log.e(tag, "SetAVTransportURI action not found")
                    mainHandler.post { onComplete?.invoke(false) }
                    return@Thread
                }

                val setUriArgs = mapOf(
                    "InstanceID" to "0",
                    "CurrentURI" to mediaUrl,
                    "CurrentURIMetaData" to (metadata ?: "")
                )

                try {
                    setUriAction.invokeSync(setUriArgs)
                    Log.d(tag, "SetAVTransportURI success, now playing")
                } catch (e: IOException) {
                    Log.e(tag, "SetAVTransportURI failed", e)
                    Log.e(tag, "Failed URL: $mediaUrl")
                    Log.e(tag, "Failed metadata: $metadata")
                    mainHandler.post {
                        callback?.onError("Failed to set media URL: ${e.message}")
                        onComplete?.invoke(false)
                    }
                    return@Thread
                }

                val playAction = avTransport.findAction("Play")
                if (playAction == null) {
                    Log.e(tag, "Play action not found")
                    mainHandler.post { onComplete?.invoke(false) }
                    return@Thread
                }

                val playArgs = mapOf(
                    "InstanceID" to "0",
                    "Speed" to "1"
                )

                try {
                    playAction.invokeSync(playArgs)
                    Log.d(tag, "Play success - starting position polling")
                    mainHandler.post {
                        callback?.onPlaybackStateChanged(true)
                        startPositionPolling()
                        onComplete?.invoke(true)
                    }
                } catch (e: IOException) {
                    Log.e(tag, "Play failed", e)
                    mainHandler.post {
                        callback?.onError("Failed to play: ${e.message}")
                        onComplete?.invoke(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during play", e)
                mainHandler.post {
                    callback?.onError("Error: ${e.message}")
                    onComplete?.invoke(false)
                }
            }
        }.start()
    }

    fun pause() {
        val device = connectedDevice ?: return
        val avTransport = device.avTransportService ?: return

        Thread {
            try {
                val pauseAction = avTransport.findAction("Pause")
                if (pauseAction != null) {
                    val args = mapOf("InstanceID" to "0")
                    pauseAction.invokeSync(args)
                    Log.d(tag, "Pause success")
                    mainHandler.post {
                        stopPositionPolling()
                        callback?.onPlaybackStateChanged(false)
                    }
                }
            } catch (e: IOException) {
                Log.e(tag, "Pause failed", e)
            }
        }.start()
    }

    fun resume() {
        val device = connectedDevice ?: return
        val avTransport = device.avTransportService ?: return

        Thread {
            try {
                val playAction = avTransport.findAction("Play")
                if (playAction != null) {
                    val args = mapOf(
                        "InstanceID" to "0",
                        "Speed" to "1"
                    )
                    playAction.invokeSync(args)
                    Log.d(tag, "Resume success")
                    mainHandler.post {
                        startPositionPolling()
                        callback?.onPlaybackStateChanged(true)
                    }
                }
            } catch (e: IOException) {
                Log.e(tag, "Resume failed", e)
            }
        }.start()
    }

    fun stop() {
        val device = connectedDevice ?: return
        val avTransport = device.avTransportService ?: return
        stopPositionPolling()

        Thread {
            try {
                val stopAction = avTransport.findAction("Stop")
                if (stopAction != null) {
                    val args = mapOf("InstanceID" to "0")
                    stopAction.invokeSync(args)
                    Log.d(tag, "Stop success")
                    mainHandler.post { callback?.onPlaybackStateChanged(false) }
                }
            } catch (e: IOException) {
                Log.e(tag, "Stop failed", e)
            }
        }.start()
    }

    fun seek(positionMs: Long) {
        val device = connectedDevice ?: return
        val avTransport = device.avTransportService ?: return

        val timeString = formatTime(positionMs)
        Log.d(tag, "Seeking to: $timeString")

        Thread {
            try {
                val seekAction = avTransport.findAction("Seek")
                if (seekAction != null) {
                    val args = mapOf(
                        "InstanceID" to "0",
                        "Unit" to "REL_TIME",
                        "Target" to timeString
                    )
                    seekAction.invokeSync(args)
                    Log.d(tag, "Seek success")
                }
            } catch (e: IOException) {
                Log.e(tag, "Seek failed", e)
                mainHandler.post { callback?.onError("Failed to seek: ${e.message}") }
            }
        }.start()
    }

    fun setNextTrack(mediaUrl: String, metadata: String?, onComplete: ((Boolean) -> Unit)? = null) {
        val device = connectedDevice
        if (device == null) {
            Log.e(tag, "setNextTrack: No device connected")
            onComplete?.invoke(false)
            return
        }

        val avTransport = device.avTransportService
        if (avTransport == null) {
            Log.e(tag, "setNextTrack: AVTransport service not available")
            onComplete?.invoke(false)
            return
        }

        Thread {
            try {
                val setNextUriAction = avTransport.findAction("SetNextAVTransportURI")
                if (setNextUriAction == null) {
                    Log.w(tag, "SetNextAVTransportURI action not supported by device")
                    mainHandler.post { onComplete?.invoke(false) }
                    return@Thread
                }

                val args = mapOf(
                    "InstanceID" to "0",
                    "NextURI" to mediaUrl,
                    "NextURIMetaData" to (metadata ?: "")
                )

                try {
                    setNextUriAction.invokeSync(args)
                    Log.d(tag, "SetNextAVTransportURI success: $mediaUrl")
                    mainHandler.post { onComplete?.invoke(true) }
                } catch (e: IOException) {
                    Log.e(tag, "SetNextAVTransportURI failed", e)
                    mainHandler.post {
                        callback?.onError("Failed to set next track: ${e.message}")
                        onComplete?.invoke(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error setting next track", e)
                mainHandler.post {
                    callback?.onError("Error: ${e.message}")
                    onComplete?.invoke(false)
                }
            }
        }.start()
    }

    fun setVolume(volume: Int) {
        val device = connectedDevice ?: return
        val renderingControl = device.renderingControlService ?: return

        Thread {
            try {
                val setVolumeAction = renderingControl.findAction("SetVolume")
                if (setVolumeAction != null) {
                    val args = mapOf(
                        "InstanceID" to "0",
                        "Channel" to "Master",
                        "DesiredVolume" to volume.toString()
                    )
                    setVolumeAction.invokeSync(args)
                    Log.d(tag, "SetVolume success: $volume")
                }
            } catch (e: IOException) {
                Log.e(tag, "SetVolume failed", e)
            }
        }.start()
    }

    fun getVolume(onResult: (Int) -> Unit) {
        val device = connectedDevice
        if (device == null) {
            Log.w(tag, "getVolume: No connected device")
            mainHandler.post { onResult(0) }
            return
        }

        val renderingControl = device.renderingControlService
        if (renderingControl == null) {
            Log.w(tag, "getVolume: Device has no renderingControlService")
            mainHandler.post { onResult(0) }
            return
        }

        Log.d(tag, "getVolume: Querying device volume...")
        Thread {
            try {
                val getVolumeAction = renderingControl.findAction("GetVolume")
                if (getVolumeAction != null) {
                    val args = mapOf(
                        "InstanceID" to "0",
                        "Channel" to "Master"
                    )
                    val result = getVolumeAction.invokeSync(args)
                    val currentVolume = result["CurrentVolume"]?.toIntOrNull() ?: 0
                    Log.d(tag, "GetVolume success: $currentVolume")
                    mainHandler.post { onResult(currentVolume) }
                } else {
                    Log.w(tag, "getVolume: GetVolume action not found")
                    mainHandler.post { onResult(0) }
                }
            } catch (e: IOException) {
                Log.e(tag, "GetVolume failed", e)
                mainHandler.post { onResult(0) }
            }
        }.start()
    }

    fun getPositionInfo(onResult: (positionMs: Long, durationMs: Long) -> Unit) {
        val device = connectedDevice ?: return
        val avTransport = device.avTransportService ?: return

        if (positionPollInFlight) return
        positionPollInFlight = true

        Thread {
            try {
                val getPositionAction = avTransport.findAction("GetPositionInfo")
                if (getPositionAction != null) {
                    val args = mapOf("InstanceID" to "0")
                    val result = getPositionAction.invokeSync(args)
                    val relTime = result["RelTime"] ?: "0:00:00"
                    val trackDuration = result["TrackDuration"] ?: "0:00:00"
                    val position = parseTimeToMs(relTime)
                    val duration = parseTimeToMs(trackDuration)
                    mainHandler.post { onResult(position, duration) }
                } else {
                    mainHandler.post { onResult(0, 0) }
                }
            } catch (e: IOException) {
                Log.e(tag, "GetPositionInfo failed", e)
                mainHandler.post { onResult(0, 0) }
            } finally {
                positionPollInFlight = false
            }
        }.start()
    }

    private fun parseTimeToMs(timeString: String): Long {
        val parts = timeString.split(":")
        if (parts.size != 3) return 0L
        return try {
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val seconds = parts[2].split(".")[0].toLong()
            (hours * 3600 + minutes * 60 + seconds) * 1000
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse time string: $timeString", e)
            0L
        }
    }

    fun getTransportState(onResult: (String) -> Unit) {
        val device = connectedDevice ?: return
        val avTransport = device.avTransportService ?: return

        Thread {
            try {
                val getTransportAction = avTransport.findAction("GetTransportInfo")
                if (getTransportAction != null) {
                    val args = mapOf("InstanceID" to "0")
                    val result = getTransportAction.invokeSync(args)
                    val state = result["CurrentTransportState"] ?: "STOPPED"
                    mainHandler.post { onResult(state) }
                } else {
                    mainHandler.post { onResult("STOPPED") }
                }
            } catch (e: IOException) {
                Log.e(tag, "GetTransportInfo failed", e)
                mainHandler.post { onResult("STOPPED") }
            }
        }.start()
    }

    private fun startPositionPolling() {
        lastKnownTrackDurationMs = 0L
        
        if (isPolling) {
            Log.d(tag, "Position polling already running, reset track duration")
            return
        }
        
        isPolling = true
        Log.d(tag, "Starting position polling")

        positionPollingRunnable = object : Runnable {
            override fun run() {
                if (!isPolling) return
                
                getPositionInfo { positionMs, durationMs ->
                    if (durationMs > 0 && positionMs > 0) {
                        lastKnownTrackDurationMs = durationMs
                        callback?.onPositionUpdate(positionMs, durationMs)
                    }
                    // positionMs == 0: skip — preserves last known position in UI
                }
                
                mainHandler.postDelayed(this, pollingIntervalMs)
            }
        }
        mainHandler.post(positionPollingRunnable!!)
    }

    private fun stopPositionPolling() {
        isPolling = false
        positionPollInFlight = false
        lastKnownTrackDurationMs = 0L
        positionPollingRunnable?.let { mainHandler.removeCallbacks(it) }
        positionPollingRunnable = null
    }

    private fun notifyDevicesUpdated() {
        mainHandler.post {
            callback?.onDevicesUpdated(getDevices())
        }
    }

    private fun acquireWifiLock(): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createMulticastLock("audiobookshelf_dlna")
            wifiLock?.setReferenceCounted(true)
            wifiLock?.acquire()
            val held = wifiLock?.isHeld == true
            Log.d(tag, "Multicast lock acquired: $held")
            held
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire multicast lock", e)
            false
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.release()
            wifiLock = null
            Log.d(tag, "Multicast lock released")
        } catch (e: Exception) {
            Log.e(tag, "Failed to release multicast lock", e)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d:%02d", hours, minutes, seconds)
    }

    fun shutdown() {
        disconnect()
        stopDiscovery()
        discoveredDevices.clear()
    }
}
