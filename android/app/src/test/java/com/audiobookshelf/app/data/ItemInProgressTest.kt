package com.audiobookshelf.app.data

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemInProgressTest {
  private val mapper = jacksonObjectMapper()

  @Test
  fun `makeFromServerObject parses a library item without a recent episode`() {
    val json = baseLibraryItemJson().apply { put("progressLastUpdate", 12_345L) }

    val itemInProgress = ItemInProgress.makeFromServerObject(json, mapper)

    assertEquals("server-item", itemInProgress.libraryItemWrapper.id)
    assertNull(itemInProgress.episode)
    assertEquals(12_345L, itemInProgress.progressLastUpdate)
    assertEquals(false, itemInProgress.isLocal)
  }

  @Test
  fun `makeFromServerObject parses an embedded recent episode`() {
    val json =
            baseLibraryItemJson().apply {
              put("progressLastUpdate", 12_345L)
              put("recentEpisode", JSONObject().apply { put("id", "ep1"); put("index", 1) })
            }

    val itemInProgress = ItemInProgress.makeFromServerObject(json, mapper)

    assertEquals("ep1", itemInProgress.episode?.id)
  }

  /**
   * Documents the current contract: `progressLastUpdate` is mandatory, and its absence throws out
   * of the parse rather than defaulting. `getAllItemsInProgress` maps every element through this,
   * so one malformed element fails the whole shelf - see the sibling spec below for that.
   */
  @Test(expected = JSONException::class)
  fun `makeFromServerObject throws when progressLastUpdate is missing`() {
    ItemInProgress.makeFromServerObject(baseLibraryItemJson(), mapper)
  }

  private fun baseLibraryItemJson(): JSONObject {
    val json =
            """
      {
        "id": "server-item",
        "ino": "ino",
        "libraryId": "library",
        "folderId": "folder",
        "path": "/book",
        "relPath": "book",
        "mtimeMs": 0,
        "ctimeMs": 0,
        "birthtimeMs": 0,
        "addedAt": 0,
        "updatedAt": 0,
        "isMissing": false,
        "isInvalid": false,
        "mediaType": "book",
        "media": {
          "metadata": {
            "title": "Title",
            "genres": [],
            "explicit": false,
            "subtitle": null
          },
          "coverPath": null,
          "tags": [],
          "tracks": []
        }
      }
    """
    return JSONObject(json)
  }
}
