package com.audiobookshelf.app.dlna

interface DlnaCallback {
    fun onDevicesUpdated(devices: List<DlnaDevice>)
    fun onDeviceConnected(device: DlnaDevice)
    fun onDeviceDisconnected()
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun onPositionUpdate(positionMs: Long, durationMs: Long)
    fun onTrackEnded()
    fun onError(message: String)
}
