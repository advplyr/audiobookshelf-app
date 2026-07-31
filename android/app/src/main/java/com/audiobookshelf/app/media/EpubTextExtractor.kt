package com.audiobookshelf.app.media

import android.util.Log
import com.audiobookshelf.app.data.TTSChapter
import com.audiobookshelf.app.data.TTSParagraph
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Native EPUB text extraction for the read aloud (TTS) player, so Android Auto
 * can play library ebooks that were never opened in the WebView reader
 * (the reader ttsExtractBook hooks need the app in the foreground).
 *
 * Produces the same chapter/paragraph shape as the JS extraction: one chapter
 * per linear spine item (startLocation = spine href, used by the reader to
 * resume near the spoken position), paragraphs from block-level text elements.
 * Paragraph CFIs need the rendered DOM, so locations stay null - progress
 * falls back to the chapter href and the character ratio, which both the
 * reader resume and the TTS resume already handle.
 *
 * Content documents are intentionally not parsed as XML: real-world EPUBs
 * contain undeclared entities and other XML violations, so text is pulled out
 * with tag stripping that turns block-level boundaries into paragraph breaks.
 * See docs/native-tts-player-design.md (F2+)
 */
object EpubTextExtractor {
  private const val tag = "EpubTextExtractor"

  data class ExtractedEpub(
    val title: String?,
    val author: String?,
    val language: String?,
    val chapters: MutableList<TTSChapter>
  )

  private data class OpfData(
    val baseDir: String,
    val title: String?,
    val author: String?,
    val language: String?,
    /** Spine hrefs (relative to the OPF) of linear content documents, in reading order */
    val spineHrefs: List<String>,
    /** Nav document href (EPUB 3), relative to the OPF */
    val navHref: String?,
    /** NCX href (EPUB 2), relative to the OPF */
    val ncxHref: String?
  )

  class EpubParseException(message: String) : Exception(message)

  fun extract(epubFile: File): ExtractedEpub {
    ZipFile(epubFile).use { zip ->
      val containerXml = readEntryText(zip, "META-INF/container.xml")
        ?: throw EpubParseException("Missing META-INF/container.xml")
      val opfPath = parseContainerForOpfPath(containerXml)
        ?: throw EpubParseException("No rootfile in container.xml")
      val opfXml = readEntryText(zip, opfPath)
        ?: throw EpubParseException("Missing OPF file $opfPath")
      val opf = parseOpf(opfXml, opfPath)
      if (opf.spineHrefs.isEmpty()) throw EpubParseException("Empty spine in $opfPath")

      // Chapter titles from the toc, keyed by the resolved zip path of the target
      val tocTitles = parseTocTitles(zip, opf)

      val chapters = mutableListOf<TTSChapter>()
      opf.spineHrefs.forEach { href ->
        val entryPath = resolvePath(opf.baseDir, href)
        val html = readEntryText(zip, entryPath)
        if (html == null) {
          Log.w(tag, "Spine entry not found in zip: $entryPath")
          return@forEach
        }
        val paragraphs = extractParagraphs(html)
        if (paragraphs.isEmpty()) return@forEach
        chapters.add(
          TTSChapter(
            title = tocTitles[entryPath] ?: "",
            startLocation = href,
            paragraphs = paragraphs.map { TTSParagraph(it, null, it.length) }.toMutableList()
          )
        )
      }
      if (chapters.isEmpty()) throw EpubParseException("No readable text found")
      return ExtractedEpub(opf.title, opf.author, opf.language, chapters)
    }
  }

  // ------------------------------------------------------------ OPF and toc

  private fun parseContainerForOpfPath(containerXml: String): String? {
    forEachXmlTag(containerXml) { parser ->
      if (parser.name.substringAfter(':') == "rootfile") {
        val fullPath = parser.getAttributeValue(null, "full-path")
        if (!fullPath.isNullOrEmpty()) return fullPath
      }
    }
    return null
  }

  private fun parseOpf(opfXml: String, opfPath: String): OpfData {
    val baseDir = opfPath.substringBeforeLast('/', "")
    var title: String? = null
    var author: String? = null
    var language: String? = null
    val manifestHrefById = mutableMapOf<String, String>()
    val manifestMediaTypeById = mutableMapOf<String, String>()
    var navId: String? = null
    var ncxId: String? = null
    var spineTocId: String? = null
    val spineIdRefs = mutableListOf<String>()

    forEachXmlTag(opfXml) { parser ->
      when (parser.name.substringAfter(':')) {
        // Metadata (dc: prefixed); the first occurrence wins
        "title" -> if (title == null) title = parser.nextText().trim().ifEmpty { null }
        "creator" -> if (author == null) author = parser.nextText().trim().ifEmpty { null }
        "language" -> if (language == null) language = parser.nextText().trim().ifEmpty { null }
        "item" -> {
          val id = parser.getAttributeValue(null, "id")
          val href = parser.getAttributeValue(null, "href")
          val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
          val properties = parser.getAttributeValue(null, "properties") ?: ""
          if (id != null && href != null) {
            manifestHrefById[id] = href
            manifestMediaTypeById[id] = mediaType
            if (properties.split(' ').contains("nav")) navId = id
            if (mediaType == "application/x-dtbncx+xml") ncxId = id
          }
        }
        "spine" -> spineTocId = parser.getAttributeValue(null, "toc")
        "itemref" -> {
          val idref = parser.getAttributeValue(null, "idref")
          val linear = parser.getAttributeValue(null, "linear")
          if (idref != null && linear != "no") spineIdRefs.add(idref)
        }
      }
    }

    val spineHrefs = spineIdRefs.mapNotNull { idref ->
      val href = manifestHrefById[idref] ?: return@mapNotNull null
      // Spine can reference non-text resources; only content documents are speakable
      val mediaType = manifestMediaTypeById[idref] ?: ""
      if (mediaType.isNotEmpty() && !mediaType.contains("html") && !mediaType.contains("xml")) return@mapNotNull null
      href
    }

    return OpfData(
      baseDir = baseDir,
      title = title,
      author = author,
      language = language,
      spineHrefs = spineHrefs,
      navHref = navId?.let { manifestHrefById[it] },
      ncxHref = (spineTocId ?: ncxId)?.let { manifestHrefById[it] }
    )
  }

  /** Map of resolved zip path -> chapter title, from the EPUB 3 nav doc or the EPUB 2 NCX */
  private fun parseTocTitles(zip: ZipFile, opf: OpfData): Map<String, String> {
    val titles = mutableMapOf<String, String>()
    try {
      opf.navHref?.let { navHref ->
        val navDir = resolvePath(opf.baseDir, navHref).substringBeforeLast('/', "")
        val navHtml = readEntryText(zip, resolvePath(opf.baseDir, navHref))
        if (navHtml != null) {
          // The nav doc is XHTML with possibly undeclared entities - pull the
          // anchors out with a regex like the content documents
          Regex("(?is)<a\\s[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(navHtml).forEach { match ->
            val target = resolvePath(navDir, match.groupValues[1])
            val label = normalizeWhitespace(decodeEntities(match.groupValues[2].replace(Regex("<[^>]*>"), "")))
            if (target.isNotEmpty() && label.isNotEmpty() && !titles.containsKey(target)) titles[target] = label
          }
        }
      }
      if (titles.isEmpty() && opf.ncxHref != null) {
        val ncxPath = resolvePath(opf.baseDir, opf.ncxHref)
        val ncxDir = ncxPath.substringBeforeLast('/', "")
        readEntryText(zip, ncxPath)?.let { ncxXml ->
          var pendingLabel: String? = null
          forEachXmlTag(ncxXml) { parser ->
            when (parser.name.substringAfter(':')) {
              "navPoint" -> pendingLabel = null
              "text" -> if (pendingLabel == null) pendingLabel = normalizeWhitespace(parser.nextText())
              "content" -> {
                val src = parser.getAttributeValue(null, "src")
                val label = pendingLabel
                if (src != null && !label.isNullOrEmpty()) {
                  val target = resolvePath(ncxDir, src)
                  if (!titles.containsKey(target)) titles[target] = label
                }
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      // Chapter titles are cosmetic - extraction continues without them
      Log.w(tag, "Failed to parse toc titles", e)
    }
    return titles
  }

  /** Iterate START_TAG events of an XML document; parse errors abort silently after logging */
  private inline fun forEachXmlTag(xml: String, block: (XmlPullParser) -> Unit) {
    try {
      val parser = XmlPullParserFactory.newInstance().newPullParser()
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
      parser.setInput(StringReader(xml))
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) block(parser)
        event = parser.next()
      }
    } catch (e: Exception) {
      Log.w(tag, "XML parse ended early: ${e.message}")
    }
  }

  // ------------------------------------------------------- text extraction

  // Closing these turns into a paragraph break. Matches the block elements the
  // JS extraction reads (p, headings, li, blockquote, figcaption, dt, dd) plus
  // structural containers so div/table-based books still split into readable
  // paragraphs instead of one huge blob.
  private val blockBreakRegex = Regex(
    "(?i)</(p|h[1-6]|li|blockquote|figcaption|dt|dd|div|section|article|aside|header|footer|td|th|tr|table|ul|ol|dl|figure|pre)\\s*>|<(br|hr)\\s*/?\\s*>"
  )
  private val removeBlocksRegex = Regex("(?is)<(script|style|head|title|svg|template)\\b[^>]*>.*?</\\1\\s*>|<!--.*?-->")
  private val bodyRegex = Regex("(?is)<body[^>]*>(.*)</body>")
  private val tagRegex = Regex("<[^>]*>")

  /** Extract readable paragraphs from an XHTML/HTML content document */
  fun extractParagraphs(html: String): List<String> {
    val body = bodyRegex.find(html)?.groupValues?.get(1) ?: html
    val cleaned = removeBlocksRegex.replace(body, " ")
    val withBreaks = blockBreakRegex.replace(cleaned, "\n\n")
    val text = decodeEntities(tagRegex.replace(withBreaks, ""))
    return text.split(Regex("\n\\s*\n"))
      .map { normalizeWhitespace(it) }
      .filter { it.isNotEmpty() }
  }

  private fun normalizeWhitespace(text: String): String {
    return text.replace(Regex("[\\s\\u00A0\\u2028\\u2029]+"), " ").trim()
  }

  // Common named entities found in real books; XML predefined + Latin-1
  // typography. Czech and other non-ASCII text is stored as UTF-8, not entities.
  private val namedEntities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "shy" to "", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
    "ndash" to "–", "mdash" to "—", "horbar" to "―", "hellip" to "…",
    "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
    "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
    "laquo" to "«", "raquo" to "»", "prime" to "′", "Prime" to "″",
    "bull" to "•", "middot" to "·", "sect" to "§", "para" to "¶",
    "dagger" to "†", "Dagger" to "‡", "permil" to "‰",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
    "plusmn" to "±", "minus" to "−", "times" to "×", "divide" to "÷",
    "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "sup1" to "¹", "sup2" to "²", "sup3" to "³",
    "euro" to "€", "pound" to "£", "cent" to "¢", "yen" to "¥"
  )
  private val entityRegex = Regex("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")

  fun decodeEntities(text: String): String {
    if (!text.contains('&')) return text
    return entityRegex.replace(text) { match ->
      val entity = match.groupValues[1]
      if (entity.startsWith("#")) {
        val code = try {
          if (entity.startsWith("#x") || entity.startsWith("#X")) entity.substring(2).toInt(16)
          else entity.substring(1).toInt(10)
        } catch (e: NumberFormatException) { -1 }
        if (code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
      } else {
        namedEntities[entity] ?: match.value
      }
    }
  }

  // ------------------------------------------------------------ zip helpers

  /**
   * Resolve an href relative to a directory inside the zip: strips the
   * fragment, decodes percent-escapes and normalizes "." and ".." segments
   */
  fun resolvePath(baseDir: String, href: String): String {
    val raw = href.substringBefore('#').substringBefore('?')
    if (raw.isEmpty()) return ""
    val decoded = try {
      URLDecoder.decode(raw, "UTF-8")
    } catch (e: Exception) { raw }
    val combined = if (baseDir.isEmpty() || decoded.startsWith("/")) decoded.removePrefix("/") else "$baseDir/$decoded"
    val parts = mutableListOf<String>()
    combined.split('/').forEach { part ->
      when (part) {
        "", "." -> {}
        ".." -> parts.removeLastOrNull()
        else -> parts.add(part)
      }
    }
    return parts.joinToString("/")
  }

  private fun findEntry(zip: ZipFile, path: String): ZipEntry? {
    zip.getEntry(path)?.let { return it }
    // Some books reference entries with different casing than the archive
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
      val entry = entries.nextElement()
      if (entry.name.equals(path, ignoreCase = true)) return entry
    }
    return null
  }

  private fun readEntryText(zip: ZipFile, path: String): String? {
    val entry = findEntry(zip, path) ?: return null
    val bytes = zip.getInputStream(entry).use { it.readBytes() }
    return String(bytes, detectCharset(bytes))
  }

  /** BOM sniffing plus the XML declaration; UTF-8 otherwise */
  private fun detectCharset(bytes: ByteArray): Charset {
    if (bytes.size >= 2) {
      if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
      if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
    }
    val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
    Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']").find(head)?.let { match ->
      try {
        return Charset.forName(match.groupValues[1])
      } catch (e: Exception) {
        Log.w(tag, "Unknown charset ${match.groupValues[1]}")
      }
    }
    return Charsets.UTF_8
  }
}
