<template>
  <div class="w-full h-full relative">
    <div v-show="canGoPrev" class="absolute top-0 left-0 h-full w-1/2 hover:opacity-100 opacity-0 z-10 cursor-pointer" @click.stop.prevent="prev" @mousedown.prevent>
      <div class="flex items-center justify-center h-full w-1/2">
        <span class="material-icons text-5xl text-white cursor-pointer text-opacity-30 hover:text-opacity-90">arrow_back_ios</span>
      </div>
    </div>
    <div v-show="canGoNext" class="absolute top-0 right-0 h-full w-1/2 hover:opacity-100 opacity-0 z-10 cursor-pointer" @click.stop.prevent="next" @mousedown.prevent>
      <div class="flex items-center justify-center h-full w-1/2 ml-auto">
        <span class="material-icons text-5xl text-white cursor-pointer text-opacity-30 hover:text-opacity-90">arrow_forward_ios</span>
      </div>
    </div>

    <div class="h-full flex items-center justify-center">
      <div :style="{ width: pdfWidth + 'px', height: pdfHeight + 'px' }" class="w-full h-full overflow-auto">
        <div v-if="loadedRatio > 0 && loadedRatio < 1" style="background-color: green; color: white; text-align: center" :style="{ width: loadedRatio * 100 + '%' }">{{ Math.floor(loadedRatio * 100) }}%</div>
        <pdf v-if="pdfDocInitParams" ref="pdf" class="m-auto z-10 border border-black border-opacity-20 shadow-md bg-white" :src="pdfDocInitParams" :page="page" :rotate="rotate" @progress="loadedRatio = $event" @error="error" @num-pages="numPagesLoaded" @link-clicked="page = $event" @loaded="loadedEvt"></pdf>
      </div>
    </div>

    <div class="fixed left-0 h-8 w-full bg-bg px-4 flex items-center text-fg-muted" :style="{ bottom: isPlayerOpen ? '120px' : '0px' }">
      <div class="flex-grow" />
      <p class="text-xs">{{ page }} / {{ numPages }}</p>
    </div>
  </div>
</template>

<script>
import pdf from '@teckel/vue-pdf'

export default {
  components: {
    pdf
  },
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
      rotate: 0,
      loadedRatio: 0,
      page: 1,
      numPages: 0,
      windowWidth: 0,
      windowHeight: 0,
      pdfDocInitParams: null
    }
  },
  computed: {
    userToken() {
      return this.$store.getters['user/getToken']
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
    pdfWidth() {
      if (this.windowWidth > this.windowHeight) {
        // Landscape
        return this.windowHeight * 0.6
      } else {
        // Portrait
        return this.windowWidth
      }
    },
    pdfHeight() {
      return this.pdfWidth * 1.667
    },
    canGoNext() {
      return this.page < this.numPages
    },
    canGoPrev() {
      return this.page > 1
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
    savedPage() {
      if (!this.keepProgress) return 0

      // Validate ebookLocation is a number
      if (!this.userItemProgress?.ebookLocation || isNaN(this.userItemProgress.ebookLocation)) return 0
      return Number(this.userItemProgress.ebookLocation)
    },
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    }
  },
  methods: {
    async updateProgress() {
      if (!this.keepProgress) return

      if (!this.numPages) {
        console.error('Num pages not loaded')
        return
      }

      const payload = {
        ebookLocation: String(this.page),
        ebookProgress: Math.max(0, Math.min(1, (Number(this.page) - 1) / Number(this.numPages)))
      }

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
          console.error('PdfReader.updateProgress failed:', error)
        })
      }
    },
    loadedEvt() {
      if (this.savedPage && this.savedPage > 0 && this.savedPage <= this.numPages) {
        this.page = this.savedPage
      }
    },
    numPagesLoaded(e) {
      if (!e) return

      this.numPages = e
    },
    prev() {
      if (this.page <= 1) return
      this.page--
      this.updateProgress()
    },
    next() {
      if (this.page >= this.numPages) return
      this.page++
      this.updateProgress()
    },
    error(err) {
      console.error(err)
    },
    screenOrientationChange() {
      this.windowWidth = window.innerWidth
      this.windowHeight = window.innerHeight
    },
    init() {
      this.pdfDocInitParams = {
        url: this.url,
        httpHeaders: {
          Authorization: `Bearer ${this.userToken}`
        }
      }
    }
  },
  mounted() {
    this.windowWidth = window.innerWidth
    this.windowHeight = window.innerHeight

    if (screen.orientation) {
      // Not available on ios
      screen.orientation.addEventListener('change', this.screenOrientationChange)
    } else {
      document.addEventListener('orientationchange', this.screenOrientationChange)
    }
    window.addEventListener('resize', this.screenOrientationChange)

    this.init()
  },
  beforeDestroy() {
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
