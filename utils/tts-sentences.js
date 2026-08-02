/**
 * Split text into sentences for TTS.
 * Handles common abbreviations to avoid false splits.
 */

const ABBREVS = ['Mr', 'Mrs', 'Ms', 'Dr', 'Prof', 'Sr', 'Jr', 'vs', 'etc', 'i.e', 'e.g', 'St', 'vol', 'pp']

export function splitIntoSentences(text) {
  if (!text || !text.trim()) return []

  // Replace abbreviations temporarily
  let processed = text
  ABBREVS.forEach((abbr, i) => {
    const re = new RegExp(`\\b${abbr}\\.`, 'g')
    processed = processed.replace(re, `${abbr}__ABBR${i}__`)
  })

  // Split on sentence-ending punctuation followed by whitespace + capital
  const raw = processed.split(/(?<=[.!?…])\s+(?=[A-ZА-ЯЁ"'(])/)

  // Restore abbreviations
  const sentences = raw.map((s) => {
    let restored = s
    ABBREVS.forEach((abbr, i) => {
      restored = restored.replace(new RegExp(`${abbr}__ABBR${i}__`, 'g'), `${abbr}.`)
    })
    return restored.trim()
  })

  return sentences.filter((s) => s.length > 0)
}

/**
 * Extract all text content from an epubjs rendition's current view
 * Returns array of sentences
 */
export function extractSentencesFromRendition(rendition) {
  const sentences = []
  try {
    const contents = rendition.getContents()
    contents.forEach((content) => {
      const doc = content.document
      if (!doc) return
      // Walk text nodes, skip script/style
      const walker = doc.createTreeWalker(
        doc.body,
        NodeFilter.SHOW_TEXT,
        {
          acceptNode(node) {
            const tag = node.parentElement?.tagName?.toLowerCase()
            if (['script', 'style', 'noscript'].includes(tag)) return NodeFilter.FILTER_REJECT
            return NodeFilter.FILTER_ACCEPT
          }
        }
      )
      let fullText = ''
      while (walker.nextNode()) {
        fullText += walker.currentNode.textContent + ' '
      }
      sentences.push(...splitIntoSentences(fullText))
    })
  } catch (e) {
    console.error('[TTS] extractSentences failed', e)
  }
  return sentences
}
