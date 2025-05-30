package com.audiobookshelf.app.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import okio.Buffer

@OptIn(ExperimentalStdlibApi::class)
class MoshiProvider {

  companion object {
    val moshi = Moshi.Builder()
      .build()

    inline fun <reified T> toJson(o: T): String = moshi.adapter<T>().toJson(o)
    inline fun <reified T> fromJson(s: String): T? = moshi.adapter<T>().fromJson(s)
    inline fun <reified T> fromJson(buffer: Buffer): T? = moshi.adapter<T>().fromJson(buffer)
  }
}
