package com.audiobookshelf.app

import android.util.Log
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LibraryShelfType
import com.audiobookshelf.app.data.MoshiProvider.Companion.fromJson
import com.audiobookshelf.app.data.MoshiProvider.Companion.toJsonWithNulls
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.squareup.moshi.JsonClass
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.skyscreamer.jsonassert.JSONAssert
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue

fun log(s: String) = Log.i("Serialization", s)
val Duration.ms: String
  get() = this.toString(DurationUnit.MILLISECONDS, 3)

@JsonClass(generateAdapter = true)
data class LibraryItems(
  val libraryItems: List<LibraryItem> = listOf()
)


@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SerializationInstrumentedTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `1personalizedTest`() {
    val jom = jacksonObjectMapper()
    for (jsonFile in listOf("/personalized.json", "/personalized2.json")) {
      val json = object {}.javaClass.getResourceAsStream(jsonFile)
        ?.readBytes()
        ?.decodeToString()!!

      val (jacksonDeserialized, jacksonTime) = measureTimedValue {
        jom.readValue<List<LibraryShelfType>>(json)
      }

      val (moshiDeserialized, moshiTime) = measureTimedValue {
        fromJson<List<LibraryShelfType>>(json)!!
      }

      log("Jackson: ${jacksonTime.ms}, Moshi: ${moshiTime.ms}")
      JSONAssert.assertEquals(
        jom.writeValueAsString(jacksonDeserialized),
        jom.writeValueAsString(moshiDeserialized),
        true
      )
      JSONAssert.assertEquals(
        jom.writeValueAsString(jacksonDeserialized),
        toJsonWithNulls(moshiDeserialized),
        true
      )
    }
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `2itemsInProgressTest`() {
    val content = object {}.javaClass.getResourceAsStream("/items-in-progress.json")
      ?.readBytes()
      ?.decodeToString()!!

    val jom = jacksonObjectMapper()

    val (jacksonDeserialized, jacksonTime) = measureTimedValue {
      jom.readValue<LibraryItems>(content)
    }

    val (moshiDeserialized, moshiTime) = measureTimedValue {
      fromJson<LibraryItems>(content)!!
    }

    log("Jackson: ${jacksonTime.ms}, Moshi: ${moshiTime.ms}")
    JSONAssert.assertEquals(
      jom.writeValueAsString(jacksonDeserialized),
      toJsonWithNulls(moshiDeserialized),
      true
    )
    JSONAssert.assertEquals(
      jom.writeValueAsString(jacksonDeserialized),
      toJsonWithNulls(moshiDeserialized),
      true
    )
  }

}
