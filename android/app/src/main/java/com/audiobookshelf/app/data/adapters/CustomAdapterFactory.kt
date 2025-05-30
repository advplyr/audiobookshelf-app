package com.audiobookshelf.app.data.adapters

import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.Podcast
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import java.lang.reflect.Type
import kotlin.jvm.java

class CustomAdapterFactory : JsonAdapter.Factory {
  override fun create(
    type: Type,
    annotations: Set<Annotation?>,
    moshi: Moshi
  ): JsonAdapter<*>? = when (type) {
      LibraryItem::class.java -> LibraryItemJsonAdapter(moshi)
      Book::class.java -> BookJsonAdapter(moshi)
      Podcast::class.java -> PodcastJsonAdapter(moshi)
      else -> null
  }
}
