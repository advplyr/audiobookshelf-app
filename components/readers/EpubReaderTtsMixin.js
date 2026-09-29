/**
 * Mixin to add TTS Read Aloud functionality to EpubReader.vue
 * Import and add to mixins: [] in EpubReader.vue
 */
import { extractSentencesFromRendition } from '../../utils/tts-sentences'

export default {
  data() {
    return {
      showTtsPanel: false
    }
  },
  computed: {
    ttsCurrentIndex() {
      return this.$store.getters['tts/currentSentenceIndex']
    },
    ttsIsPlaying() {
      return this.$store.getters['tts/isPlaying']
    }
  },
  watch: {
    ttsCurrentIndex(newIndex) {
      this.highlightTtsSentence(newIndex)
    }
  },
  methods: {
    openTtsPanel() {
      this.showTtsPanel = true
    },
    closeTtsPanel() {
      this.showTtsPanel = false
    },
    startTtsReading() {
      if (!this.rendition) return
      const sentences = extractSentencesFromRendition(this.rendition)
      if (!sentences.length) {
        console.warn('[TTS] No sentences extracted')
        return
      }
      this.$store.dispatch('tts/startReading', {
        sentences,
        startIndex: 0,
        libraryItemId: this.libraryItemId,
        serverLibraryItemId: this.serverLibraryItemId
      })
    },
    highlightTtsSentence(index) {
      if (!this.rendition) return
      try {
        const contents = this.rendition.getContents()
        contents.forEach((content) => {
          const doc = content.document
          if (!doc) return
          // Remove previous highlights
          doc.querySelectorAll('.tts-highlight').forEach((el) => el.classList.remove('tts-highlight'))
          // Highlight current sentence by index attribute
          const target = doc.querySelector(`[data-tts-index="${index}"]`)
          if (target) {
            target.classList.add('tts-highlight')
            target.scrollIntoView({ behavior: 'smooth', block: 'center' })
          }
        })
      } catch (e) {
        console.error('[TTS] highlight failed', e)
      }
    },
    injectTtsStyles() {
      if (!this.rendition) return
      this.rendition.getContents().forEach((content) => {
        const doc = content.document
        if (!doc) return
        if (doc.getElementById('tts-styles')) return
        const style = doc.createElement('style')
        style.id = 'tts-styles'
        style.textContent = `.tts-highlight { background-color: rgba(250, 204, 21, 0.4) !important; border-radius: 3px !important; }`
        doc.head.appendChild(style)
      })
    }
  },
  beforeDestroy() {
    if (this.ttsIsPlaying) {
      this.$store.dispatch('tts/stopReading')
    }
  }
}
