package com.tomesonic.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class LibraryCategory(
  var id:String,
  var label:String,
  var type:String,
  var entities:List<LibraryItemWrapper>,
  var isLocal:Boolean
)
