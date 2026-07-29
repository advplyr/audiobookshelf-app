<template>
  <div id="epub-frame" class="w-full">
    <div id="viewer" class="h-full w-full"></div>

    <div class="fixed left-0 h-8 w-full px-4 flex items-center" :class="isLightTheme ? 'bg-white text-black' : isDarkTheme ? 'bg-[#232323] text-white/80' : 'bg-black text-white/80'" :style="{ bottom: isPlayerOpen ? '120px' : '0px' }">
      <p v-if="totalLocations" class="text-xs text-slate-600">Location {{ currentLocationNum }} of {{ totalLocations }}</p>
      <div class="flex-grow" />
      <p class="text-xs">{{ progress }}%</p>
    </div>
  </div>
</template>

<script>
import ePub, { EpubCFI } from 'epubjs'
import { TextToSpeech } from '@capacitor-community/text-to-speech'

export default {
  props: {
    url: String,
    libraryItem: {
      type: Object,
      default: () => {}
    },
    isLocal: Boolean,
    keepProgress: Boolean
  },
  data() {
    return {
      /** @type {ePub.Book} */
      book: null,
      /** @type {ePub.Rendition} */
      rendition: null,
      progress: 0,
      totalLocations: 0,
      currentLocationNum: 0,
      currentLocationCfi: null,
      inittingDisplay: true,
      isRefreshingUI: false,
      ttsState: 'stopped',
      ttsSessionId: 0,
      ttsSectionIndex: 0,
      ttsParagraphs: [],
      ttsParagraphIndex: 0,
      ttsChunks: [],
      ttsChunkIndex: 0,
      ereaderSettings: {
        theme: 'dark',
        font: 'serif',
        fontScale: 100,
        lineSpacing: 115,
        textStroke: 0,
        ttsLanguage: 'en-US',
        ttsRate: 1
      }
    }
  },
  watch: {
    isPlayerOpen() {
      this.refreshUI()
    }
  },
  computed: {
    /** @returns {string} */
    libraryItemId() {
      return this.libraryItem?.id
    },
    localLibraryItem() {
      if (this.isLocal) return this.libraryItem
      return this.libraryItem.localLibraryItem || null
    },
    localLibraryItemId() {
      return this.localLibraryItem?.id
    },
    serverLibraryItemId() {
      if (!this.isLocal) return this.libraryItem.id
      // Check if local library item is connected to the current server
      if (!this.libraryItem.serverAddress || !this.libraryItem.libraryItemId) return null
      if (this.$store.getters['user/getServerAddress'] === this.libraryItem.serverAddress) {
        return this.libraryItem.libraryItemId
      }
      return null
    },
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    },
    readerHeightOffset() {
      return this.isPlayerOpen ? 204 : 104
    },
    /** @returns {Array<ePub.NavItem>} */
    chapters() {
      return this.book?.navigation?.toc || []
    },
    userItemProgress() {
      if (this.isLocal) return this.localItemProgress
      return this.serverItemProgress
    },
    localItemProgress() {
      return this.$store.getters['globals/getLocalMediaProgressById'](this.localLibraryItemId)
    },
    serverItemProgress() {
      return this.$store.getters['user/getUserMediaProgress'](this.serverLibraryItemId)
    },
    localStorageLocationsKey() {
      return `ebookLocations-${this.libraryItemId}`
    },
    savedEbookLocation() {
      if (!this.keepProgress) return null
      if (!this.userItemProgress?.ebookLocation) return null
      // Validate ebookLocation is an epubcfi
      if (!String(this.userItemProgress.ebookLocation).startsWith('epubcfi')) return null
      return this.userItemProgress.ebookLocation
    },
    isLightTheme() {
      return this.ereaderSettings.theme === 'light'
    },
    isDarkTheme() {
      return this.ereaderSettings.theme === 'dark'
    },
    themeRules() {
      const isDark = this.ereaderSettings.theme === 'dark'
      const isBlack = this.ereaderSettings.theme === 'black'
      const fontColor = isDark ? '#fff' : isBlack ? '#fff' : '#000'
      const backgroundColor = isDark ? 'rgb(35 35 35)' : isBlack ? 'rgb(0 0 0)' : 'rgb(255, 255, 255)'

      return {
        '*': {
          color: `${fontColor}!important`,
          'background-color': `${backgroundColor}!important`,
          'line-height': this.ereaderSettings.lineSpacing + '%!important',
          '-webkit-text-stroke': this.ereaderSettings.textStroke / 100 + 'px ' + fontColor + '!important'
        },
        a: {
          color: `${fontColor}!important`
        }
      }
    }
  },
  methods: {
    updateSettings(settings) {
      const ttsChanged = settings.ttsLanguage !== this.ereaderSettings.ttsLanguage || settings.ttsRate !== this.ereaderSettings.ttsRate

      this.ereaderSettings = settings

      if (ttsChanged && this.ttsState === 'playing') {
        // Restart the current chunk so the new voice/rate takes effect immediately
        this.ttsSessionId++
        TextToSpeech.stop()
          .catch(() => {})
          .finally(() => {
            if (this.ttsState === 'playing') this.speakNextChunk()
          })
      }

      if (!this.rendition) return

      this.applyTheme()

      const fontScale = settings.fontScale || 100
      this.rendition.themes.fontSize(`${fontScale}%`)
      this.rendition.themes.font(settings.font)
      this.rendition.spread(settings.spread || 'auto')
    },
    goToChapter(href) {
      return this.rendition?.display(href)
    },
    /**
     * Read aloud (TTS) of the epub text using the device system voices.
     * Text is extracted from the rendered section, split into sentence-sized
     * chunks and spoken sequentially. The displayed page follows along.
     */
    async startTTS() {
      const location = this.rendition?.currentLocation()
      if (!location?.start) return

      this.ttsSessionId++
      await TextToSpeech.stop().catch(() => {})

      const lang = this.ereaderSettings.ttsLanguage || 'en-US'
      TextToSpeech.isLanguageSupported({ lang })
        .then((result) => {
          if (!result.supported) {
            this.$toast.warning(this.$strings.MessageReadAloudNoVoice)
          }
        })
        .catch(() => {})

      this.ttsSectionIndex = location.start.index
      const contents = this.getTTSSectionContents()
      if (!contents) return
      this.ttsParagraphs = this.collectTTSParagraphs(contents)
      if (!this.ttsParagraphs.length) {
        this.$toast.error(this.$strings.MessageReadAloudNoText)
        return
      }

      // Start from the first paragraph on the currently visible page
      const cfiCompare = new EpubCFI()
      let startIndex = this.ttsParagraphs.findIndex((p) => {
        try {
          return cfiCompare.compare(p.cfi, location.start.cfi) >= 0
        } catch (error) {
          return false
        }
      })
      if (startIndex < 0) startIndex = this.ttsParagraphs.length - 1

      this.ttsParagraphIndex = startIndex
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
        this.ttsNextSection()
        return
      }
      this.ttsFollowLocation(paragraph.cfi)
      this.ttsChunks = this.splitTextChunks(paragraph.text)
      this.ttsChunkIndex = 0
      this.speakNextChunk()
    },
    async speakNextChunk() {
      if (this.ttsState !== 'playing') return

      if (this.ttsChunkIndex >= this.ttsChunks.length) {
        this.ttsParagraphIndex++
        if (this.ttsParagraphIndex >= this.ttsParagraphs.length) {
          this.ttsNextSection()
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
        console.error('[EpubReader] TTS speak failed', error)
        this.$toast.error(this.$strings.MessageReadAloudFailed)
        this.stopTTS()
        return
      }
      if (session !== this.ttsSessionId || this.ttsState !== 'playing') return

      this.ttsChunkIndex++
      this.speakNextChunk()
    },
    async ttsNextSection() {
      if (this.ttsState !== 'playing') return

      // Skip sections with no readable text (e.g. cover/image pages)
      let nextSection = this.book.spine.get(this.ttsSectionIndex + 1)
      while (nextSection) {
        this.ttsSectionIndex = nextSection.index
        const session = this.ttsSessionId
        await this.rendition.display(nextSection.href).catch((error) => {
          console.error('[EpubReader] TTS failed to display section', error)
        })
        if (session !== this.ttsSessionId || this.ttsState !== 'playing') return

        const contents = this.getTTSSectionContents()
        const paragraphs = contents ? this.collectTTSParagraphs(contents) : []
        if (paragraphs.length) {
          this.ttsParagraphs = paragraphs
          this.ttsParagraphIndex = 0
          this.speakCurrentParagraph()
          return
        }
        nextSection = this.book.spine.get(this.ttsSectionIndex + 1)
      }

      // Reached the end of the book
      this.stopTTS()
    },
    getTTSSectionContents() {
      const contents = this.rendition?.getContents() || []
      return contents.find((c) => c.sectionIndex === this.ttsSectionIndex) || contents[0] || null
    },
    /** @returns {Array<{ text: string, cfi: string }>} */
    collectTTSParagraphs(contents) {
      const textElementsSelector = 'p, h1, h2, h3, h4, h5, h6, li, blockquote, figcaption, dt, dd'
      const paragraphs = []
      const elements = contents.document?.body?.querySelectorAll(textElementsSelector) || []
      elements.forEach((el) => {
        // Skip elements nested inside another matched element to avoid reading text twice
        if (el.parentElement?.closest(textElementsSelector)) return
        const text = (el.innerText || el.textContent || '').trim()
        if (!text) return
        try {
          paragraphs.push({ text, cfi: contents.cfiFromNode(el) })
        } catch (error) {
          console.error('[EpubReader] TTS failed to get cfi for element', error)
        }
      })

      // Fallback for epubs not using standard text elements
      if (!paragraphs.length) {
        const bodyText = (contents.document?.body?.innerText || '').trim()
        if (bodyText) {
          paragraphs.push({ text: bodyText, cfi: null })
        }
      }
      return paragraphs
    },
    /** Turn the page when the spoken paragraph is not on the visible page */
    ttsFollowLocation(cfi) {
      if (!cfi) return
      const location = this.rendition?.currentLocation()
      if (!location?.start?.cfi || !location?.end?.cfi) return
      try {
        const cfiCompare = new EpubCFI()
        if (cfiCompare.compare(cfi, location.end.cfi) >= 0 || cfiCompare.compare(cfi, location.start.cfi) < 0) {
          this.rendition.display(cfi).catch((error) => {
            console.error('[EpubReader] TTS failed to follow location', error)
          })
        }
      } catch (error) {
        console.error('[EpubReader] TTS failed to compare locations', error)
      }
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
    prev() {
      if (this.rendition) {
        this.rendition.prev()
      }
    },
    next() {
      if (this.rendition) {
        this.rendition.next()
      }
    },
    /**
     * @param {object} payload
     * @param {string} payload.ebookLocation - CFI of the current location
     * @param {string} payload.ebookProgress - eBook Progress Percentage
     */
    async updateProgress(payload) {
      if (!this.keepProgress) return

      // Update local item
      if (this.localLibraryItemId) {
        const localPayload = {
          localLibraryItemId: this.localLibraryItemId,
          ...payload
        }
        const localResponse = await this.$db.updateLocalEbookProgress(localPayload)
        if (localResponse.localMediaProgress) {
          this.$store.commit('globals/updateLocalMediaProgress', localResponse.localMediaProgress)
        }
      }

      // Update server item
      if (this.serverLibraryItemId) {
        this.$nativeHttp.patch(`/api/me/progress/${this.serverLibraryItemId}`, payload).catch((error) => {
          console.error('EpubReader.updateProgress failed:', error)
        })
      }
    },
    getAllEbookLocationData() {
      const locations = []
      let totalSize = 0 // Total in bytes

      for (const key in localStorage) {
        if (!localStorage.hasOwnProperty(key) || !key.startsWith('ebookLocations-')) {
          continue
        }

        try {
          const ebookLocations = JSON.parse(localStorage[key])
          if (!ebookLocations.locations) throw new Error('Invalid locations object')

          ebookLocations.key = key
          ebookLocations.size = (localStorage[key].length + key.length) * 2
          locations.push(ebookLocations)
          totalSize += ebookLocations.size
        } catch (error) {
          console.error('Failed to parse ebook locations', key, error)
          localStorage.removeItem(key)
        }
      }

      // Sort by oldest lastAccessed first
      locations.sort((a, b) => a.lastAccessed - b.lastAccessed)

      return {
        locations,
        totalSize
      }
    },
    /** @param {string} locationString */
    checkSaveLocations(locationString) {
      const maxSizeInBytes = 3000000 // Allow epub locations to take up to 3MB of space
      const newLocationsSize = JSON.stringify({ lastAccessed: Date.now(), locations: locationString }).length * 2

      // Too large overall
      if (newLocationsSize > maxSizeInBytes) {
        console.error('Epub locations are too large to store. Size =', newLocationsSize)
        return
      }

      const ebookLocationsData = this.getAllEbookLocationData()

      let availableSpace = maxSizeInBytes - ebookLocationsData.totalSize

      // Remove epub locations until there is room for locations
      while (availableSpace < newLocationsSize && ebookLocationsData.locations.length) {
        const oldestLocation = ebookLocationsData.locations.shift()
        console.log(`Removing cached locations for epub "${oldestLocation.key}" taking up ${oldestLocation.size} bytes`)
        availableSpace += oldestLocation.size
        localStorage.removeItem(oldestLocation.key)
      }

      console.log(`Cacheing epub locations with key "${this.localStorageLocationsKey}" taking up ${newLocationsSize} bytes`)
      this.saveLocations(locationString)
    },
    /** @param {string} locationString */
    saveLocations(locationString) {
      localStorage.setItem(
        this.localStorageLocationsKey,
        JSON.stringify({
          lastAccessed: Date.now(),
          locations: locationString
        })
      )
    },
    loadLocations() {
      const locationsObjString = localStorage.getItem(this.localStorageLocationsKey)
      if (!locationsObjString) return null

      const locationsObject = JSON.parse(locationsObjString)

      // Remove invalid location objects
      if (!locationsObject.locations) {
        console.error('Invalid epub locations stored', this.localStorageLocationsKey)
        localStorage.removeItem(this.localStorageLocationsKey)
        return null
      }

      // Update lastAccessed
      this.saveLocations(locationsObject.locations)

      return locationsObject.locations
    },
    /** @param {string} location - CFI of the new location */
    relocated(location) {
      console.log(`[EpubReader] relocated ${location.start.cfi}`)
      if (this.inittingDisplay) {
        console.log(`[EpubReader] relocated but initting display ${location.start.cfi}`)
        return
      }
      this.currentLocationNum = location.start.location

      if (this.currentLocationCfi === location.start.cfi) {
        console.log(`[EpubReader] location already saved`, location.start.cfi)
        return
      }

      console.log(`[EpubReader] Saving new location ${location.start.cfi}`)
      this.currentLocationCfi = location.start.cfi

      if (location.end.percentage) {
        this.updateProgress({
          ebookLocation: location.start.cfi,
          ebookProgress: location.end.percentage
        })
        this.progress = Math.round(location.end.percentage * 100)
      } else {
        this.updateProgress({
          ebookLocation: location.start.cfi
        })
      }
    },
    initEpub() {
      this.progress = Math.round((this.userItemProgress?.ebookProgress || 0) * 100)

      /** @type {EpubReader} */
      const reader = this

      // Use axios to make request because we have token refresh logic in interceptor
      const customRequest = async (url) => {
        try {
          return this.$axios.$get(url, {
            responseType: 'arraybuffer'
          })
        } catch (error) {
          console.error('EpubReader.initEpub customRequest failed:', error)
          throw error
        }
      }

      console.log('[EpubReader] initEpub', reader.url)
      /** @type {ePub.Book} */
      reader.book = new ePub(reader.url, {
        width: window.innerWidth,
        height: window.innerHeight - this.readerHeightOffset,
        openAs: 'epub',
        requestMethod: this.isLocal ? null : customRequest
      })

      /** @type {ePub.Rendition} */
      reader.rendition = reader.book.renderTo('viewer', {
        width: window.innerWidth,
        height: window.innerHeight - this.readerHeightOffset,
        snap: true,
        manager: 'continuous',
        flow: 'paginated'
      })

      reader.book.ready.then(() => {
        console.log('%c [EpubReader] Book ready', 'color:cyan;')

        let displayCfi = reader.book.locations.start
        if (this.savedEbookLocation && reader.book.spine.get(this.savedEbookLocation)) {
          displayCfi = this.savedEbookLocation
        }

        reader.rendition.on('displayed', async () => {
          console.log('%c [EpubReader] Rendition displayed', 'color:blue;')

          // Overriding the needsSnap function in epubjs `snap.js` to fix a bug with scrollLeft being a decimal
          reader.rendition.manager.snapper.needsSnap = function () {
            let left = Math.round(this.scrollLeft)
            let snapWidth = this.layout.pageWidth * this.layout.divisor
            return left % snapWidth !== 0
          }
        })

        reader.rendition.on('rendered', (section, view) => {
          this.applyTheme()
          console.log('%c [EpubReader] Rendition rendered', 'color:red;', section, view)
        })

        // set up event listeners
        reader.rendition.on('relocated', reader.relocated)

        reader.rendition.on('displayError', (err) => {
          console.log('[EpubReader] Display error', err)
        })

        reader.rendition.on('touchstart', (event) => {
          this.$emit('touchstart', event)
        })
        reader.rendition.on('touchend', (event) => {
          this.$emit('touchend', event)
        })

        // load ebook cfi locations
        const savedLocations = this.loadLocations()
        if (savedLocations) {
          reader.book.locations.load(savedLocations)
          this.totalLocations = reader.book.locations.length()
        } else {
          reader.book.locations.generate(100).then(() => {
            this.totalLocations = reader.book.locations.length()
            this.currentLocationNum = reader.rendition.currentLocation()?.start.location || 0
            this.checkSaveLocations(reader.book.locations.save())
          })
        }

        // TODO: To get the correct page need to render twice. On book ready and after first display. Figure out why
        console.log(`[EpubReader] Displaying cfi ${displayCfi}`)
        this.currentLocationCfi = displayCfi
        reader.rendition.display(displayCfi).then(() => {
          reader.rendition.display(displayCfi).then(() => {
            this.inittingDisplay = false
          })
        })
      })
    },
    applyTheme() {
      if (!this.rendition) return
      this.rendition.getContents().forEach((c) => {
        c.addStylesheetRules(this.themeRules)
      })
    },
    async screenOrientationChange() {
      if (this.isRefreshingUI) return
      this.isRefreshingUI = true
      const windowWidth = window.innerWidth
      this.refreshUI()

      // Window width does not always change right away. Wait up to 250ms for a change.
      // iPhone 10 on iOS 16 took between 100 - 200ms to update when going from portrait to landscape
      //   but landscape to portrait was immediate
      for (let i = 0; i < 5; i++) {
        await new Promise((resolve) => setTimeout(resolve, 50))
        if (window.innerWidth !== windowWidth) {
          this.refreshUI()
          break
        }
      }

      this.isRefreshingUI = false
    },
    refreshUI() {
      if (this.rendition?.resize) {
        this.rendition.resize(window.innerWidth, window.innerHeight - this.readerHeightOffset)
      }
    }
  },
  mounted() {
    this.initEpub()

    if (screen.orientation) {
      // Not available on ios
      screen.orientation.addEventListener('change', this.screenOrientationChange)
    } else {
      document.addEventListener('orientationchange', this.screenOrientationChange)
    }
    window.addEventListener('resize', this.screenOrientationChange)
  },
  beforeDestroy() {
    this.stopTTS()
    this.book?.destroy()

    if (screen.orientation) {
      // Not available on ios
      screen.orientation.removeEventListener('change', this.screenOrientationChange)
    } else {
      document.removeEventListener('orientationchange', this.screenOrientationChange)
    }
    window.removeEventListener('resize', this.screenOrientationChange)
  }
}
</script>

<style>
#epub-frame {
  height: calc(100% - 32px);
  max-height: calc(100% - 32px);
  overflow: hidden;
}
.reader-player-open #epub-frame {
  height: calc(100% - 132px);
  max-height: calc(100% - 132px);
  overflow: hidden;
}
</style>
