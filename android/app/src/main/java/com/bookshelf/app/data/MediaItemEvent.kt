package com.bookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class MediaItemEvent(
  var name:String, // e.g. Play/Pause/Stop/Seek/Save
  var type:String, // Playback/Info
  var description:String?,
  var currentTime:Number?, // Seconds
  var serverSyncAttempted:Boolean?,
  var serverSyncSuccess:Boolean?,
  var serverSyncMessage:String?,
  var timestamp: Long
)
