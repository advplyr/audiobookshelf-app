<template>
  <div v-if="show" class="absolute top-0 left-0 w-full h-full bg-bg z-40 pt-16" :class="{ 'reader-player-open': !!playerLibraryItemId }">
    <div class="h-16 pt-8 w-full bg-primary flex items-center px-2 fixed top-0 left-0 z-30 box-shadow-sm">
      <p class="w-5/6 truncate">{{ title }}</p>
      <div class="flex-grow" />
      <span class="material-icons text-xl text-white" @click.stop="show = false">close</span>
    </div>
    <component v-if="readerComponentName" ref="readerComponent" :is="readerComponentName" :url="ebookUrl" />
  </div>
</template>

<script>
export default {
  data() {
    return {
      touchstartX: 0,
      touchstartY: 0,
      touchendX: 0,
      touchendY: 0,
      touchstartTime: 0
    }
  },
  watch: {
    show: {
      handler(newVal) {
        if (newVal) {
          this.registerListeners()
        } else {
          this.unregisterListeners()
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
      return this.selectedLibraryItem ? this.selectedLibraryItem.media : null
    },
    mediaMetadata() {
      return this.media ? this.media.metadata || {} : {}
    },
    readerComponentName() {
      if (this.ebookType === 'epub') return 'readers-epub-reader'
      else if (this.ebookType === 'mobi') return 'readers-mobi-reader'
      else if (this.ebookType === 'comic') return 'readers-comic-reader'
      else if (this.ebookType === 'pdf') return 'readers-pdf-reader'
      return null
    },
    folderId() {
      return this.selectedLibraryItem ? this.selectedLibraryItem.folderId : null
    },
    libraryId() {
      return this.selectedLibraryItem ? this.selectedLibraryItem.libraryId : null
    },
    ebookFile() {
      return this.media ? this.media.ebookFile : null
    },
    ebookFormat() {
      if (!this.ebookFile) return null
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
    ebookUrl() {
      if (!this.ebookFile) return null
      let filepath = ''
      if (this.selectedLibraryItem.isFile) {
        filepath = this.$encodeUriPath(this.ebookFile.metadata.filename)
      } else {
        const itemRelPath = this.selectedLibraryItem.relPath
        if (itemRelPath.startsWith('/')) itemRelPath = itemRelPath.slice(1)
        const relPath = this.ebookFile.metadata.relPath
        if (relPath.startsWith('/')) relPath = relPath.slice(1)

        filepath = this.$encodeUriPath(`${itemRelPath}/${relPath}`)
      }
      const serverAddress = this.$store.getters['user/getServerAddress']
      return `${serverAddress}/ebook/${this.libraryId}/${this.folderId}/${filepath}`
    },
    playerLibraryItemId() {
      return this.$store.state.playerLibraryItemId
    }
  },
  methods: {
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
      // Touch must be less than 1s. Must be > 100px drag and X distance > Y distance
      const touchTimeMs = Date.now() - this.touchstartTime
      if (touchTimeMs >= 1000) {
        console.log('Touch too long', touchTimeMs)
        return
      }

      const touchDistanceX = Math.abs(this.touchendX - this.touchstartX)
      const touchDistanceY = Math.abs(this.touchendY - this.touchstartY)
      if (touchDistanceX < 60 || touchDistanceY > touchDistanceX) {
        return
      }

      if (this.touchendX < this.touchstartX) {
        console.log('swiped left')
        this.next()
      }
      if (this.touchendX > this.touchstartX) {
        console.log('swiped right')
        this.prev()
      }
    },
    touchstart(e) {
      this.touchstartX = e.touches[0].screenX
      this.touchstartY = e.touches[0].screenY
      this.touchstartTime = Date.now()
    },
    touchend(e) {
      this.touchendX = e.changedTouches[0].screenX
      this.touchendY = e.changedTouches[0].screenY
      this.handleGesture()
    },
    registerListeners() {
      document.body.addEventListener('touchstart', this.touchstart)
      document.body.addEventListener('touchend', this.touchend)
    },
    unregisterListeners() {
      document.body.removeEventListener('touchstart', this.touchstart)
      document.body.removeEventListener('touchend', this.touchend)
    }
  },
  beforeDestroy() {
    this.unregisterListeners()
  }
}
</script>