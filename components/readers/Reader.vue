<template>
  <div v-if="show" class="fixed top-0 left-0 right-0 layout-wrapper w-full bg-primary z-40 pt-8" :class="{ 'reader-player-open': !!playerLibraryItemId }">
    <div class="h-28 pt-8 w-full bg-bg px-2 fixed top-0 left-0 z-30 transition-transform" :class="showingToolbar ? 'translate-y-0' : '-translate-y-28'" @touchstart.stop @mousedown.stop @touchend.stop @mouseup.stop>
      <div class="flex items-center mb-2">
        <button type="button" class="inline-flex mx-2" @click.stop="show = false"><span class="material-icons-outlined text-3xl text-white">chevron_left</span></button>
        <div class="flex-grow" />
        <button v-if="isComic" type="button" class="inline-flex mx-2" @click.stop="clickTOCBtn"><span class="material-icons-outlined text-2xl text-white">format_list_bulleted</span></button>
        <!-- <button v-if="isEpub" type="button" class="inline-flex mx-2" @click.stop="clickSettingsBtn"><span class="material-icons text-2xl text-white">settings</span></button> -->
        <button v-if="comicHasMetadata" type="button" class="inline-flex mx-2" @click.stop="clickMetadataBtn"><span class="material-icons text-2xl text-white">more</span></button>
      </div>

      <p class="text-center truncate">{{ title }}</p>
    </div>

    <component v-if="readerComponentName" ref="readerComponent" :is="readerComponentName" :url="ebookUrl" :library-item="selectedLibraryItem" :is-local="isLocal" :keep-progress="keepProgress" @touchstart="touchstart" @touchend="touchend" @loaded="readerLoaded" />
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
      chapters: []
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
      return !!this.ebookFile?.isLocal
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
    playerLibraryItemId() {
      return this.$store.state.playerLibraryItemId
    },
    keepProgress() {
      return this.$store.state.ereaderKeepProgress
    },
    ebookFileId() {
      return this.$store.state.ereaderFileId
    }
  },
  methods: {
    readerLoaded(data) {
      if (this.isComic) {
        this.comicHasMetadata = data.hasMetadata
      }
    },
    clickMetadataBtn() {
      this.$refs.readerComponent?.clickShowInfoMenu()
    },
    clickTOCBtn() {
      if (this.isComic) {
        this.$refs.readerComponent?.clickShowPageMenu?.()
      } else {
        this.chapters = this.$refs.readerComponent?.chapters || []
        this.showTOCModal = true
      }
    },
    clickSettingsBtn() {
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
        console.log('Touch too long', touchTimeMs)
        return
      }

      const touchDistanceX = Math.abs(this.touchendX - this.touchstartX)
      const touchDistanceY = Math.abs(this.touchendY - this.touchstartY)
      const touchDistance = Math.sqrt(Math.pow(this.touchstartX - this.touchendX, 2) + Math.pow(this.touchstartY - this.touchendY, 2))
      if (touchDistance < 60) {
        this.toggleToolbar()
        return
      }

      if (touchDistanceX < 60 || touchDistanceY > touchDistanceX) {
        return
      }
      this.hideToolbar()
      if (this.touchendX < this.touchstartX) {
        this.next()
      }
      if (this.touchendX > this.touchstartX) {
        this.prev()
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