import { TextToSpeech } from '@capacitor-community/text-to-speech'

/**
 * Read aloud (TTS) engine shared by the ebook readers, speaking with the
 * device system voices via the native text-to-speech plugin.
 *
 * Text is spoken in sentence-sized chunks grouped into paragraphs, and
 * paragraphs into "units" (an epub spine section, a pdf page, or the whole
 * document). The reader component implements the format-specific hooks:
 *
 *   ttsCollectParagraphs() -> Array<{ text: String, ref: any }> | Promise
 *     Paragraphs of the current unit. Called when TTS starts.
 *   ttsStartIndex(paragraphs) -> Number (optional)
 *     Index of the paragraph to start speaking from, e.g. based on the
 *     currently visible page or scroll position. Defaults to 0.
 *   ttsAdvanceUnit() -> Array<paragraph> | null | Promise (optional)
 *     Move to the next unit and return its paragraphs, or null at the end
 *     of the book. The hook is responsible for skipping empty units.
 *     When not implemented the book ends with the current unit.
 *   ttsFollowParagraph(paragraph) (optional)
 *     Bring the paragraph about to be spoken into view.
 */
export default {
  data() {
    return {
      ttsState: 'stopped',
      ttsSessionId: 0,
      ttsParagraphs: [],
      ttsParagraphIndex: 0,
      ttsChunks: [],
      ttsChunkIndex: 0,
      ereaderSettings: {
        ttsLanguage: 'en-US',
        ttsRate: 1
      }
    }
  },
  methods: {
    // Readers with their own settings handling (epub) override this and
    // call ttsHandleSettingsChange themselves before storing the settings
    updateSettings(settings) {
      this.ttsHandleSettingsChange(settings)
      this.ereaderSettings = settings
    },
    ttsHandleSettingsChange(newSettings) {
      const ttsChanged = newSettings.ttsLanguage !== this.ereaderSettings.ttsLanguage || newSettings.ttsRate !== this.ereaderSettings.ttsRate
      if (ttsChanged && this.ttsState === 'playing') {
        // Restart the current chunk so the new voice/rate takes effect immediately
        this.ttsSessionId++
        TextToSpeech.stop()
          .catch(() => {})
          .finally(() => {
            if (this.ttsState === 'playing') this.speakNextChunk()
          })
      }
    },
    async startTTS() {
      this.ttsSessionId++
      const session = this.ttsSessionId
      await TextToSpeech.stop().catch(() => {})

      const lang = this.ereaderSettings.ttsLanguage || 'en-US'
      TextToSpeech.isLanguageSupported({ lang })
        .then((result) => {
          if (!result.supported) {
            this.$toast.warning(this.$strings.MessageReadAloudNoVoice)
          }
        })
        .catch(() => {})

      const paragraphs = await Promise.resolve(this.ttsCollectParagraphs()).catch((error) => {
        console.error('[ttsPlayer] Failed to collect paragraphs', error)
        return []
      })
      if (session !== this.ttsSessionId) return
      if (!paragraphs?.length) {
        this.$toast.error(this.$strings.MessageReadAloudNoText)
        return
      }

      this.ttsParagraphs = paragraphs
      let startIndex = this.ttsStartIndex?.(paragraphs) || 0
      this.ttsParagraphIndex = Math.max(0, Math.min(startIndex, paragraphs.length - 1))
      this.ttsState = 'playing'
      this.$emit('tts-state', 'playing')
      this.speakCurrentParagraph()
    },
    pauseTTS() {
      if (this.ttsState !== 'playing') return
      this.ttsState = 'paused'
      this.ttsSessionId++
      TextToSpeech.stop().catch(() => {})
      this.$emit('tts-state', 'paused')
    },
    resumeTTS() {
      if (this.ttsState !== 'paused') return
      this.ttsState = 'playing'
      this.$emit('tts-state', 'playing')
      this.speakNextChunk()
    },
    stopTTS() {
      const wasActive = this.ttsState !== 'stopped'
      this.ttsState = 'stopped'
      this.ttsSessionId++
      TextToSpeech.stop().catch(() => {})
      this.ttsParagraphs = []
      this.ttsParagraphIndex = 0
      this.ttsChunks = []
      this.ttsChunkIndex = 0
      if (wasActive) this.$emit('tts-state', 'stopped')
    },
    speakCurrentParagraph() {
      const paragraph = this.ttsParagraphs[this.ttsParagraphIndex]
      if (!paragraph) {
        this.ttsAdvance()
        return
      }
      this.ttsFollowParagraph?.(paragraph)
      this.ttsChunks = this.splitTextChunks(paragraph.text)
      this.ttsChunkIndex = 0
      this.speakNextChunk()
    },
    async speakNextChunk() {
      if (this.ttsState !== 'playing') return

      if (this.ttsChunkIndex >= this.ttsChunks.length) {
        this.ttsParagraphIndex++
        if (this.ttsParagraphIndex >= this.ttsParagraphs.length) {
          this.ttsAdvance()
        } else {
          this.speakCurrentParagraph()
        }
        return
      }

      const session = this.ttsSessionId
      try {
        await TextToSpeech.speak({
          text: this.ttsChunks[this.ttsChunkIndex],
          lang: this.ereaderSettings.ttsLanguage || 'en-US',
          rate: this.ereaderSettings.ttsRate || 1,
          category: 'playback'
        })
      } catch (error) {
        // Rejection is expected when speech gets interrupted by stop()
        if (session !== this.ttsSessionId || this.ttsState !== 'playing') return
        console.error('[ttsPlayer] TTS speak failed', error)
        this.$toast.error(this.$strings.MessageReadAloudFailed)
        this.stopTTS()
        return
      }
      if (session !== this.ttsSessionId || this.ttsState !== 'playing') return

      this.ttsChunkIndex++
      this.speakNextChunk()
    },
    async ttsAdvance() {
      if (this.ttsState !== 'playing') return
      if (!this.ttsAdvanceUnit) {
        this.stopTTS()
        return
      }

      const session = this.ttsSessionId
      const paragraphs = await Promise.resolve(this.ttsAdvanceUnit()).catch((error) => {
        console.error('[ttsPlayer] Failed to advance unit', error)
        return null
      })
      if (session !== this.ttsSessionId || this.ttsState !== 'playing') return
      if (!paragraphs?.length) {
        // Reached the end of the book
        this.stopTTS()
        return
      }
      this.ttsParagraphs = paragraphs
      this.ttsParagraphIndex = 0
      this.speakCurrentParagraph()
    },
    /**
     * Native TTS engines limit utterance length and cannot be interrupted
     * mid-utterance on all platforms, so speak in sentence-sized chunks
     * @returns {string[]}
     */
    splitTextChunks(text, maxLength = 300) {
      const chunks = []
      const sentences = text.match(/[^.!?…]+[.!?…]+["'”’)]*\s*|[^.!?…]+$/g) || [text]
      let current = ''
      for (const sentence of sentences) {
        if (current && current.length + sentence.length > maxLength) {
          chunks.push(current)
          current = ''
        }
        if (sentence.length > maxLength) {
          let remaining = sentence.trim()
          while (remaining.length > maxLength) {
            let cut = remaining.lastIndexOf(' ', maxLength)
            if (cut <= 0) cut = maxLength
            chunks.push(remaining.slice(0, cut))
            remaining = remaining.slice(cut)
          }
          current = remaining
        } else {
          current += sentence
        }
      }
      if (current) chunks.push(current)
      return chunks.map((c) => c.trim()).filter((c) => c)
    },
    /**
     * Collect readable text elements from a rendered HTML document
     * @returns {Array<{ text: string, ref: Element }>}
     */
    ttsCollectHtmlParagraphs(documentBody) {
      const textElementsSelector = 'p, h1, h2, h3, h4, h5, h6, li, blockquote, figcaption, dt, dd'
      const paragraphs = []
      const elements = documentBody?.querySelectorAll(textElementsSelector) || []
      elements.forEach((el) => {
        // Skip elements nested inside another matched element to avoid reading text twice
        if (el.parentElement?.closest(textElementsSelector)) return
        const text = (el.innerText || el.textContent || '').trim()
        if (!text) return
        paragraphs.push({ text, ref: el })
      })

      // Fallback for books not using standard text elements
      if (!paragraphs.length) {
        const bodyText = (documentBody?.innerText || '').trim()
        if (bodyText) {
          paragraphs.push({ text: bodyText, ref: documentBody })
        }
      }
      return paragraphs
    }
  },
  beforeDestroy() {
    this.stopTTS()
  }
}
