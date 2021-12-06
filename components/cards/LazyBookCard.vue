<template>
  <div ref="card" :id="`book-card-${index}`" :style="{ width: width + 'px', minWidth: width + 'px', height: height + 'px' }" class="rounded-sm z-10 bg-primary cursor-pointer box-shadow-book" @click="clickCard">
    <!-- When cover image does not fill -->
    <div v-show="showCoverBg" class="absolute top-0 left-0 w-full h-full overflow-hidden rounded-sm bg-primary">
      <div class="absolute cover-bg" ref="coverBg" />
    </div>

    <div class="w-full h-full absolute top-0 left-0 rounded overflow-hidden z-10">
      <div v-show="audiobook && !imageReady" class="absolute top-0 left-0 w-full h-full flex items-center justify-center" :style="{ padding: sizeMultiplier * 0.5 + 'rem' }">
        <p :style="{ fontSize: sizeMultiplier * 0.8 + 'rem' }" class="font-book text-gray-300 text-center">{{ title }}</p>
      </div>

      <img v-show="audiobook" ref="cover" :src="bookCoverSrc" class="w-full h-full object-contain transition-opacity duration-300" @load="imageLoaded" :style="{ opacity: imageReady ? 1 : 0 }" />

      <!-- Placeholder Cover Title & Author -->
      <div v-if="!hasCover" class="absolute top-0 left-0 right-0 bottom-0 w-full h-full flex items-center justify-center" :style="{ padding: placeholderCoverPadding + 'rem' }">
        <div>
          <p class="text-center font-book" style="color: rgb(247 223 187)" :style="{ fontSize: titleFontSize + 'rem' }">{{ titleCleaned }}</p>
        </div>
      </div>
      <div v-if="!hasCover" class="absolute left-0 right-0 w-full flex items-center justify-center" :style="{ padding: placeholderCoverPadding + 'rem', bottom: authorBottom + 'rem' }">
        <p class="text-center font-book" style="color: rgb(247 223 187); opacity: 0.75" :style="{ fontSize: authorFontSize + 'rem' }">{{ authorCleaned }}</p>
      </div>
    </div>

    <!-- Downloaded indicator icon -->
    <div v-if="hasDownload" class="absolute z-10" :style="{ top: 0.5 * sizeMultiplier + 'rem', right: 0.5 * sizeMultiplier + 'rem' }">
      <span class="material-icons text-success" :style="{ fontSize: 1.1 * sizeMultiplier + 'rem' }">download_done</span>
    </div>

    <!-- Progress bar -->
    <div class="absolute bottom-0 left-0 h-1 shadow-sm max-w-full z-10 rounded-b" :class="userIsRead ? 'bg-success' : 'bg-yellow-400'" :style="{ width: width * userProgressPercent + 'px' }"></div>

    <div v-if="showError" :style="{ height: 1.5 * sizeMultiplier + 'rem', width: 2.5 * sizeMultiplier + 'rem' }" class="bg-error rounded-r-full shadow-md flex items-center justify-end border-r border-b border-red-300">
      <span class="material-icons text-red-100 pr-1" :style="{ fontSize: 0.875 * sizeMultiplier + 'rem' }">priority_high</span>
    </div>

    <div v-if="volumeNumber && showVolumeNumber && !isHovering && !isSelectionMode" class="absolute rounded-lg bg-black bg-opacity-90 box-shadow-md z-10" :style="{ top: 0.375 * sizeMultiplier + 'rem', right: 0.375 * sizeMultiplier + 'rem', padding: `${0.1 * sizeMultiplier}rem ${0.25 * sizeMultiplier}rem` }">
      <p :style="{ fontSize: sizeMultiplier * 0.8 + 'rem' }">#{{ volumeNumber }}</p>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    index: Number,
    width: {
      type: Number,
      default: 120
    },
    height: {
      type: Number,
      default: 192
    },
    bookCoverAspectRatio: Number,
    showVolumeNumber: Boolean,
    bookMount: {
      type: Object,
      default: () => null
    }
  },
  data() {
    return {
      isHovering: false,
      isMoreMenuOpen: false,
      isProcessingReadUpdate: false,
      audiobook: null,
      imageReady: false,
      rescanning: false,
      selected: false,
      isSelectionMode: false,
      showCoverBg: false
    }
  },
  computed: {
    _audiobook() {
      return this.audiobook || {}
    },
    placeholderUrl() {
      return '/book_placeholder.jpg'
    },
    hasDownload() {
      return !!this._audiobook.download
    },
    downloadedCover() {
      if (!this._audiobook.download) return null
      return this._audiobook.download.cover
    },
    bookCoverSrc() {
      if (this.downloadedCover) return this.downloadedCover
      return this.store.getters['audiobooks/getBookCoverSrc'](this._audiobook, this.placeholderUrl)
    },
    audiobookId() {
      return this._audiobook.id
    },
    hasEbook() {
      return this._audiobook.numEbooks
    },
    hasTracks() {
      return this._audiobook.numTracks
    },
    book() {
      return this._audiobook.book || {}
    },
    hasCover() {
      return !!this.book.cover
    },
    squareAspectRatio() {
      return this.bookCoverAspectRatio === 1
    },
    sizeMultiplier() {
      var baseSize = this.squareAspectRatio ? 160 : 100
      return this.width / baseSize
    },
    title() {
      return this.book.title || ''
    },
    author() {
      return this.book.author
    },
    authorFL() {
      return this.book.authorFL || this.author
    },
    authorLF() {
      return this.book.authorLF || this.author
    },
    volumeNumber() {
      return this.book.volumeNumber || null
    },
    userProgress() {
      return this.store.getters['user/getUserAudiobook'](this.audiobookId)
    },
    userProgressPercent() {
      return this.userProgress ? this.userProgress.progress || 0 : 0
    },
    userIsRead() {
      return this.userProgress ? !!this.userProgress.isRead : false
    },
    showError() {
      return this.hasMissingParts || this.hasInvalidParts || this.isMissing || this.isInvalid
    },
    isStreaming() {
      return this.store.getters['getAudiobookIdStreaming'] === this.audiobookId
    },
    isMissing() {
      return this._audiobook.isMissing
    },
    isInvalid() {
      return this._audiobook.isInvalid
    },
    hasMissingParts() {
      return this._audiobook.hasMissingParts
    },
    hasInvalidParts() {
      return this._audiobook.hasInvalidParts
    },
    errorText() {
      if (this.isMissing) return 'Audiobook directory is missing!'
      else if (this.isInvalid) return 'Audiobook has no audio tracks & ebook'
      var txt = ''
      if (this.hasMissingParts) {
        txt = `${this.hasMissingParts} missing parts.`
      }
      if (this.hasInvalidParts) {
        if (this.hasMissingParts) txt += ' '
        txt += `${this.hasInvalidParts} invalid parts.`
      }
      return txt || 'Unknown Error'
    },
    store() {
      return this.$store || this.$nuxt.$store
    },
    userCanUpdate() {
      return this.store.getters['user/getUserCanUpdate']
    },
    userCanDelete() {
      return this.store.getters['user/getUserCanDelete']
    },
    userCanDownload() {
      return this.store.getters['user/getUserCanDownload']
    },
    userIsRoot() {
      return this.store.getters['user/getIsRoot']
    },
    _socket() {
      return this.$root.socket || this.$nuxt.$root.socket
    },
    titleFontSize() {
      return 0.75 * this.sizeMultiplier
    },
    authorFontSize() {
      return 0.6 * this.sizeMultiplier
    },
    placeholderCoverPadding() {
      return 0.8 * this.sizeMultiplier
    },
    authorBottom() {
      return 0.75 * this.sizeMultiplier
    },
    titleCleaned() {
      if (!this.title) return ''
      if (this.title.length > 60) {
        return this.title.slice(0, 57) + '...'
      }
      return this.title
    },
    authorCleaned() {
      if (!this.authorFL) return ''
      if (this.authorFL.length > 30) {
        return this.authorFL.slice(0, 27) + '...'
      }
      return this.authorFL
    }
  },
  methods: {
    setSelectionMode(val) {
      this.isSelectionMode = val
      if (!val) this.selected = false
    },
    setEntity(audiobook) {
      this.audiobook = audiobook
    },
    clickCard(e) {
      if (this.isSelectionMode) {
        e.stopPropagation()
        e.preventDefault()
        this.selectBtnClick()
      } else {
        var router = this.$router || this.$nuxt.$router
        if (router) router.push(`/audiobook/${this.audiobookId}`)
      }
    },
    selectBtnClick() {
      this.selected = !this.selected
      this.$emit('select', this.audiobook)
    },
    destroy() {
      // destroy the vue listeners, etc
      this.$destroy()

      // remove the element from the DOM
      if (this.$el && this.$el.parentNode) {
        this.$el.parentNode.removeChild(this.$el)
      } else if (this.$el && this.$el.remove) {
        this.$el.remove()
      }
    },
    setCoverBg() {
      if (this.$refs.coverBg) {
        this.$refs.coverBg.style.backgroundImage = `url("${this.bookCoverSrc}")`
      }
    },
    imageLoaded() {
      this.imageReady = true

      if (this.$refs.cover && this.bookCoverSrc !== this.placeholderUrl) {
        var { naturalWidth, naturalHeight } = this.$refs.cover
        var aspectRatio = naturalHeight / naturalWidth
        var arDiff = Math.abs(aspectRatio - this.bookCoverAspectRatio)

        // If image aspect ratio is <= 1.45 or >= 1.75 then use cover bg, otherwise stretch to fit
        if (arDiff > 0.15) {
          this.showCoverBg = true
          this.$nextTick(this.setCoverBg)
        } else {
          this.showCoverBg = false
        }
      }
    }
  },
  mounted() {
    if (this.bookMount) {
      this.setEntity(this.bookMount)
    }
  }
}
</script>
