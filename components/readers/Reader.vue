<template>
  <div v-if="show" :data-theme="ereaderTheme" class="group fixed top-0 left-0 right-0 layout-wrapper w-full z-40 pt-8 data-[theme=black]:bg-black data-[theme=black]:text-white data-[theme=dark]:bg-[#232323] data-[theme=dark]:text-white data-[theme=light]:bg-white data-[theme=light]:text-black" :class="{ 'reader-player-open': isPlayerOpen }">
    <!-- toolbar -->
    <div class="h-24 pt-6 w-full px-2 fixed top-0 left-0 z-30 bg-bg text-fg transform transition-transform duration-200 ease-out" :class="{ 'translate-y-0': showingToolbar, '-translate-y-24': !showingToolbar }" :style="{ boxShadow: '0px 8px 8px #11111155' }" @touchstart.stop @mousedown.stop @touchend.stop @mouseup.stop>
      <div class="flex items-center mb-2">
        <button type="button" class="inline-flex mx-2" @click.stop="show = false">
          <span class="material-symbols text-3xl text-fg">chevron_left</span>
        </button>
        <div class="flex-grow" />
        <button v-if="isEpub" type="button" class="inline-flex mx-2" @click.stop="clickSearchBtn">
          <span class="material-symbols text-2xl text-fg">search</span>
        </button>
        <button v-if="isEpub" type="button" class="inline-flex mx-1" :disabled="!hasActiveSearch" @click.stop="toolbarPrevSearchResult">
          <span class="material-symbols text-2xl text-fg" :class="{ 'opacity-40': !hasActiveSearch }">arrow_upward</span>
        </button>
        <button v-if="isEpub" type="button" class="inline-flex mx-1" :disabled="!hasActiveSearch" @click.stop="toolbarNextSearchResult">
          <span class="material-symbols text-2xl text-fg" :class="{ 'opacity-40': !hasActiveSearch }">arrow_downward</span>
        </button>
        <button v-if="isComic || isEpub" type="button" class="inline-flex mx-2" @click.stop="clickTOCBtn">
          <span class="material-symbols text-2xl text-fg">format_list_bulleted</span>
        </button>
        <button v-if="isEpub" type="button" class="inline-flex mx-2" @click.stop="clickSettingsBtn">
          <span class="material-symbols text-2xl text-fg">settings</span>
        </button>
        <button v-if="comicHasMetadata" type="button" class="inline-flex mx-2" @click.stop="clickMetadataBtn">
          <span class="material-symbols text-2xl text-fg">more</span>
        </button>
      </div>

      <p class="text-center truncate">{{ title }}</p>
    </div>

    <!-- ereader -->
    <component v-if="readerComponentName" ref="readerComponent" :is="readerComponentName" :url="ebookUrl" :library-item="selectedLibraryItem" :is-local="isLocal" :keep-progress="keepProgress" :showing-toolbar="showingToolbar" @touchstart="touchstart" @touchend="touchend" @loaded="readerLoaded" @hook:mounted="readerMounted" @search-results="onSearchResults" />

    <!-- table of contents modal -->
    <modals-fullscreen-modal v-model="showTOCModal" :theme="ereaderTheme">
      <div class="flex items-end justify-between h-20 px-4 pb-2">
        <h1 class="text-lg">{{ $strings.HeaderTableOfContents }}</h1>
        <button class="flex" @click.stop="showTOCModal = false">
          <span class="material-symbols">close</span>
        </button>
      </div>

      <!-- chapters list -->
      <div class="w-full overflow-y-auto overflow-x-hidden h-full max-h-[calc(100vh-85px)]">
        <div class="w-full h-full px-4">
          <ul>
            <li v-for="chapter in chapters" :key="chapter.id" class="py-1">
              <a :href="chapter.href" class="opacity-80 hover:opacity-100" @click.prevent="goToChapter(chapter.href)">{{ chapter.label }}</a>
              <ul v-if="chapter.subitems.length">
                <li v-for="subchapter in chapter.subitems" :key="subchapter.id" class="py-1 pl-4">
                  <a :href="subchapter.href" class="opacity-80 hover:opacity-100" @click.prevent="goToChapter(subchapter.href)">{{ subchapter.label }}</a>
                </li>
              </ul>
            </li>
          </ul>
          <div v-if="!chapters.length" class="flex h-full items-center justify-center">
            <p class="text-xl">{{ $strings.MessageNoChapters }}</p>
          </div>
        </div>
      </div>
    </modals-fullscreen-modal>

    <!-- ereader settings modal -->
    <modals-fullscreen-modal v-model="showSettingsModal" :theme="ereaderTheme" threeQuartersScreen>
      <div style="box-shadow: 0px -8px 8px #11111155">
        <div class="flex items-end justify-between h-14 px-4 pb-2 mb-6">
          <h1 class="text-lg">{{ $strings.HeaderEreaderSettings }}</h1>
          <button class="flex" @click="showSettingsModal = false">
            <span class="material-symbols">close</span>
          </button>
        </div>
        <div class="w-full overflow-y-auto overflow-x-hidden h-full max-h-[calc(75vh-85px)]">
          <div class="w-full h-full px-4">
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-sm">{{ $strings.LabelTheme }}</p>
              </div>
              <ui-toggle-btns v-model="ereaderSettings.theme" name="theme" :items="themeItems" @input="settingsUpdated" />
            </div>
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-sm">{{ $strings.LabelFontScale }}</p>
              </div>
              <ui-range-input v-model="ereaderSettings.fontScale" :min="5" :max="300" :step="5" input-width="180px" @input="settingsUpdated" />
            </div>
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-sm">{{ $strings.LabelLineSpacing }}</p>
              </div>
              <ui-range-input v-model="ereaderSettings.lineSpacing" :min="100" :max="300" :step="5" input-width="180px" @input="settingsUpdated" />
            </div>
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-sm">{{ $strings.LabelFontBoldness }}</p>
              </div>
              <ui-range-input v-model="ereaderSettings.textStroke" :min="0" :max="300" :step="5" input-width="180px" @input="settingsUpdated" />
            </div>
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-sm">{{ $strings.LabelLayout }}</p>
              </div>
              <ui-toggle-btns v-model="ereaderSettings.spread" name="spread" :items="spreadItems" @input="settingsUpdated" />
            </div>
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-sm">{{ $strings.LabelNavigateWithVolume }}</p>
              </div>
              <ui-toggle-btns v-model="ereaderSettings.navigateWithVolume" name="navigate-volume" :items="navigateWithVolumeItems" @input="settingsUpdated" />
            </div>
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-sm">{{ $strings.LabelNavigateWithVolumeWhilePlaying }}</p>
              </div>
              <ui-toggle-btns v-model="ereaderSettings.navigateWithVolumeWhilePlaying" name="navigate-volume-playing" :items="onOffToggleButtonItems" @input="settingsUpdated" />
            </div>
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-sm">{{ $strings.LabelKeepScreenAwake }}</p>
              </div>
              <ui-toggle-btns v-model="ereaderSettings.keepScreenAwake" name="keep-awake" :items="onOffToggleButtonItems" @input="settingsUpdated" />
            </div>
          </div>
        </div>
      </div>
    </modals-fullscreen-modal>

    <!-- epub search modal -->
    <modals-fullscreen-modal v-model="showSearchModal" :theme="ereaderTheme" threeQuartersScreen>
      <div style="box-shadow: 0px -8px 8px #11111155">
        <div class="flex items-end justify-between h-14 px-4 pb-2 mb-6">
          <h1 class="text-lg">{{ $strings.HeaderSearch }}</h1>
          <button class="flex" @click="showSearchModal = false">
            <span class="material-symbols">close</span>
          </button>
        </div>
        <div class="w-full overflow-y-auto overflow-x-hidden h-full max-h-[calc(75vh-85px)]">
          <div class="w-full h-full px-4">
            <div class="flex items-center gap-2 mb-4">
              <ui-text-input v-model="searchQuery" :placeholder="$strings.PlaceholderSearchEbook" prepend-icon="search" clearable @keyup.enter.native="performSearch" />
              <ui-btn :disabled="!searchQuery || searching" @click="performSearch">{{ $strings.ButtonSearch }}</ui-btn>
            </div>

            <div class="flex items-center justify-between mb-2" v-if="searchResults.length">
              <p class="text-sm opacity-80">{{ $getString('LabelSearchResultsCount', [String(searchResults.length)]) }}</p>
              <div class="flex items-center gap-2">
                <ui-btn :small="true" @click="prevResult">{{ $strings.ButtonPrevious }}</ui-btn>
                <p class="text-sm">{{ currentSearchIndex + 1 }} / {{ searchResults.length }}</p>
                <ui-btn :small="true" @click="nextResult">{{ $strings.ButtonNext }}</ui-btn>
              </div>
            </div>

            <ul>
              <li v-for="(r, idx) in searchResults" :key="r.cfi" class="py-2 border-b border-border cursor-pointer" :class="{ 'opacity-100': idx === currentSearchIndex, 'opacity-80 hover:opacity-100': idx !== currentSearchIndex }" @click="goToResult(idx)">
                <p class="text-sm truncate" v-if="r.excerpt">{{ r.excerpt }}</p>
                <p class="text-xs opacity-60">CFI: {{ r.cfi }}</p>
              </li>
            </ul>

            <div v-if="!searching && searched && !searchResults.length" class="mt-4 opacity-80">
              <p>{{ $strings.MessageNoItemsFound }}</p>
            </div>
          </div>
        </div>
      </div>
    </modals-fullscreen-modal>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { VolumeButtons } from '@capacitor-community/volume-buttons'
import { KeepAwake } from '@capacitor-community/keep-awake'

export default {
  data() {
    return {
      touchstartX: 0,
      touchstartY: 0,
      touchendX: 0,
      touchendY: 0,
      touchstartTime: 0,
      touchIdentifier: null,
      showingToolbar: false,
      showTOCModal: false,
      showSettingsModal: false,
      showSearchModal: false,
      comicHasMetadata: false,
      chapters: [],
      isInittingWatchVolume: false,
      // Search UI state
      searchQuery: '',
      searchResults: [],
      currentSearchIndex: -1,
      searching: false,
      searched: false,
      ereaderSettings: {
        theme: 'dark',
        fontScale: 100,
        lineSpacing: 115,
        spread: 'auto',
        textStroke: 0,
        navigateWithVolume: 'enabled',
        navigateWithVolumeWhilePlaying: false,
        keepScreenAwake: false
      }
    }
  },
  watch: {
    show: {
      handler(newVal) {
        if (newVal) {
          this.comicHasMetadata = false
          this.registerListeners()
          this.hideToolbar()
        } else {
          this.unregisterListeners()
          this.$showHideStatusBar(true)
        }
      }
    },
    isPlayerOpen(newVal, oldVal) {
      // Closed player
      if (!newVal && oldVal) {
        this.initWatchVolume()
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.$store.state.showReader
      },
      set(val) {
        this.$store.commit('setShowReader', val)
      }
    },
    title() {
      return this.mediaMetadata.title || 'No Title'
    },
    selectedLibraryItem() {
      return this.$store.state.selectedLibraryItem
    },
    media() {
      return this.selectedLibraryItem?.media || null
    },
    mediaMetadata() {
      return this.media?.metadata || {}
    },
    ereaderTheme() {
      if (this.isEpub) return this.ereaderSettings.theme
      return document.documentElement.dataset.theme || 'dark'
    },
    spreadItems() {
      return [
        {
          text: this.$strings.LabelLayoutSinglePage,
          value: 'none'
        },
        {
          text: this.$strings.LabelLayoutAuto,
          value: 'auto'
        }
      ]
    },
    navigateWithVolumeItems() {
      return [
        {
          text: this.$strings.LabelOn,
          value: 'enabled'
        },
        {
          text: this.$strings.LabelNavigateWithVolumeMirrored,
          value: 'mirrored'
        },
        {
          text: this.$strings.LabelOff,
          value: 'none'
        }
      ]
    },
    onOffToggleButtonItems() {
      return [
        {
          text: this.$strings.LabelOn,
          value: true
        },
        {
          text: this.$strings.LabelOff,
          value: false
        }
      ]
    },
    themeItems() {
      return [
        {
          text: this.$strings.LabelThemeBlack,
          value: 'black'
        },
        {
          text: this.$strings.LabelThemeDark,
          value: 'dark'
        },
        {
          text: this.$strings.LabelThemeLight,
          value: 'light'
        }
      ]
    },
    readerComponentName() {
      if (this.ebookType === 'epub') return 'readers-epub-reader'
      else if (this.ebookType === 'mobi') return 'readers-mobi-reader'
      else if (this.ebookType === 'comic') return 'readers-comic-reader'
      else if (this.ebookType === 'pdf') return 'readers-pdf-reader'
      return null
    },
    ebookFile() {
      if (!this.media) return null
      // ebook file id is passed when reading a supplementary ebook
      if (this.ebookFileId) {
        return this.selectedLibraryItem.libraryFiles.find((lf) => lf.ino === this.ebookFileId)
      }
      return this.media.ebookFile
    },
    ebookFormat() {
      if (!this.ebookFile) return null
      // Use file extension for supplementary ebook
      if (!this.ebookFile.ebookFormat) {
        return this.ebookFile.metadata.ext.toLowerCase().slice(1)
      }
      return this.ebookFile.ebookFormat
    },
    ebookType() {
      if (this.isMobi) return 'mobi'
      else if (this.isEpub) return 'epub'
      else if (this.isPdf) return 'pdf'
      else if (this.isComic) return 'comic'
      return null
    },
    isEpub() {
      return this.ebookFormat == 'epub'
    },
    isMobi() {
      return this.ebookFormat == 'mobi' || this.ebookFormat == 'azw3'
    },
    isPdf() {
      return this.ebookFormat == 'pdf'
    },
    isComic() {
      return this.ebookFormat == 'cbz' || this.ebookFormat == 'cbr'
    },
    isLocal() {
      return !!this.ebookFile?.isLocal || !!this.ebookFile?.localFileId
    },
    localContentUrl() {
      return this.ebookFile?.contentUrl
    },
    ebookUrl() {
      if (!this.ebookFile) return null
      if (this.localContentUrl) {
        return Capacitor.convertFileSrc(this.localContentUrl)
      }

      if (this.ebookFileId) {
        return `/api/items/${this.selectedLibraryItem.id}/ebook/${this.ebookFileId}`
      }
      return `/api/items/${this.selectedLibraryItem.id}/ebook`
    },
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    },
    keepProgress() {
      return this.$store.state.ereaderKeepProgress
    },
    ebookFileId() {
      return this.$store.state.ereaderFileId
    },
    hasActiveSearch() {
      return this.isEpub && this.searchResults.length > 0 && this.currentSearchIndex >= 0
    }
  },
  methods: {
    settingsUpdated() {
      this.$refs.readerComponent?.updateSettings?.(this.ereaderSettings)
      localStorage.setItem('ereaderSettings', JSON.stringify(this.ereaderSettings))

      this.initWatchVolume()
      this.initKeepScreenAwake()
    },
    goToChapter(href) {
      this.showTOCModal = false
      this.$refs.readerComponent?.goToChapter(href)
    },
    readerMounted() {
      if (this.isEpub) {
        this.loadEreaderSettings()
      }
    },
    readerLoaded(data) {
      if (this.isComic) {
        this.comicHasMetadata = data.hasMetadata
      }
    },
    clickMetadataBtn() {
      this.$refs.readerComponent?.clickShowInfoMenu()
    },
    clickTOCBtn() {
      this.hideToolbar()
      if (this.isComic) {
        this.$refs.readerComponent?.clickShowPageMenu?.()
      } else {
        this.chapters = this.$refs.readerComponent?.chapters || []
        this.showTOCModal = true
      }
    },
    clickSettingsBtn() {
      this.hideToolbar()
      this.showSettingsModal = true
    },
    clickSearchBtn() {
      this.hideToolbar()
      this.showSearchModal = true
      this.$nextTick(() => {})
    },
    async performSearch() {
      if (!this.isEpub || !this.$refs.readerComponent?.searchEbook) return
      if (!this.searchQuery || !this.searchQuery.trim()) return
      this.searching = true
      this.searched = true
      const results = await this.$refs.readerComponent.searchEbook(this.searchQuery)
      this.searchResults = results || []
      this.currentSearchIndex = this.searchResults.length ? 0 : -1
      this.searching = false
    },
    nextResult() {
      if (!this.isEpub) return
      const rc = this.$refs.readerComponent
      if (!rc?.nextSearchResult) return
      // Ensure search results exist; if not, re-run last query
      const ensure = rc.ensureSearch ? rc.ensureSearch(this.searchQuery) : Promise.resolve(null)
      Promise.resolve(ensure)
        .then(() => rc.nextSearchResult())
        .then((r) => {
          if (!r) return
          this.currentSearchIndex = rc.getEbookSearchState().index
        })
    },
    prevResult() {
      if (!this.isEpub) return
      const rc = this.$refs.readerComponent
      if (!rc?.prevSearchResult) return
      const ensure = rc.ensureSearch ? rc.ensureSearch(this.searchQuery) : Promise.resolve(null)
      Promise.resolve(ensure)
        .then(() => rc.prevSearchResult())
        .then((r) => {
          if (!r) return
          this.currentSearchIndex = rc.getEbookSearchState().index
        })
    },
    goToResult(idx) {
      if (!this.isEpub || !this.$refs.readerComponent?.goToSearchResult) return
      this.$refs.readerComponent.goToSearchResult(idx).then((r) => {
        if (!r) return
        // Update current index from child state (in case of wrap)
        if (this.$refs.readerComponent?.getEbookSearchState) {
          this.currentSearchIndex = this.$refs.readerComponent.getEbookSearchState().index
        } else {
          this.currentSearchIndex = idx
        }
        // Close search modal after navigating to selection
        this.showSearchModal = false
      })
    },
    toolbarNextSearchResult() {
      if (!this.hasActiveSearch) return
      this.nextResult()
    },
    toolbarPrevSearchResult() {
      if (!this.hasActiveSearch) return
      this.prevResult()
    },
    onSearchResults(payload) {
      this.searchQuery = payload?.query || this.searchQuery
      this.searchResults = payload?.results || []
      this.currentSearchIndex = payload?.index ?? this.currentSearchIndex
    },
    next() {
      if (this.$refs.readerComponent && this.$refs.readerComponent.next) {
        this.$refs.readerComponent.next()
      }
    },
    prev() {
      if (this.$refs.readerComponent && this.$refs.readerComponent.prev) {
        this.$refs.readerComponent.prev()
      }
    },
    handleGesture() {
      // Touch must be less than 1s. Must be > 60px drag and X distance > Y distance
      const touchTimeMs = Date.now() - this.touchstartTime
      if (touchTimeMs >= 1000) {
        return
      }

      const touchDistanceX = Math.abs(this.touchendX - this.touchstartX)
      const touchDistanceY = Math.abs(this.touchendY - this.touchstartY)
      const touchDistance = Math.sqrt(Math.pow(this.touchstartX - this.touchendX, 2) + Math.pow(this.touchstartY - this.touchendY, 2))
      if (touchDistance < 30) {
        if (this.showSettingsModal) {
          this.showSettingsModal = false
        } else {
          this.toggleToolbar()
        }
        return
      }

      if (touchDistanceX < 60 || touchDistanceY > touchDistanceX) {
        return
      }
      this.hideToolbar()
      if (!this.isEpub) {
        if (this.touchendX < this.touchstartX) {
          this.next()
        }
        if (this.touchendX > this.touchstartX) {
          this.prev()
        }
      }
    },
    showToolbar() {
      this.showingToolbar = true
      this.$showHideStatusBar(true)
    },
    hideToolbar() {
      this.showingToolbar = false
      this.$showHideStatusBar(false)
    },
    toggleToolbar() {
      if (this.showingToolbar) this.hideToolbar()
      else this.showToolbar()
    },
    touchstart(e) {
      // Ignore rapid touch
      if (this.touchstartTime && Date.now() - this.touchstartTime < 250) {
        return
      }

      this.touchstartX = e.touches[0].screenX
      this.touchstartY = e.touches[0].screenY
      this.touchstartTime = Date.now()
      this.touchIdentifier = e.touches[0].identifier
    },
    touchend(e) {
      if (this.touchIdentifier !== e.changedTouches[0].identifier) {
        return
      }

      this.touchendX = e.changedTouches[0].screenX
      this.touchendY = e.changedTouches[0].screenY
      this.handleGesture()
    },
    closeEvt() {
      this.show = false
    },
    loadEreaderSettings() {
      try {
        const settings = localStorage.getItem('ereaderSettings')
        if (settings) {
          const _ereaderSettings = JSON.parse(settings)
          for (const key in this.ereaderSettings) {
            if (_ereaderSettings[key] !== undefined) {
              this.ereaderSettings[key] = _ereaderSettings[key]
            }
          }
          this.settingsUpdated()
        }
      } catch (error) {
        console.error('Failed to load ereader settings', error)
      }
    },
    async initWatchVolume() {
      if (this.isInittingWatchVolume || !this.isEpub) return
      this.isInittingWatchVolume = true
      const isWatching = await VolumeButtons.isWatching()

      if (this.ereaderSettings.navigateWithVolume !== 'none' && (this.ereaderSettings.navigateWithVolumeWhilePlaying || !this.isPlayerOpen)) {
        if (!isWatching.value) {
          const options = {
            disableSystemVolumeHandler: true,
            suppressVolumeIndicator: true
          }
          await VolumeButtons.watchVolume(options, this.volumePressed)
        }
      } else if (isWatching.value) {
        await VolumeButtons.clearWatch().catch((error) => {
          console.error('Failed to clear volume watch', error)
        })
      }

      this.isInittingWatchVolume = false
    },
    async initKeepScreenAwake() {
      try {
        if (this.ereaderSettings.keepScreenAwake) {
          await KeepAwake.keepAwake()
          console.log('Reader keep screen awake enabled')
        } else {
          await KeepAwake.allowSleep()
          console.log('Reader keep screen awake disabled')
        }
      } catch (error) {
        console.error('Failed to init keep screen awake', error)
      }
    },
    registerListeners() {
      this.$eventBus.$on('close-ebook', this.closeEvt)
      document.body.addEventListener('touchstart', this.touchstart)
      document.body.addEventListener('touchend', this.touchend)
      this.initWatchVolume()
      this.initKeepScreenAwake()
    },
    unregisterListeners() {
      this.$eventBus.$on('close-ebook', this.closeEvt)
      document.body.removeEventListener('touchstart', this.touchstart)
      document.body.removeEventListener('touchend', this.touchend)
      VolumeButtons.clearWatch().catch((error) => {
        console.error('Failed to clear volume watch', error)
      })
      KeepAwake.allowSleep().catch((error) => {
        console.error('Failed to allow sleep', error)
      })
    },
    volumePressed(e) {
      if (this.ereaderSettings.navigateWithVolume == 'enabled') {
        if (e.direction == 'up') {
          this.prev()
        } else {
          this.next()
        }
      } else if (this.ereaderSettings.navigateWithVolume == 'mirrored') {
        if (e.direction == 'down') {
          this.prev()
        } else {
          this.next()
        }
      }
    }
  },
  beforeDestroy() {
    this.unregisterListeners()
  }
}
</script>
