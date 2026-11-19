package com.audiobookshelf.app.device

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.WearableListenerService

/** Receives sign in and relay requests from the Wear OS app, even while the phone app is closed. */
class WatchListenerService : WearableListenerService() {
  override fun onChannelOpened(channel: ChannelClient.Channel) {
    WatchBridge.handle(this, channel)
  }
}
