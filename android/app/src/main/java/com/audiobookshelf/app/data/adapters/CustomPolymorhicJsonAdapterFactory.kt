/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.audiobookshelf.app.data.adapters

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonReader.Options
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.rawType
import okio.IOException
import java.lang.reflect.Type

// based on https://github.com/square/moshi/blob/master/moshi-adapters/src/main/java/com/squareup/moshi/adapters/PolymorphicJsonAdapterFactory.kt
// helps with https://github.com/square/moshi/issues/874 in the LibraryShelfType hierarchy
// TLDR: prevents double inclusion of the "type" property when serializing to JSON

class CustomPolymorphicJsonAdapterFactory<T> internal constructor(
  private val baseType: Class<T>,
  private val labelKey: String,
  private val labels: List<String>,
  private val subtypes: List<Type>,
  private val fallbackJsonAdapter: JsonAdapter<Any>?,
) : Factory {
  /** Returns a new factory that decodes instances of `subtype`. */
  fun withSubtype(subtype: Class<out T>, label: String): CustomPolymorphicJsonAdapterFactory<T> {
    require(!labels.contains(label)) { "Labels must be unique." }
    val newLabels = buildList {
      addAll(labels)
      add(label)
    }
    val newSubtypes = buildList {
      addAll(subtypes)
      add(subtype)
    }
    return CustomPolymorphicJsonAdapterFactory(
      baseType = baseType,
      labelKey = labelKey,
      labels = newLabels,
      subtypes = newSubtypes,
      fallbackJsonAdapter = fallbackJsonAdapter,
    )
  }

  /**
   * Returns a new factory that with default to `fallbackJsonAdapter.fromJson(reader)` upon
   * decoding of unrecognized labels.
   *
   * The [JsonReader] instance will not be automatically consumed, so make sure to consume
   * it within your implementation of [JsonAdapter.fromJson]
   */
  fun withFallbackJsonAdapter(
    fallbackJsonAdapter: JsonAdapter<Any>?,
  ): CustomPolymorphicJsonAdapterFactory<T> {
    return CustomPolymorphicJsonAdapterFactory(
      baseType = baseType,
      labelKey = labelKey,
      labels = labels,
      subtypes = subtypes,
      fallbackJsonAdapter = fallbackJsonAdapter,
    )
  }

  /**
   * Returns a new factory that will default to `defaultValue` upon decoding of unrecognized
   * labels. The default value should be immutable.
   */
  fun withDefaultValue(defaultValue: T?): CustomPolymorphicJsonAdapterFactory<T> {
    return withFallbackJsonAdapter(buildFallbackJsonAdapter(defaultValue))
  }

  private fun buildFallbackJsonAdapter(defaultValue: T?): JsonAdapter<Any> {
    return object : JsonAdapter<Any>() {
      override fun fromJson(reader: JsonReader): Any? {
        reader.skipValue()
        return defaultValue
      }

      override fun toJson(writer: JsonWriter, value: Any?) {
        throw IllegalArgumentException(
          "Expected one of $subtypes but found $value, a ${value?.javaClass}. Register this subtype.",
        )
      }
    }
  }

  override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
    if (type.rawType != baseType || annotations.isNotEmpty()) {
      return null
    }
    val jsonAdapters: List<JsonAdapter<Any>> = subtypes.map(moshi::adapter)
    return PolymorphicJsonAdapter(labelKey, labels, subtypes, jsonAdapters, fallbackJsonAdapter)
      .nullSafe()
  }

  internal class PolymorphicJsonAdapter(
    private val labelKey: String,
    private val labels: List<String>,
    private val subtypes: List<Type>,
    private val jsonAdapters: List<JsonAdapter<Any>>,
    private val fallbackJsonAdapter: JsonAdapter<Any>?,
  ) : JsonAdapter<Any>() {
    /** Single-element options containing the label's key only.  */
    private val labelKeyOptions: Options = Options.of(labelKey)

    /** Corresponds to subtypes.  */
    private val labelOptions: Options = Options.of(*labels.toTypedArray())

    override fun fromJson(reader: JsonReader): Any? {
      val peeked = reader.peekJson()
      val labelIndex = peeked.use(::labelIndex)
      return if (labelIndex == -1) {
        fallbackJsonAdapter?.fromJson(reader)
      } else {
        jsonAdapters[labelIndex].fromJson(reader)
      }
    }

    private fun labelIndex(reader: JsonReader): Int {
      reader.beginObject()
      while (reader.hasNext()) {
        if (reader.selectName(labelKeyOptions) == -1) {
          reader.skipName()
          reader.skipValue()
          continue
        }
        val labelIndex = reader.selectString(labelOptions)
        if (labelIndex == -1 && fallbackJsonAdapter == null) {
          throw JsonDataException(
            "Expected one of $labels for key '$labelKey' but found '${reader.nextString()}'. Register a subtype for this label.",
          )
        }
        return labelIndex
      }
      throw JsonDataException("Missing label for $labelKey")
    }

    @Throws(IOException::class)
    override fun toJson(writer: JsonWriter, value: Any?) {
      val type: Class<*> = value!!.javaClass
      val labelIndex = subtypes.indexOf(type)
      val adapter: JsonAdapter<Any> = if (labelIndex == -1) {
        requireNotNull(fallbackJsonAdapter) {
          "Expected one of $subtypes but found $value, a ${value.javaClass}. Register this subtype."
        }
      } else {
        jsonAdapters[labelIndex]
      }
      writer.beginObject()
      val flattenToken = writer.beginFlatten()
      adapter.toJson(writer, value)
      writer.endFlatten(flattenToken)
      writer.endObject()
    }

    override fun toString(): String {
      return "PolymorphicJsonAdapter($labelKey)"
    }
  }

  companion object {
    /**
     * @param baseType The base type for which this factory will create adapters. Cannot be Object.
     * @param labelKey The key in the JSON object whose value determines the type to which to map the
     * JSON object.
     */
    @JvmStatic
    fun <T> of(baseType: Class<T>, labelKey: String): CustomPolymorphicJsonAdapterFactory<T> {
      return CustomPolymorphicJsonAdapterFactory(
        baseType = baseType,
        labelKey = labelKey,
        labels = emptyList(),
        subtypes = emptyList(),
        fallbackJsonAdapter = null,
      )
    }
  }
}
