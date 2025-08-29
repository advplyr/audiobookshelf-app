package com.audiobookshelf.app.data

import com.audiobookshelf.app.data.adapters.CustomAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter

@OptIn(ExperimentalStdlibApi::class)
class MoshiProvider {

  companion object {
    val moshi = Moshi.Builder()
      .add(libraryShelfTypePolymorphicAdapterFactory)
      .add(CustomAdapterFactory())
      .build()

    inline fun <reified T> toJson(o: T): String = moshi.adapter<T>().toJson(o)
    inline fun <reified T> toJsonWithNulls(o: T): String = moshi.adapter<T>().serializeNulls().toJson(o)
    inline fun <reified T> fromJson(s: String): T? = moshi.adapter<T>().fromJson(s)
  }
}
