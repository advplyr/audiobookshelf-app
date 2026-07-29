package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Extracted ebook text for the native read aloud (TTS) player.
 * Produced by the ebook readers in the WebView (ttsExtractBook hooks)
 * and cached on disk by TTSBookCache.
 * See docs/native-tts-player-design.md
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TTSBook(
  var libraryItemId: String,
  var serverAddress: String?,
  var title: String,
  var author: String?,
  var language: String,
  var rate: Float,
  var ebookFormat: String,
  var chapters: MutableList<TTSChapter>,
  var totalChars: Int
) {
  constructor() : this("", null, "", null, "en-US", 1f, "", mutableListOf(), 0)

  /** Total characters before the given position, for progress and time estimates */
  fun charsBefore(chapterIndex: Int, paragraphIndex: Int): Int {
    var chars = 0
    chapters.forEachIndexed { ci, chapter ->
      chapter.paragraphs.forEachIndexed { pi, paragraph ->
        if (ci < chapterIndex || (ci == chapterIndex && pi < paragraphIndex)) {
          chars += paragraph.chars
        }
      }
    }
    return chars
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TTSChapter(
  var title: String?,
  var startLocation: String?,
  var paragraphs: MutableList<TTSParagraph>
) {
  constructor() : this(null, null, mutableListOf())
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TTSParagraph(
  var text: String,
  var location: String?,
  var chars: Int
) {
  constructor() : this("", null, 0)
}

/** Lightweight summary stored next to the full book for fast listing (Android Auto browse) */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TTSBookSummary(
  var libraryItemId: String,
  var serverAddress: String?,
  var title: String,
  var author: String?,
  var language: String,
  var ebookFormat: String,
  var totalChars: Int,
  var lastAccessed: Long
) {
  constructor() : this("", null, "", null, "en-US", "", 0, 0L)
}
