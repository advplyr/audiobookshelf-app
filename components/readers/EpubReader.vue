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
import ePub from 'epubjs'

export default {
  props: {
    url: String,
    libraryItem: {
      type: Object,
      default: () => {}
    },
    isLocal: Boolean,
    keepProgress: Boolean,
    showingToolbar: {
      type: Boolean,
      default: false
    }
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
      // Search state
      searchQuery: '',
      searchResults: [],
      currentSearchIndex: -1,
      searching: false,
      lastHighlightCfi: null,
      searchToken: 0,
      lastSearchQuery: '',
      // Cache
      searchCacheLoaded: false,
      // Internal swipe tracking
      swipeStartX: 0,
      swipeStartY: 0,
      swipeStartTime: 0,
      // Prevent re-displays during explicit navigation
      isNavigatingToCfi: false,
      // True while the UI is resizing due to toolbar show/hide
      isUiResizing: false,
      ereaderSettings: {
        theme: 'dark',
        font: 'serif',
        fontScale: 100,
        lineSpacing: 115,
        textStroke: 0
      }
    }
  },
  watch: {
    isPlayerOpen() {
      this.refreshUI()
    },
    showingToolbar() {
      // No-op: keep viewport height constant to prevent geometry drift
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
        },
        '.abs-epub-search-highlight': {
          'background-color': 'rgba(255, 235, 59, 0.6)!important'
        }
      }
    },
    searchCacheKey() {
      return this.libraryItemId ? `ebookSearch-${this.libraryItemId}` : null
    },
    topOverlayPx() {
      // Align with Reader toolbar height (h-24 = 96px) — not used to resize viewport
      return this.showingToolbar ? 96 : 0
    },
    viewerStyle() {
      return {
        marginTop: '0px'
      }
    }
  },
  methods: {
    // Compare CFIs; fallback to string compare if library function missing
    compareCfi(a, b) {
      try {
        if (ePub?.CFI?.compare) return ePub.CFI.compare(a, b)
      } catch (e) {}
      return String(a).localeCompare(String(b))
    },
    cfiInCurrentPage(targetCfi) {
      try {
        const loc = this.rendition?.currentLocation()
        if (!loc || !loc.start?.cfi || !loc.end?.cfi) return false
        const start = loc.start.cfi
        const end = loc.end.cfi
        return this.compareCfi(start, targetCfi) <= 0 && this.compareCfi(targetCfi, end) <= 0
      } catch (e) {
        return false
      }
    },
    async displayEnsureCfi(targetCfi, maxTries = 4) {
      for (let i = 0; i < maxTries; i++) {
        await this.rendition.display(targetCfi)
        // allow layout to settle
        await new Promise((r) => setTimeout(r, i === 0 ? 0 : 60))
        if (this.cfiInCurrentPage(targetCfi)) return true
      }
      return false
    },
    loadSearchCache() {
      if (!this.searchCacheKey) return null
      try {
        const raw = localStorage.getItem(this.searchCacheKey)
        if (!raw) return null
        const parsed = JSON.parse(raw)
        if (!parsed || typeof parsed !== 'object') return null
        if (!Array.isArray(parsed.results)) return null
        return parsed
      } catch (e) {
        try { localStorage.removeItem(this.searchCacheKey) } catch (_) {}
        return null
      }
    },
    saveSearchCache() {
      if (!this.searchCacheKey) return
      try {
        const payload = {
          query: this.searchQuery,
          results: this.searchResults,
          index: this.currentSearchIndex
        }
        localStorage.setItem(this.searchCacheKey, JSON.stringify(payload))
      } catch (e) {}
    },
    clearSearchCache() {
      if (!this.searchCacheKey) return
      try { localStorage.removeItem(this.searchCacheKey) } catch (e) {}
    },
    async restoreCachedSearch() {
      if (this.searchCacheLoaded) return
      const cached = this.loadSearchCache()
      if (!cached || !this.rendition) { this.searchCacheLoaded = true; return }
      // Apply cached state
      this.searchQuery = cached.query || ''
      this.lastSearchQuery = this.searchQuery
      this.searchResults = Array.isArray(cached.results) ? cached.results : []
      this.currentSearchIndex = typeof cached.index === 'number' ? cached.index : -1
      this.$emit('search-results', {
        query: this.searchQuery,
        results: this.searchResults.slice(),
        index: this.currentSearchIndex
      })
      // Navigate/highlight if valid index
      if (this.searchResults.length && this.currentSearchIndex >= 0 && this.currentSearchIndex < this.searchResults.length) {
        await this.goToSearchResult(this.currentSearchIndex)
      }
      this.searchCacheLoaded = true
    },
    handleRenditionTouchStart(event) {
      const t = event?.touches?.[0] || event?.changedTouches?.[0]
      if (!t) return
      this.swipeStartX = t.screenX
      this.swipeStartY = t.screenY
      this.swipeStartTime = Date.now()
    },
    handleRenditionTouchEnd(event) {
      const t = event?.touches?.[0] || event?.changedTouches?.[0]
      if (!t) return
      const dx = t.screenX - this.swipeStartX
      const dy = t.screenY - this.swipeStartY
      const dt = Date.now() - this.swipeStartTime
      if (dt > 800) return
      const absDx = Math.abs(dx)
      const absDy = Math.abs(dy)
      if (absDx < 50 || absDy > absDx) return
      if (dx < 0) this.next()
      else if (dx > 0) this.prev()
    },
    redisplayCurrentLocation() {
      if (this.isNavigatingToCfi) return
      try {
        const cfi = this.currentLocationCfi
        if (cfi) this.rendition.display(cfi)
      } catch (e) {}
    },
    ensurePageVisible() {
      try {
        const loc = this.rendition?.currentLocation()
        if (!loc || !loc.start?.cfi) {
          if (this.currentLocationCfi) {
            this.rendition.display(this.currentLocationCfi)
          }
        }
      } catch (e) {}
    },
    scrollCurrentHighlightIntoView() {
      try {
        const contents = this.rendition?.getContents?.() || []
        contents.forEach((c) => {
          const el = c.document?.querySelector?.('.abs-epub-search-highlight')
          if (el) {
            el.scrollIntoView({ behavior: 'auto', block: 'center', inline: 'nearest' })
          }
        })
      } catch (e) {}
    },
    // --- Search API exposed to parent ---
    async searchEbook(query) {
      if (!this.book || !query || !query.trim()) {
        this.clearSearch()
        return []
      }
      this.searchQuery = query
      this.lastSearchQuery = query
      this.searching = true
      this.searchResults = []
      this.currentSearchIndex = -1
      const token = ++this.searchToken

      const seen = new Set()
      const pushResult = (cfiLike, excerpt, href) => {
        const cfi = cfiLike && (cfiLike.cfiRange || cfiLike.cfi || cfiLike)
        if (!cfi || seen.has(cfi)) return
        seen.add(cfi)
        this.searchResults.push({ cfi, excerpt: (excerpt || '').slice(0, 180), href })
      }

      try {
        // Prefer built-in epub.js search if available for complete results
        if (typeof this.book.search === 'function') {
          const results = await this.book.search(query)
          if (token !== this.searchToken) return []
          if (Array.isArray(results)) {
            results.forEach((m) => pushResult(m, m.excerpt, m.href))
          }
        }

        // Also crawl sections to ensure completeness (merge unique CFIs)
        const spineItems = this.book.spine?.spineItems || []
        const batchSize = 3
        for (let i = 0; i < spineItems.length; i++) {
          if (token !== this.searchToken) {
            return []
          }
          const section = spineItems[i]
          await section.load(this.book.load.bind(this.book))
          if (typeof section.find === 'function') {
            const matches = section.find(query)
            if (Array.isArray(matches) && matches.length) {
              matches.forEach((m) => pushResult(m, m.excerpt, section.href))
            }
          }
          section.unload()
          if ((i + 1) % batchSize === 0) {
            this.$emit('search-results', {
              query: this.searchQuery,
              results: this.searchResults.slice(),
              index: this.currentSearchIndex
            })
            await new Promise((resolve) => setTimeout(resolve, 0))
          }
        }
      } catch (error) {
        console.error('[EpubReader] search failed', error)
      } finally {
        this.searching = false
      }

      if (this.searchResults.length) {
        await this.goToSearchResult(0)
      }
      this.saveSearchCache()
      // Notify parent listeners
      this.$emit('search-results', {
        query: this.searchQuery,
        results: this.searchResults.slice(),
        index: this.currentSearchIndex
      })
      return this.searchResults
    },
    async ensureSearch(query) {
      // Reuse existing results if they match the query
      const q = (query && query.trim()) || this.lastSearchQuery || this.searchQuery
      if (!q) return []
      if (this.searchResults.length && this.searchQuery === q) {
        return this.searchResults
      }
      return this.searchEbook(q)
    },
    getEbookSearchState() {
      return {
        query: this.searchQuery,
        results: this.searchResults,
        index: this.currentSearchIndex,
        searching: this.searching
      }
    },
    async goToSearchResult(index) {
      if (!this.rendition || !this.searchResults.length) return null
      if (index < 0 || index >= this.searchResults.length) return null

      const { cfi } = this.searchResults[index]
      this.currentSearchIndex = index

      try {
        this.isNavigatingToCfi = true
        // Robustly ensure the CFI falls within the visible page
        await this.displayEnsureCfi(cfi)
        // Clear previous highlight
        if (this.lastHighlightCfi) {
          try {
            this.rendition.annotations.remove(this.lastHighlightCfi, 'highlight')
          } catch (e) {}
        }
        // Highlight current
        this.rendition.annotations.add('highlight', cfi, {}, null, 'abs-epub-search-highlight')
        this.lastHighlightCfi = cfi
        // Center the highlighted range in view (robust to large fonts)
        setTimeout(() => this.scrollCurrentHighlightIntoView(), 0)
        setTimeout(() => this.scrollCurrentHighlightIntoView(), 120)
      } catch (error) {
        console.error('[EpubReader] failed to display search result', error)
      } finally {
        // Small delay lets the layout settle before reflows trigger
        setTimeout(() => {
          this.isNavigatingToCfi = false
        }, 150)
      }

      this.$emit('search-results', {
        query: this.searchQuery,
        results: this.searchResults.slice(),
        index: this.currentSearchIndex
      })
      this.saveSearchCache()
      return this.searchResults[this.currentSearchIndex]
    },
    async nextSearchResult() {
      if (!this.searchResults.length) return null
      const nextIndex = (this.currentSearchIndex + 1) % this.searchResults.length
      return this.goToSearchResult(nextIndex)
    },
    async prevSearchResult() {
      if (!this.searchResults.length) return null
      const prevIndex = (this.currentSearchIndex - 1 + this.searchResults.length) % this.searchResults.length
      return this.goToSearchResult(prevIndex)
    },
    clearSearch() {
      this.searchQuery = ''
      this.searchResults = []
      this.currentSearchIndex = -1
      if (this.rendition && this.lastHighlightCfi) {
        try {
          this.rendition.annotations.remove(this.lastHighlightCfi, 'highlight')
        } catch (e) {}
      }
      this.lastHighlightCfi = null
      this.clearSearchCache()
      this.$emit('search-results', {
        query: this.searchQuery,
        results: [],
        index: -1
      })
    },
    updateSettings(settings) {
      this.ereaderSettings = settings

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
    /** @param {object} location */
    relocated(location) {
      const newCfi = location.start?.cfi
      if (!newCfi) return
      if (this.inittingDisplay || this.isUiResizing || this.isNavigatingToCfi) {
        return
      }
      this.currentLocationNum = location.start.location
      if (this.currentLocationCfi === newCfi) {
        return
      }
      this.currentLocationCfi = newCfi

      if (location.end?.percentage) {
        this.updateProgress({
          ebookLocation: newCfi,
          ebookProgress: location.end.percentage
        })
        this.progress = Math.round(location.end.percentage * 100)
      } else {
        this.updateProgress({
          ebookLocation: newCfi
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
          // Re-apply highlight after re-render
          if (this.lastHighlightCfi) {
            try {
              this.rendition.annotations.remove(this.lastHighlightCfi, 'highlight')
            } catch (e) {}
            try {
              this.rendition.annotations.add('highlight', this.lastHighlightCfi, {}, null, 'abs-epub-search-highlight')
            } catch (e) {}
          }
          console.log('%c [EpubReader] Rendition rendered', 'color:red;', section, view)
        })

        // set up event listeners
        reader.rendition.on('relocated', reader.relocated)

        reader.rendition.on('displayError', (err) => {
          console.log('[EpubReader] Display error', err)
        })

        reader.rendition.on('touchstart', (event) => {
          this.handleRenditionTouchStart(event)
          this.$emit('touchstart', event)
        })
        reader.rendition.on('touchend', (event) => {
          this.handleRenditionTouchEnd(event)
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

        // Display initial CFI once
        console.log(`[EpubReader] Displaying cfi ${displayCfi}`)
        this.currentLocationCfi = displayCfi
        reader.rendition.display(displayCfi).then(() => {
          this.inittingDisplay = false
          // Attempt to restore cached search state once initial display is complete
          this.restoreCachedSearch()
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
