<template>
  <div v-if="show" :data-theme="ereaderTheme" class="group fixed top-0 left-0 right-0 layout-wrapper w-full z-40 pt-8 data-[theme=dark]:bg-[#232323] data-[theme=dark]:text-white data-[theme=light]:bg-white data-[theme=light]:text-black" :class="{ 'reader-player-open': isPlayerOpen }">
    <!-- toolbar -->
    <div class="h-32 pt-10 w-full px-2 fixed top-0 left-0 z-30 transition-transform bg-bg text-fg" :class="showingToolbar ? 'translate-y-0' : '-translate-y-32'" :style="{ boxShadow: showingToolbar ? '0px 8px 8px #11111155' : '' }" @touchstart.stop @mousedown.stop @touchend.stop @mouseup.stop>
      <div class="flex items-center mb-2">
        <button type="button" class="inline-flex mx-2" @click.stop="show = false">
          <span class="material-icons-outlined text-3xl text-fg">chevron_left</span>
        </button>
        <div class="flex-grow" />
        <button v-if="isComic || isEpub" type="button" class="inline-flex mx-2" @click.stop="clickTOCBtn">
          <span class="material-icons-outlined text-2xl text-fg">format_list_bulleted</span>
        </button>
        <button v-if="isEpub" type="button" class="inline-flex mx-2" @click.stop="clickSettingsBtn">
          <span class="material-icons text-2xl text-fg">settings</span>
        </button>
        <button v-if="comicHasMetadata" type="button" class="inline-flex mx-2" @click.stop="clickMetadataBtn">
          <span class="material-icons text-2xl text-fg">more</span>
        </button>
      </div>

      <p class="text-center truncate">{{ title }}</p>
    </div>

    <!-- ereader -->
    <component v-if="readerComponentName" ref="readerComponent" :is="readerComponentName" :url="ebookUrl" :library-item="selectedLibraryItem" :is-local="isLocal" :keep-progress="keepProgress" @touchstart="touchstart" @touchend="touchend" @loaded="readerLoaded" @hook:mounted="readerMounted" />

    <!-- table of contents modal -->
    <modals-fullscreen-modal v-model="showTOCModal" :theme="ereaderTheme">
      <div class="flex items-end justify-between h-20 px-4 pb-2">
        <h1 class="text-lg">{{ $strings.HeaderTableOfContents }}</h1>
        <button class="flex" @click.stop="showTOCModal = false">
          <span class="material-icons">close</span>
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
    <modals-fullscreen-modal v-model="showSettingsModal" :theme="ereaderTheme" half-screen>
      <div style="box-shadow: 0px -8px 8px #11111155">
        <div class="flex items-end justify-between h-20 px-4 pb-2 mb-6">
          <h1 class="text-lg">{{ $strings.HeaderEreaderSettings }}</h1>
          <button class="flex" @click="showSettingsModal = false">
            <span class="material-icons">close</span>
          </button>
        </div>
        <div class="w-full overflow-y-auto overflow-x-hidden h-full max-h-[calc(100vh-85px)]">
          <div class="w-full h-full px-4">
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-base">{{ $strings.LabelTheme }}:</p>
              </div>
              <ui-toggle-btns v-model="ereaderSettings.theme" :items="themeItems" @input="settingsUpdated" />
            </div>
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-base">{{ $strings.LabelFontScale }}:</p>
              </div>
              <ui-range-input v-model="ereaderSettings.fontScale" :min="5" :max="300" :step="5" input-width="180px" @input="settingsUpdated" />
            </div>
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-base">{{ $strings.LabelLineSpacing }}:</p>
              </div>
              <ui-range-input v-model="ereaderSettings.lineSpacing" :min="100" :max="300" :step="5" input-width="180px" @input="settingsUpdated" />
            </div>
            <div class="flex items-center mb-6">
              <div class="w-32">
                <p class="text-base">{{ $strings.LabelFontBoldness }}:</p>
              </div>
              <ui-range-input v-model="ereaderSettings.textStroke" :min="0" :max="300" :step="5" input-width="180px" @input="settingsUpdated" />
            </div>
            <div class="flex items-center">
              <div class="w-32">
                <p class="text-base">{{ $strings.LabelLayout }}:</p>
              </div>
              <ui-toggle-btns v-model="ereaderSettings.spread" :items="spreadItems" @input="settingsUpdated" />
            </div>
          </div>
        </div>
      </div>
    </modals-fullscreen-modal>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'

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
      comicHasMetadata: false,
      chapters: [],
      ereaderSettings: {
        theme: 'dark',
        fontScale: 100,
        lineSpacing: 115,
        spread: 'auto',
        textStroke: 0
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

          console.log('showReader for ebookFile', JSON.stringify(this.ebookFile))
        } else {
          this.unregisterListeners()
          this.$showHideStatusBar(true)
        }
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
    themeItems() {
      return [
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
      const serverAddress = this.$store.getters['user/getServerAddress']
      if (this.ebookFileId) {
        return `${serverAddress}/api/items/${this.selectedLibraryItem.id}/ebook/${this.ebookFileId}`
      }
      return `${serverAddress}/api/items/${this.selectedLibraryItem.id}/ebook`
    },
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    },
    keepProgress() {
      return this.$store.state.ereaderKeepProgress
    },
    ebookFileId() {
      return this.$store.state.ereaderFileId
    }
  },
  methods: {
    settingsUpdated() {
      this.$refs.readerComponent?.updateSettings?.(this.ereaderSettings)
      localStorage.setItem('ereaderSettings', JSON.stringify(this.ereaderSettings))
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
    registerListeners() {
      this.$eventBus.$on('close-ebook', this.closeEvt)
      document.body.addEventListener('touchstart', this.touchstart)
      document.body.addEventListener('touchend', this.touchend)
    },
    unregisterListeners() {
      this.$eventBus.$on('close-ebook', this.closeEvt)
      document.body.removeEventListener('touchstart', this.touchstart)
      document.body.removeEventListener('touchend', this.touchend)
    }
  },
  beforeDestroy() {
    this.unregisterListeners()
  }
}
</script>
