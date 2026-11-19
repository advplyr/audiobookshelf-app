package com.audiobookshelf.app

import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.Podcast
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastEpisodeReplayTest {
  private val mapper = jacksonObjectMapper()
          .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  private fun snapshot(index: Int): LibraryItem {
    val metadata = """{"filename":"episode-$index.mp3","ext":".mp3","path":"/pod/episode-$index.mp3","relPath":"episode-$index.mp3","size":1024}"""
    return mapper.readValue(
            """
            {"id":"pod","ino":"1","libraryId":"library","folderId":"folder","path":"/pod","relPath":"pod",
              "mediaType":"podcast","libraryFiles":[],"media":{
                "metadata":{"title":"Podcast","genres":[],"feedUrl":"https://example.test/feed","explicit":false},
                "tags":[],"autoDownloadEpisodes":false,"episodes":[{
                  "id":"episode-$index","index":$index,"title":"Episode $index","description":"Offline description",
                  "audioFile":{"index":$index,"ino":"$index","metadata":$metadata},
                  "audioTrack":{"index":$index,"title":"Episode $index","duration":30,"startOffset":0,
                    "contentUrl":"/api/items/pod/file/$index","metadata":$metadata},
                  "chapters":[{"id":1,"start":0,"end":30,"title":"Opening"}]
                }]
              }
            }
            """.trimIndent(),
            LibraryItem::class.java
    )
  }

  @Test
  fun replayingAnEpisodeReplacesItWithoutAddingADuplicate() {
    val source = snapshot(7).media as Podcast
    val local = source.getLocalCopy()
    val episode = source.episodes!!.single()
    local.addEpisode(episode.audioTrack!!, episode)
    val replayed = local.addEpisode(episode.audioTrack!!, episode)
    assertEquals(replayed, local.episodes!!.single())
    assertEquals("episode-7", replayed.serverEpisodeId)
    assertEquals(1, replayed.index)
  }
}
