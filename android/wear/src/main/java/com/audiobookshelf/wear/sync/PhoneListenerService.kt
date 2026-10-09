package com.audiobookshelf.wear.sync

import com.audiobookshelf.wear.WearApp
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** Receives server connection updates from the phone app, even while the watch app is closed. */
class PhoneListenerService : WearableListenerService() {
  override fun onDataChanged(dataEvents: DataEventBuffer) {
    val phoneConfig = (application as WearApp).phoneConfig
    dataEvents.forEach { event ->
      if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == PhoneConfig.PATH_SERVER) {
        phoneConfig.update(DataMapItem.fromDataItem(event.dataItem).dataMap)
      }
    }
  }
}
