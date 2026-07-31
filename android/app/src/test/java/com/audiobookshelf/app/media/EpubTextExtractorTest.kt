package com.audiobookshelf.app.media

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTextExtractorTest {

  private fun buildTestEpub(dest: File) {
    ZipOutputStream(dest.outputStream()).use { zip ->
      fun entry(name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }

      entry(
        "META-INF/container.xml",
        """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
      )

      entry(
        "OEBPS/content.opf",
        """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">test-book</dc:identifier>
    <dc:title>Testovací kniha</dc:title>
    <dc:creator>Karel Čapek</dc:creator>
    <dc:language>cs</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch1" href="text/chapter%201.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
    <item id="img" href="images/cover.jpg" media-type="image/jpeg"/>
  </manifest>
  <spine>
    <itemref idref="cover" linear="no"/>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>"""
      )

      entry(
        "OEBPS/nav.xhtml",
        """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Obsah</title></head>
<body>
<nav epub:type="toc">
<ol>
<li><a href="text/chapter%201.xhtml">Kapitola <i>první</i></a></li>
<li><a href="text/chapter2.xhtml#start">Kapitola druhá</a></li>
</ol>
</nav>
</body>
</html>"""
      )

      entry(
        "OEBPS/cover.xhtml",
        """<html><body><p>Obálka - nelineární, nesmí se číst</p></body></html>"""
      )

      // Undeclared entity (&nbsp;), script/style noise, inline tags, a
      // paragraph broken over source lines and a percent-encoded file name
      entry(
        "OEBPS/text/chapter 1.xhtml",
        """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Kapitola 1</title><style>p { color: red; }</style></head>
<body>
<div class="chapter">
<h1>Kapitola první</h1>
<p>První odstavec&nbsp;s <em>kurzívou</em> a&#160;pomlčkou &mdash; tady.</p>
<p>Druhý
   odstavec přes
   více řádků.</p>
<script>var x = 1 &lt; 2;</script>
<p> </p>
<p>Třetí odstavec.</p>
</div>
</body>
</html>"""
      )

      entry(
        "OEBPS/text/chapter2.xhtml",
        """<html>
<body>
<ul><li>První položka</li><li>Druhá položka</li></ul>
<blockquote>Citát pokračuje zde.</blockquote>
</body>
</html>"""
      )
    }
  }

  private fun withTestEpub(block: (EpubTextExtractor.ExtractedEpub) -> Unit) {
    val file = File.createTempFile("test-book", ".epub")
    try {
      buildTestEpub(file)
      block(EpubTextExtractor.extract(file))
    } finally {
      file.delete()
    }
  }

  @Test
  fun extractsMetadata() {
    withTestEpub { extracted ->
      assertEquals("Testovací kniha", extracted.title)
      assertEquals("Karel Čapek", extracted.author)
      assertEquals("cs", extracted.language)
    }
  }

  @Test
  fun extractsLinearSpineChaptersWithTocTitles() {
    withTestEpub { extracted ->
      // The non-linear cover is skipped
      assertEquals(2, extracted.chapters.size)
      assertEquals("Kapitola první", extracted.chapters[0].title)
      assertEquals("text/chapter%201.xhtml", extracted.chapters[0].startLocation)
      // Toc entry with a fragment still maps to the chapter file
      assertEquals("Kapitola druhá", extracted.chapters[1].title)
      assertEquals("text/chapter2.xhtml", extracted.chapters[1].startLocation)
    }
  }

  @Test
  fun extractsParagraphsWithEntitiesAndWhitespaceNormalized() {
    withTestEpub { extracted ->
      val texts = extracted.chapters[0].paragraphs.map { it.text }
      assertEquals(
        listOf(
          "Kapitola první",
          "První odstavec s kurzívou a pomlčkou — tady.",
          "Druhý odstavec přes více řádků.",
          "Třetí odstavec."
        ),
        texts
      )
      extracted.chapters[0].paragraphs.forEach { paragraph ->
        assertNull(paragraph.location) // CFIs need the rendered DOM
        assertEquals(paragraph.text.length, paragraph.chars)
      }
    }
  }

  @Test
  fun extractsListsAndBlockquotes() {
    withTestEpub { extracted ->
      assertEquals(
        listOf("První položka", "Druhá položka", "Citát pokračuje zde."),
        extracted.chapters[1].paragraphs.map { it.text }
      )
    }
  }

  @Test
  fun extractParagraphsSplitsOnLineBreaks() {
    assertEquals(
      listOf("První řádek", "Druhý řádek"),
      EpubTextExtractor.extractParagraphs("<p>První řádek<br/>Druhý řádek</p>")
    )
  }

  @Test
  fun extractParagraphsFallsBackToWholeBodyText() {
    assertEquals(
      listOf("Text bez blokových elementů"),
      EpubTextExtractor.extractParagraphs("<html><body><span>Text bez blokových elementů</span></body></html>")
    )
  }

  @Test
  fun decodeEntitiesHandlesNumericNamedAndUnknown() {
    assertEquals("A & B A B &neznama;", EpubTextExtractor.decodeEntities("A &amp; B &#65; &#x42; &neznama;"))
  }

  @Test
  fun resolvePathNormalizesRelativeSegmentsAndEscapes() {
    assertEquals("images/x.jpg", EpubTextExtractor.resolvePath("OEBPS", "../images/x.jpg"))
    assertEquals("OEBPS/text/chapter 1.xhtml", EpubTextExtractor.resolvePath("OEBPS", "text/chapter%201.xhtml"))
    assertEquals("OEBPS/text/ch.xhtml", EpubTextExtractor.resolvePath("OEBPS", "text/ch.xhtml#fragment"))
    assertTrue(EpubTextExtractor.resolvePath("", "").isEmpty())
  }
}
