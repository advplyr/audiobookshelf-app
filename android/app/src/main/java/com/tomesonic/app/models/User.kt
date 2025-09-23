package com.tomesonic.app.models

import com.tomesonic.app.data.MediaProgress
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class User(
  val id:String,
  val username: String,
  val mediaProgress:List<MediaProgress>
)
