<template>
  <div ref="card" :id="`book-card-${index}`" :style="{ minWidth: width + 'px', maxWidth: width + 'px', height: height + 'px' }" class="rounded-sm z-10 cursor-pointer py-1" @click="clickCard">
    <div class="h-full flex relative">
      <div class="list-card-cover relative">
        <!-- When cover image does not fill -->
        <div v-show="showCoverBg" class="absolute top-0 left-0 w-full h-full overflow-hidden rounded-sm bg-primary">
          <div class="absolute cover-bg" ref="coverBg" />
        </div>

        <div class="w-full h-full absolute top-0 left-0">
          <img v-show="libraryItem" ref="cover" :src="bookCoverSrc" class="w-full h-full transition-opacity duration-300" :class="showCoverBg ? 'object-contain' : 'object-fill'" @load="imageLoaded" :style="{ opacity: imageReady ? 1 : 0 }" />
        </div>

        <!-- No progress shown for collapsed series or podcasts in library -->
        <div v-if="!isPodcast && !collapsedSeries" class="absolute bottom-0 left-0 h-1 shadow-sm max-w-full z-10 rounded-b" :class="itemIsFinished ? 'bg-success' : 'bg-yellow-400'" :style="{ width: coverWidth * userProgressPercent + 'px' }"></div>
      </div>
      <div class="flex-grow pl-2" :class="showPlayButton ? 'pr-12' : 'pr-2'">
        <p class="whitespace-normal line-clamp-2" :style="{ fontSize: 0.8 * sizeMultiplier + 'rem' }">
          <span v-if="seriesSequence">#{{ seriesSequence }}&nbsp;</span>{{ displayTitle }}
        </p>
        <p class="truncate text-fg-muted" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">{{ displayAuthor }}</p>
        <p v-if="displaySortLine" class="truncate text-fg-muted" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">{{ displaySortLine }}</p>
        <p v-if="duration" class="truncate text-fg-muted" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">{{ $elapsedPretty(duration) }}</p>

        <p v-if="numEpisodesIncomplete" class="truncate text-fg-muted" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">
          {{ $getString('LabelNumEpisodesIncomplete', [numEpisodes, numEpisodesIncomplete]) }}
        </p>
        <p v-else-if="numEpisodes" class="truncate text-fg-muted" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">
          {{ $getString('LabelNumEpisodes', [numEpisodes]) }}
        </p>
      </div>
      <div v-if="showPlayButton" class="absolute top-0 bottom-0 right-0 h-full flex items-center justify-center z-20 pr-1">
        <button type="button" class="relative rounded-full bg-fg-muted/50" :class="{ 'p-2': !playerIsStartingForThisMedia }" @click.stop.prevent="play">
          <span v-if="!playerIsStartingForThisMedia" class="material-symbols text-2xl fill text-white">{{ playerIsPlaying ? 'pause' : 'play_arrow' }}</span>
          <div v-else class="p-2 text-fg w-10 h-10 flex items-center justify-center bg-fg-muted/80 rounded-full overflow-hidden">
            <svg class="animate-spin" style="width: 24px; height: 24px" viewBox="0 0 24 24">
              <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
            </svg>
          </div>
        </button>
      </div>

      <div v-if="localLibraryItem || isLocal" class="absolute top-0 right-0 z-20" :style="{ top: 0.375 * sizeMultiplier + 'rem', right: 0.375 * sizeMultiplier + 'rem', padding: `${0.1 * sizeMultiplier}rem ${0.25 * sizeMultiplier}rem` }">
        <span class="material-symbols text-2xl text-success">download_done</span>
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
    showSequence: Boolean,
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
    },
    bookCoverAspectRatio() {
      this.setCSSProperties()
    }
  },
  computed: {
    _libraryItem() {
      return this.libraryItem || {}
    },
    isLocal() {
      return !!this._libraryItem.isLocal
    },
    media() {
      return this._libraryItem.media || {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
    mediaType() {
      return this._libraryItem.mediaType
    },
    duration() {
      return this.media.duration || null
    },
    isPodcast() {
      return this.mediaType === 'podcast'
    },
    numEpisodes() {
      if (this.isLocal && this.isPodcast && this.media.episodes) return this.media.episodes.length
      return this.media.numEpisodes
    },
    numEpisodesIncomplete() {
      if (this.isLocal) return 0
      return this._libraryItem.numEpisodesIncomplete || 0
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
    localLibraryItemId() {
      return this.localLibraryItem?.id
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
      return Math.min(1, this.width / 364)
    },
    title() {
      return this.mediaMetadata.title || ''
    },
    playIconFontSize() {
      return Math.max(2, 3 * this.sizeMultiplier)
    },
    author() {
      if (this.isPodcast) return this.mediaMetadata.author || 'Unknown'
      return this.mediaMetadata.authorName || 'Unknown'
    },
    authorLF() {
      return this.mediaMetadata.authorNameLF || 'Unknown'
    },
    series() {
      // Only included when filtering by series or collapse series
      return this.mediaMetadata.series
    },
    seriesSequence() {
      return this.series?.sequence || null
    },
    collapsedSeries() {
      // Only added to item object when collapseSeries is enabled
      return this._libraryItem.collapsedSeries
    },
    booksInSeries() {
      // Only added to item object when collapseSeries is enabled
      return this.collapsedSeries?.numBooks || 0
    },
    displayTitle() {
      const ignorePrefix = this.orderBy === 'media.metadata.title' && this.sortingIgnorePrefix
      if (this.collapsedSeries) return ignorePrefix ? this.collapsedSeries.nameIgnorePrefix : this.collapsedSeries.name
      return ignorePrefix ? this.mediaMetadata.titleIgnorePrefix : this.title
    },
    displayAuthor() {
      if (this.isPodcast) return this.author
      if (this.collapsedSeries) return `${this.booksInSeries} books in series`
      if (this.orderBy === 'media.metadata.authorNameLF') return this.authorLF
      return this.author
    },
    displaySortLine() {
      if (this.collapsedSeries) return null
      if (this.orderBy === 'mtimeMs') return 'Modified ' + this.$formatDate(this._libraryItem.mtimeMs, this.dateFormat)
      if (this.orderBy === 'birthtimeMs') return 'Born ' + this.$formatDate(this._libraryItem.birthtimeMs, this.dateFormat)
      if (this.orderBy === 'addedAt') return this.$getString('LabelAddedDate', [this.$formatDate(this._libraryItem.addedAt, this.dateFormat)])
      if (this.orderBy === 'size') return 'Size: ' + this.$bytesPretty(this._libraryItem.size)
      return null
    },
    userProgress() {
      return this.store.getters['user/getUserMediaProgress'](this.libraryItemId)
    },
    userProgressPercent() {
      return this.userProgress?.progress || 0
    },
    itemIsFinished() {
      return !!this.userProgress?.isFinished
    },
    showError() {
      return this.isMissing || this.isInvalid
    },
    isStreaming() {
      return this.isPlaying && !this.store.getters['getIsCurrentSessionLocal']
    },
    isPlaying() {
      if (this.localLibraryItemId && this.store.getters['getIsMediaStreaming'](this.localLibraryItemId)) return true
      return this.store.getters['getIsMediaStreaming'](this.libraryItemId)
    },
    playerIsPlaying() {
      return this.store.state.playerIsPlaying && (this.isStreaming || this.isPlaying)
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.store.state.playerIsStartingPlayback
    },
    playerIsStartingForThisMedia() {
      const mediaId = this.store.state.playerStartingPlaybackMediaId
      return mediaId === this.libraryItemId
    },
    isCasting() {
      return this.store.state.isCasting
    },
    showReadButton() {
      return !this.isSelectionMode && !this.showPlayButton && this.hasEbook
    },
    showPlayButton() {
      return !this.isSelectionMode && !this.isMissing && !this.isInvalid && this.numTracks && !this.isPodcast
    },
    showSmallEBookIcon() {
      return !this.isSelectionMode && this.hasEbook
    },
    isMissing() {
      return this._libraryItem.isMissing
    },
    isInvalid() {
      return this._libraryItem.isInvalid
    },
    store() {
      return this.$store || this.$nuxt.$store
    },
    dateFormat() {
      return this.store.state.deviceData?.deviceSettings?.dateFormat || 'MM/dd/yyyy'
    },
    coverWidth() {
      return 80 / this.bookCoverAspectRatio
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
          if (this.collapsedSeries) router.push(`/bookshelf/series/${this.collapsedSeries.id}`)
          else router.push(`/item/${this.libraryItemId}`)
        }
      }
    },
    editClick() {
      this.$emit('edit', this.libraryItem)
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
    async play() {
      if (this.playerIsStartingPlayback) return

      const hapticsImpact = this.$hapticsImpact || this.$nuxt.$hapticsImpact
      if (hapticsImpact) {
        await hapticsImpact()
      }

      const eventBus = this.$eventBus || this.$nuxt.$eventBus

      if (this.playerIsPlaying) {
        eventBus.$emit('pause-item')
      } else {
        // Audiobook
        let libraryItemId = this.libraryItemId

        // When casting use server library item
        if (this.localLibraryItem && !this.isCasting) {
          libraryItemId = this.localLibraryItem.id
        } else if (this.hasLocal) {
          libraryItemId = this.localLibraryItem.id
        }

        this.store.commit('setPlayerIsStartingPlayback', this.libraryItemId)
        eventBus.$emit('play-item', { libraryItemId, serverLibraryItemId: this.libraryItemId })
      }
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
        const { naturalWidth, naturalHeight } = this.$refs.cover
        const aspectRatio = naturalHeight / naturalWidth
        const arDiff = Math.abs(aspectRatio - this.bookCoverAspectRatio)

        // If image aspect ratio is <= 1.45 or >= 1.75 then use cover bg, otherwise stretch to fit
        if (arDiff > 0.15) {
          this.showCoverBg = true
          this.$nextTick(this.setCoverBg)
        } else {
          this.showCoverBg = false
        }
      }
    },
    setCSSProperties() {
      document.documentElement.style.setProperty('--list-card-cover-width', this.coverWidth + 'px')
    }
  },
  mounted() {
    this.setCSSProperties()
    if (this.bookMount) {
      this.setEntity(this.bookMount)

      if (this.bookMount.localLibraryItem) {
        this.setLocalLibraryItem(this.bookMount.localLibraryItem)
      }
    }
  }
}
</script>

<style>
:root {
  --list-card-cover-width: 80px;
}

.list-card-cover {
  height: 80px;
  max-height: 80px;
  width: var(--list-card-cover-width);
  min-width: var(--list-card-cover-width);
  max-width: var(--list-card-cover-width);
}
</style>
