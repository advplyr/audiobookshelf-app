<template>
  <div ref="card" :id="`book-card-${index}`" :style="{ minWidth: width + 'px', maxWidth: width + 'px', height: height + 'px' }" class="rounded-sm z-10 cursor-pointer py-1" @click="clickCard">
    <div class="h-full flex">
      <div class="w-20 h-20 relative" style="min-width: 80px; max-width: 80px">
        <!-- When cover image does not fill -->
        <div v-show="showCoverBg" class="absolute top-0 left-0 w-full h-full overflow-hidden rounded-sm bg-primary">
          <div class="absolute cover-bg" ref="coverBg" />
        </div>

        <div class="w-full h-full absolute top-0 left-0">
          <img v-show="libraryItem" ref="cover" :src="bookCoverSrc" class="w-full h-full transition-opacity duration-300" :class="showCoverBg ? 'object-contain' : 'object-fill'" @load="imageLoaded" :style="{ opacity: imageReady ? 1 : 0 }" />
        </div>

        <!-- No progress shown for collapsed series in library -->
        <div class="absolute bottom-0 left-0 h-1 shadow-sm max-w-full z-10 rounded-b" :class="itemIsFinished ? 'bg-success' : 'bg-yellow-400'" :style="{ width: width * userProgressPercent + 'px' }"></div>

        <div v-if="localLibraryItem || isLocal" class="absolute top-0 right-0 z-20" :style="{ top: 0.375 * sizeMultiplier + 'rem', right: 0.375 * sizeMultiplier + 'rem', padding: `${0.1 * sizeMultiplier}rem ${0.25 * sizeMultiplier}rem` }">
          <span class="material-icons text-2xl text-success">{{ isLocalOnly ? 'task' : 'download_done' }}</span>
        </div>
      </div>
      <div class="flex-grow px-2">
        <p class="whitespace-normal" :style="{ fontSize: 0.8 * sizeMultiplier + 'rem' }">
          {{ displayTitle }}
        </p>
        <p class="truncate text-gray-400" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">{{ displayAuthor }}</p>
        <p v-if="displaySortLine" class="truncate text-gray-400" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">{{ displaySortLine }}</p>
      </div>
    </div>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'

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
    bookshelfView: Number,
    bookMount: {
      // Book can be passed as prop or set with setEntity()
      type: Object,
      default: () => null
    },
    orderBy: String,
    filterBy: String,
    sortingIgnorePrefix: Boolean
  },
  data() {
    return {
      isProcessingReadUpdate: false,
      libraryItem: null,
      imageReady: false,
      rescanning: false,
      selected: false,
      isSelectionMode: false,
      showCoverBg: false,
      localLibraryItem: null
    }
  },
  watch: {
    bookMount: {
      handler(newVal) {
        if (newVal) {
          this.libraryItem = newVal
        }
      }
    }
  },
  computed: {
    showExperimentalFeatures() {
      return this.store.state.showExperimentalFeatures
    },
    _libraryItem() {
      return this.libraryItem || {}
    },
    isLocal() {
      return !!this._libraryItem.isLocal
    },
    isLocalOnly() {
      // Local item with no server match
      return this.isLocal && !this._libraryItem.libraryItemId
    },
    media() {
      return this._libraryItem.media || {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
    placeholderUrl() {
      return '/book_placeholder.jpg'
    },
    bookCoverSrc() {
      if (this.isLocal) {
        if (this.libraryItem.coverContentUrl) return Capacitor.convertFileSrc(this.libraryItem.coverContentUrl)
        return this.placeholderUrl
      }
      return this.store.getters['globals/getLibraryItemCoverSrc'](this._libraryItem, this.placeholderUrl)
    },
    libraryItemId() {
      return this._libraryItem.id
    },
    series() {
      return this.mediaMetadata.series
    },
    libraryId() {
      return this._libraryItem.libraryId
    },
    hasEbook() {
      return this.media.ebookFile
    },
    numTracks() {
      return this.media.numTracks
    },
    processingBatch() {
      return this.store.state.processingBatch
    },
    booksInSeries() {
      // Only added to audiobook object when collapseSeries is enabled
      return this._libraryItem.booksInSeries
    },
    hasCover() {
      return !!this.media.coverPath
    },
    squareAspectRatio() {
      return this.bookCoverAspectRatio === 1
    },
    sizeMultiplier() {
      return this.width / 364
    },
    title() {
      return this.mediaMetadata.title || ''
    },
    playIconFontSize() {
      return Math.max(2, 3 * this.sizeMultiplier)
    },
    author() {
      return this.mediaMetadata.authorName || ''
    },
    authorLF() {
      return this.mediaMetadata.authorNameLF || ''
    },
    volumeNumber() {
      return this.mediaMetadata.volumeNumber || null
    },
    displayTitle() {
      if (this.orderBy === 'media.metadata.title' && this.sortingIgnorePrefix && this.title.toLowerCase().startsWith('the ')) {
        return this.title.substr(4) + ', The'
      }
      return this.title
    },
    displayAuthor() {
      if (this.orderBy === 'media.metadata.authorNameLF') return this.authorLF
      return this.author
    },
    displaySortLine() {
      if (this.orderBy === 'mtimeMs') return 'Modified ' + this.$formatDate(this._libraryItem.mtimeMs)
      if (this.orderBy === 'birthtimeMs') return 'Born ' + this.$formatDate(this._libraryItem.birthtimeMs)
      if (this.orderBy === 'addedAt') return 'Added ' + this.$formatDate(this._libraryItem.addedAt)
      if (this.orderBy === 'duration') return 'Duration: ' + this.$elapsedPrettyExtended(this.media.duration, false)
      if (this.orderBy === 'size') return 'Size: ' + this.$bytesPretty(this._libraryItem.size)
      return null
    },
    userProgress() {
      return this.store.getters['user/getUserLibraryItemProgress'](this.libraryItemId)
    },
    userProgressPercent() {
      return this.userProgress ? this.userProgress.progress || 0 : 0
    },
    itemIsFinished() {
      return this.userProgress ? !!this.userProgress.isFinished : false
    },
    showError() {
      return this.hasMissingParts || this.hasInvalidParts || this.isMissing || this.isInvalid
    },
    isStreaming() {
      return this.store.getters['getlibraryItemIdStreaming'] === this.libraryItemId
    },
    showReadButton() {
      return !this.isSelectionMode && this.showExperimentalFeatures && !this.showPlayButton && this.hasEbook
    },
    showPlayButton() {
      return !this.isSelectionMode && !this.isMissing && !this.isInvalid && this.numTracks && !this.isStreaming
    },
    showSmallEBookIcon() {
      return !this.isSelectionMode && this.showExperimentalFeatures && this.hasEbook
    },
    isMissing() {
      return this._libraryItem.isMissing
    },
    isInvalid() {
      return this._libraryItem.isInvalid
    },
    hasMissingParts() {
      return this._libraryItem.hasMissingParts
    },
    hasInvalidParts() {
      return this._libraryItem.hasInvalidParts
    },
    errorText() {
      if (this.isMissing) return 'Item directory is missing!'
      else if (this.isInvalid) return 'Item has no media files'
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
    overlayWrapperClasslist() {
      var classes = []
      if (this.isSelectionMode) classes.push('bg-opacity-60')
      else classes.push('bg-opacity-40')
      if (this.selected) {
        classes.push('border-2 border-yellow-400')
      }
      return classes
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
      if (!this.author) return ''
      if (this.author.length > 30) {
        return this.author.slice(0, 27) + '...'
      }
      return this.author
    },
    isAlternativeBookshelfView() {
      return false
      // var constants = this.$constants || this.$nuxt.$constants
      // return this.bookshelfView === constants.BookshelfView.TITLES
    },
    titleDisplayBottomOffset() {
      if (!this.isAlternativeBookshelfView) return 0
      else if (!this.displaySortLine) return 3 * this.sizeMultiplier
      return 4.25 * this.sizeMultiplier
    }
  },
  methods: {
    setSelectionMode(val) {
      this.isSelectionMode = val
      if (!val) this.selected = false
    },
    setEntity(libraryItem) {
      this.libraryItem = libraryItem
    },
    setLocalLibraryItem(localLibraryItem) {
      // Server books may have a local library item
      this.localLibraryItem = localLibraryItem
    },
    clickCard(e) {
      if (this.isSelectionMode) {
        e.stopPropagation()
        e.preventDefault()
        this.selectBtnClick()
      } else {
        var router = this.$router || this.$nuxt.$router
        if (router) {
          if (this.booksInSeries) router.push(`/library/${this.libraryId}/series/${this.$encode(this.series)}`)
          else router.push(`/item/${this.libraryItemId}`)
        }
      }
    },
    editClick() {
      this.$emit('edit', this.libraryItem)
    },
    toggleFinished() {
      var updatePayload = {
        isFinished: !this.itemIsFinished
      }
      this.isProcessingReadUpdate = true
      var toast = this.$toast || this.$nuxt.$toast
      var axios = this.$axios || this.$nuxt.$axios
      axios
        .$patch(`/api/me/progress/${this.libraryItemId}`, updatePayload)
        .then(() => {
          this.isProcessingReadUpdate = false
          toast.success(`Item marked as ${updatePayload.isFinished ? 'Finished' : 'Not Finished'}`)
        })
        .catch((error) => {
          console.error('Failed', error)
          this.isProcessingReadUpdate = false
          toast.error(`Failed to mark as ${updatePayload.isFinished ? 'Finished' : 'Not Finished'}`)
        })
    },
    rescan() {
      this.rescanning = true
      this.$axios
        .$get(`/api/items/${this.libraryItemId}/scan`)
        .then((data) => {
          this.rescanning = false
          var result = data.result
          if (!result) {
            this.$toast.error(`Re-Scan Failed for "${this.title}"`)
          } else if (result === 'UPDATED') {
            this.$toast.success(`Re-Scan complete item was updated`)
          } else if (result === 'UPTODATE') {
            this.$toast.success(`Re-Scan complete item was up to date`)
          } else if (result === 'REMOVED') {
            this.$toast.error(`Re-Scan complete item was removed`)
          }
        })
        .catch((error) => {
          console.error('Failed to scan library item', error)
          this.$toast.error('Failed to scan library item')
          this.rescanning = false
        })
    },
    showEditModalTracks() {
      // More menu func
      this.store.commit('showEditModalOnTab', { libraryItem: this.libraryItem, tab: 'tracks' })
    },
    showEditModalMatch() {
      // More menu func
      this.store.commit('showEditModalOnTab', { libraryItem: this.libraryItem, tab: 'match' })
    },
    showEditModalDownload() {
      // More menu func
      this.store.commit('showEditModalOnTab', { libraryItem: this.libraryItem, tab: 'download' })
    },
    openCollections() {
      this.store.commit('setSelectedLibraryItem', this.libraryItem)
      this.store.commit('globals/setShowUserCollectionsModal', true)
    },
    clickReadEBook() {
      this.store.commit('showEReader', this.libraryItem)
    },
    selectBtnClick() {
      if (this.processingBatch) return
      this.selected = !this.selected
      this.$emit('select', this.libraryItem)
    },
    play() {
      var eventBus = this.$eventBus || this.$nuxt.$eventBus
      eventBus.$emit('play-item', this.libraryItemId)
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

      if (this.bookMount.localLibraryItem) {
        this.setLocalLibraryItem(this.bookMount.localLibraryItem)
      }
    }
  }
}
</script>
