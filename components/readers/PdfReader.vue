<template>
  <div class="w-full h-full relative overflow-hidden">
    <!-- Navigation overlays (nascosti durante zoom) -->
    <div v-show="canGoPrev && !isZooming" class="absolute top-0 left-0 h-full w-1/4 hover:opacity-100 opacity-0 z-10 cursor-pointer" @click.stop.prevent="prev" @mousedown.prevent>
      <div class="flex items-center justify-center h-full w-1/2">
        <span class="material-symbols text-5xl text-white cursor-pointer text-opacity-30 hover:text-opacity-90">arrow_back_ios</span>
      </div>
    </div>
    <div v-show="canGoNext && !isZooming" class="absolute top-0 right-0 h-full w-1/4 hover:opacity-100 opacity-0 z-10 cursor-pointer" @click.stop.prevent="next" @mousedown.prevent>
      <div class="flex items-center justify-center h-full w-1/2 ml-auto">
        <span class="material-symbols text-5xl text-white cursor-pointer text-opacity-30 hover:text-opacity-90">arrow_forward_ios</span>
      </div>
    </div>

    <!-- Contenitore PDF con zoom -->
    <div ref="pdfContainer" class="h-full flex items-center justify-center relative overflow-hidden" @touchstart.passive="handleTouchStart" @touchmove.passive="handleTouchMove" @touchend.passive="handleTouchEnd" @wheel.prevent="handleWheel">
      <div
        ref="pdfWrapper"
        :style="{
          width: pdfWidth + 'px',
          height: pdfHeight + 'px',
          transform: `scale(${scale}) translate(${translateX}px, ${translateY}px)`,
          transformOrigin: 'center center',
          transition: isAnimating ? 'transform 0.3s ease-out' : 'none',
          touchAction: 'none',
          userSelect: 'none'
        }"
        class="relative"
      >
        <div v-if="loadedRatio > 0 && loadedRatio < 1" style="background-color: green; color: white; text-align: center" :style="{ width: loadedRatio * 100 + '%' }">{{ Math.floor(loadedRatio * 100) }}%</div>

        <pdf v-if="pdfDocInitParams" ref="pdf" class="m-auto z-10 border border-black border-opacity-20 shadow-md bg-white" :src="pdfDocInitParams" :page="page" :rotate="rotate" @progress="loadedRatio = $event" @error="error" @num-pages="numPagesLoaded" @link-clicked="page = $event" @loaded="loadedEvt" />
      </div>
    </div>

    <!-- Controlli zoom -->
    <div class="absolute top-4 right-4 flex flex-col space-y-2 z-20">
      <button @click="zoomIn" class="w-12 h-12 bg-black bg-opacity-50 text-white rounded-full flex items-center justify-center">
        <span class="material-symbols text-xl">add</span>
      </button>
      <button @click="zoomOut" class="w-12 h-12 bg-black bg-opacity-50 text-white rounded-full flex items-center justify-center">
        <span class="material-symbols text-xl">remove</span>
      </button>
      <button @click="resetZoom" class="w-12 h-12 bg-black bg-opacity-50 text-white rounded-full flex items-center justify-center">
        <span class="material-symbols text-xl">center_focus_strong</span>
      </button>
    </div>

    <!-- Indicatore zoom -->
    <div v-if="scale !== 1" class="absolute top-4 left-4 bg-black bg-opacity-50 text-white px-3 py-1 rounded-full text-sm z-20">{{ Math.round(scale * 100) }}%</div>

    <!-- Barra di navigazione -->
    <div class="fixed left-0 h-16 w-full bg-bg px-4 flex items-center text-fg-muted z-20" :style="{ bottom: isPlayerOpen ? '120px' : '0px' }">
      <button v-show="canGoPrev" @click="prev" class="flex items-center justify-center w-12 h-8 bg-primary text-white rounded-l mr-2">
        <span class="material-symbols text-lg">chevron_left</span>
      </button>

      <div class="flex-grow text-center">
        <p class="text-sm">{{ page }} / {{ numPages }}</p>
      </div>

      <button v-show="canGoNext" @click="next" class="flex items-center justify-center w-12 h-8 bg-primary text-white rounded-r ml-2">
        <span class="material-symbols text-lg">chevron_right</span>
      </button>
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
      pdfDocInitParams: null,

      // Zoom e pan
      scale: 1,
      translateX: 0,
      translateY: 0,
      minScale: 0.5,
      maxScale: 4,
      isAnimating: false,

      // Touch handling
      touches: [],
      lastDistance: 0,
      lastScale: 1,
      initialDistance: 0,
      isPanning: false,
      panStartX: 0,
      panStartY: 0,
      lastTranslateX: 0,
      lastTranslateY: 0,
      touchStartTime: 0,
      isZooming: false
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
      if (!this.libraryItem.serverAddress || !this.libraryItem.libraryItemId) return null
      if (this.$store.getters['user/getServerAddress'] === this.libraryItem.serverAddress) {
        return this.libraryItem.libraryItemId
      }
      return null
    },
    pdfWidth() {
      if (this.windowWidth > this.windowHeight) {
        return this.windowHeight * 0.6
      } else {
        return this.windowWidth * 0.9
      }
    },
    pdfHeight() {
      return this.pdfWidth * 1.4
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
      if (!this.userItemProgress?.ebookLocation || isNaN(this.userItemProgress.ebookLocation)) return 0
      return Number(this.userItemProgress.ebookLocation)
    },
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    }
  },
  methods: {
    // Gestione zoom
    zoomIn() {
      this.setScale(Math.min(this.scale * 1.5, this.maxScale))
    },

    zoomOut() {
      this.setScale(Math.max(this.scale / 1.5, this.minScale))
    },

    resetZoom() {
      this.setScale(1)
      this.translateX = 0
      this.translateY = 0
      this.isAnimating = true
      setTimeout(() => {
        this.isAnimating = false
      }, 300)
    },

    setScale(newScale) {
      this.scale = Math.max(this.minScale, Math.min(this.maxScale, newScale))
      this.constrainTranslation()
      this.isAnimating = true
      setTimeout(() => {
        this.isAnimating = false
      }, 300)
    },

    constrainTranslation() {
      if (this.scale <= 1) {
        this.translateX = 0
        this.translateY = 0
        return
      }

      const container = this.$refs.pdfContainer
      if (!container) return

      const containerRect = container.getBoundingClientRect()
      const scaledWidth = this.pdfWidth * this.scale
      const scaledHeight = this.pdfHeight * this.scale

      const maxTranslateX = Math.max(0, (scaledWidth - containerRect.width) / 2 / this.scale)
      const maxTranslateY = Math.max(0, (scaledHeight - containerRect.height) / 2 / this.scale)

      this.translateX = Math.max(-maxTranslateX, Math.min(maxTranslateX, this.translateX))
      this.translateY = Math.max(-maxTranslateY, Math.min(maxTranslateY, this.translateY))
    },

    // Gestione touch
    handleTouchStart(e) {
      this.touchStartTime = Date.now()
      this.touches = Array.from(e.touches)

      if (this.touches.length === 1) {
        // Single touch - possibile pan
        const touch = this.touches[0]
        this.panStartX = touch.clientX
        this.panStartY = touch.clientY
        this.lastTranslateX = this.translateX
        this.lastTranslateY = this.translateY
        this.isPanning = false
      } else if (this.touches.length === 2) {
        // Multi-touch - zoom
        this.isZooming = true
        this.initialDistance = this.getDistance(this.touches[0], this.touches[1])
        this.lastDistance = this.initialDistance
        this.lastScale = this.scale
      }
    },

    handleTouchMove(e) {
      e.preventDefault()
      this.touches = Array.from(e.touches)

      if (this.touches.length === 1 && this.scale > 1) {
        // Single touch pan (solo se zoomato)
        const touch = this.touches[0]
        const deltaX = touch.clientX - this.panStartX
        const deltaY = touch.clientY - this.panStartY

        // Soglia per iniziare il pan
        if (!this.isPanning && (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10)) {
          this.isPanning = true
        }

        if (this.isPanning) {
          this.translateX = this.lastTranslateX + deltaX / this.scale
          this.translateY = this.lastTranslateY + deltaY / this.scale
          this.constrainTranslation()
        }
      } else if (this.touches.length === 2) {
        // Multi-touch zoom
        const distance = this.getDistance(this.touches[0], this.touches[1])
        const scaleChange = distance / this.initialDistance

        this.scale = Math.max(this.minScale, Math.min(this.maxScale, this.lastScale * scaleChange))
        this.constrainTranslation()
      }
    },

    handleTouchEnd(e) {
      const touchDuration = Date.now() - this.touchStartTime

      // Se è un tap veloce e non stiamo facendo pan/zoom, potrebbe essere per navigazione
      if (touchDuration < 300 && !this.isPanning && !this.isZooming && this.scale === 1) {
        const touch = e.changedTouches[0]
        const containerRect = this.$refs.pdfContainer.getBoundingClientRect()
        const x = touch.clientX - containerRect.left
        const containerWidth = containerRect.width

        // Tap sulla sinistra (1/4 sinistro) = pagina precedente
        if (x < containerWidth * 0.25 && this.canGoPrev) {
          this.prev()
        }
        // Tap sulla destra (1/4 destro) = pagina successiva
        else if (x > containerWidth * 0.75 && this.canGoNext) {
          this.next()
        }
      }

      this.touches = []
      this.isPanning = false
      this.isZooming = false

      // Reset animation per smooth transition
      if (this.scale < 1.1 && this.scale > 0.9) {
        this.resetZoom()
      }
    },

    handleWheel(e) {
      e.preventDefault()
      const delta = e.deltaY > 0 ? 0.9 : 1.1
      this.setScale(this.scale * delta)
    },

    getDistance(touch1, touch2) {
      const dx = touch1.clientX - touch2.clientX
      const dy = touch1.clientY - touch2.clientY
      return Math.sqrt(dx * dx + dy * dy)
    },

    // Metodi esistenti
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
      // Emit per notificare il parent che il PDF è pronto per lo zoom
      this.$emit('pdf-zoom-ready')
    },

    numPagesLoaded(e) {
      if (!e) return
      this.numPages = e
    },

    prev() {
      if (this.page <= 1) return
      this.page--
      this.resetZoom() // Reset zoom quando cambi pagina
      this.updateProgress()
    },

    next() {
      if (this.page >= this.numPages) return
      this.page++
      this.resetZoom() // Reset zoom quando cambi pagina
      this.updateProgress()
    },

    error(err) {
      console.error(err)
    },

    screenOrientationChange() {
      this.windowWidth = window.innerWidth
      this.windowHeight = window.innerHeight
      this.resetZoom() // Reset zoom on orientation change
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
      screen.orientation.addEventListener('change', this.screenOrientationChange)
    } else {
      document.addEventListener('orientationchange', this.screenOrientationChange)
    }
    window.addEventListener('resize', this.screenOrientationChange)

    this.init()
  },

  beforeDestroy() {
    if (screen.orientation) {
      screen.orientation.removeEventListener('change', this.screenOrientationChange)
    } else {
      document.removeEventListener('orientationchange', this.screenOrientationChange)
    }
    window.removeEventListener('resize', this.screenOrientationChange)
  }
}
</script>
