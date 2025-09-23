<template>
  <!-- Loading state -->
  <div
    v-if="isLoading"
    ref="card"
    :id="`book-card-${index}`"
    :style="{
      minWidth: width + 'px',
      maxWidth: width + 'px',
      height: height + 'px',
      animationDelay: animationDelay + 'ms'
    }"
    :class="['material-3-list-card', 'rounded-2xl', 'z-10', 'py-1', 'px-2', 'mx-0', 'bg-surface-container', 'shadow-elevation-1', 'transition-all', 'duration-300', 'ease-expressive', 'loading-item', animationFromTop ? 'from-top' : 'from-bottom']"
  >
    <div class="h-full flex items-center relative">
      <!-- Loading cover placeholder -->
      <div class="list-card-cover relative">
        <div class="w-full h-full bg-surface-variant animate-pulse" :class="squareAspectRatio ? 'rounded-lg' : 'rounded-xl'"></div>
      </div>

      <!-- Loading content placeholder -->
      <div class="flex-grow pl-4 pr-4">
        <div class="space-y-2">
          <!-- Title placeholder -->
          <div class="h-4 bg-surface-variant animate-pulse rounded-md w-3/4"></div>
          <!-- Author placeholder -->
          <div class="h-3 bg-surface-variant animate-pulse rounded-md w-1/2"></div>
          <!-- Duration placeholder -->
          <div class="h-3 bg-surface-variant animate-pulse rounded-md w-1/3"></div>
        </div>
      </div>

      <!-- Play button placeholder -->
      <div class="absolute top-2 right-4 z-20">
        <div class="w-12 h-12 bg-surface-variant animate-pulse rounded-full"></div>
      </div>
    </div>
  </div>

  <!-- Actual content -->
  <div
    v-else
    ref="card"
    :id="`book-card-${index}`"
    :style="{
      minWidth: width + 'px',
      maxWidth: width + 'px',
      height: height + 'px',
      animationDelay: animationDelay + 'ms'
    }"
    :class="['material-3-list-card', 'rounded-2xl', 'z-10', 'cursor-pointer', 'py-1', 'px-2', 'mx-0', 'bg-surface-container', 'shadow-elevation-1', 'hover:shadow-elevation-2', 'transition-all', 'duration-300', 'ease-expressive', 'loaded-item', animationFromTop ? 'from-top' : 'from-bottom']"
    @click="clickCard"
  >
    <div class="h-full flex items-center relative">
      <div class="list-card-cover relative">
        <!-- When cover image does not fill -->
        <div v-show="showCoverBg" class="absolute top-0 left-0 w-full h-full overflow-hidden bg-primary" :class="squareAspectRatio ? 'rounded-lg' : 'rounded-xl'">
          <div class="absolute cover-bg" ref="coverBg" />
        </div>

        <div class="w-full h-full absolute top-0 left-0">
          <img v-show="libraryItem && !isMaterialSymbolPlaceholder" ref="cover" :src="bookCoverSrc" class="w-full h-full transition-opacity duration-300" :class="[showCoverBg ? 'object-contain' : 'object-fill', squareAspectRatio ? 'rounded-lg' : 'rounded-xl']" @load="imageLoaded" :style="{ opacity: imageReady ? 1 : 0 }" />

          <!-- Material Symbol placeholder -->
          <div v-if="isMaterialSymbolPlaceholder" class="w-full h-full flex items-center justify-center bg-surface-container" :class="squareAspectRatio ? 'rounded-lg' : 'rounded-xl'">
            <span class="material-symbols text-4xl text-on-surface-variant">book</span>
          </div>
        </div>

        <!-- Enhanced progress indicator (only for playable items) -->
        <div v-if="isLibraryItem && !isPodcast && !collapsedSeries" class="absolute bottom-0 left-0 h-1.5 shadow-elevation-2 max-w-full z-10" :class="[itemIsFinished ? 'bg-tertiary' : 'bg-primary', squareAspectRatio ? 'rounded-bl-lg rounded-br-lg' : 'rounded-bl-xl rounded-br-xl']" :style="{ width: coverWidth * userProgressPercent + 'px' }"></div>
      </div>
      <div class="flex-grow pl-4" :class="showPlayButton ? (localLibraryItem || isLocal ? 'pr-28' : 'pr-20') : 'pr-4'">
        <p class="whitespace-normal line-clamp-2 text-on-surface text-body-medium font-medium" :style="{ fontSize: 0.8 * sizeMultiplier + 'rem' }">
          <span v-if="seriesSequence">#{{ seriesSequence }}&nbsp;</span>{{ displayTitle }}
        </p>
        <p class="truncate text-on-surface-variant text-body-small" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">{{ displayAuthor }}</p>
        <p v-if="displaySortLine" class="truncate text-on-surface-variant text-body-small" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">{{ displaySortLine }}</p>
        <p v-if="duration" class="truncate text-on-surface-variant text-body-small" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">{{ $elapsedPretty(duration) }}</p>

        <p v-if="numEpisodesIncomplete" class="truncate text-on-surface-variant text-body-small" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">
          {{ $getString('LabelNumEpisodesIncomplete', [numEpisodes, numEpisodesIncomplete]) }}
        </p>
        <p v-else-if="numEpisodes" class="truncate text-on-surface-variant text-body-small" :style="{ fontSize: 0.7 * sizeMultiplier + 'rem' }">
          {{ $getString('LabelNumEpisodes', [numEpisodes]) }}
        </p>
      </div>

      <!-- Icon stack area - positioned to the left, stacking downward from top -->
      <div v-if="localLibraryItem || isLocal" class="absolute top-2 right-20 z-20">
        <div class="bg-success-container shadow-elevation-2 rounded-full p-1.5 border border-outline-variant border-opacity-30 w-6 h-6 flex items-center justify-center">
          <span class="material-symbols text-xs text-on-success-container">download_done</span>
        </div>
      </div>

      <!-- Add more icons here in the future, each with top-10, top-18, etc. for stacking -->

      <!-- Play button - positioned to the right of icon stack -->
      <div v-if="showPlayButton" class="absolute top-2 right-4 flex items-center justify-center z-20">
        <button type="button" class="material-3-play-button rounded-full transition-all duration-200 ease-expressive shadow-elevation-2 hover:shadow-elevation-4" :class="{ 'w-12 h-12 bg-primary': !playerIsStartingForThisMedia, 'w-12 h-12 bg-surface-variant': playerIsStartingForThisMedia }" @click.stop.prevent="play">
          <span v-if="!playerIsStartingForThisMedia" class="material-symbols text-2xl text-on-primary">{{ playerIsPlaying ? 'pause' : 'play_arrow' }}</span>
          <div v-else class="flex items-center justify-center">
            <svg class="animate-spin w-6 h-6 text-on-surface-variant" viewBox="0 0 24 24">
              <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
            </svg>
          </div>
        </button>
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
      localLibraryItem: null,
      isLoading: true,
      animationDelay: 0,
      // true when the item should animate as if entering from the top (user scrolled up)
      animationFromTop: false,
      _io: null
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
    // Entity type detection
    entityType() {
      // Check parent page context first for more accurate detection
      const parentEntityName = this.$parent?.entityName || this.$parent?.$parent?.entityName


      if (parentEntityName === 'playlists') return 'playlist'
      if (parentEntityName === 'series') return 'series'
      if (parentEntityName === 'collections') return 'collection'
      if (parentEntityName === 'authors') return 'author'

      // Fallback to data structure detection
      if (this._libraryItem.books) return 'series'

      // Distinguish between collections and playlists by checking item structure
      if (this._libraryItem.items) {
        // Playlists have items with libraryItemId and libraryItem properties
        // Collections have items that are directly library items (books property exists)
        const firstItem = this._libraryItem.items[0]
        if (firstItem && firstItem.libraryItemId && firstItem.libraryItem) {
          return 'playlist'
        } else {
          return 'collection'
        }
      }

      if (this._libraryItem.name && this._libraryItem.imagePath !== undefined) return 'author'
      return 'libraryItem'
    },
    isSeriesEntity() {
      return this.entityType === 'series'
    },
    isCollectionEntity() {
      return this.entityType === 'collection'
    },
    isPlaylistEntity() {
      return this.entityType === 'playlist'
    },
    isAuthorEntity() {
      return this.entityType === 'author'
    },
    isLibraryItem() {
      return this.entityType === 'libraryItem'
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
      // Material 3 book icon as SVG data URL
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor"><path d="M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 4h5v8l-2.5-1.5L6 12V4z"/></svg>`;
      return `data:image/svg+xml;base64,${btoa(svg)}`;
    },
    bookCoverSrc() {
      if (this.isLocal) {
        if (this.libraryItem.coverContentUrl) return Capacitor.convertFileSrc(this.libraryItem.coverContentUrl)
        return this.placeholderUrl
      }

      // Handle different entity types
      if (this.isAuthorEntity) {
        return this.store.getters['globals/getAuthorCoverSrc'](this._libraryItem, this.placeholderUrl)
      }
      if (this.isSeriesEntity) {
        // Series uses the first book's cover
        const firstBook = this._libraryItem.books?.[0]
        if (firstBook) {
          return this.store.getters['globals/getLibraryItemCoverSrc'](firstBook, this.placeholderUrl)
        }
      }
      if (this.isCollectionEntity) {
        // Collections use the first book's cover
        const firstItem = this._libraryItem.books?.[0]
        if (firstItem) {
          return this.store.getters['globals/getLibraryItemCoverSrc'](firstItem, this.placeholderUrl)
        }
      }
      if (this.isPlaylistEntity) {
        // Playlists use the first item's library item cover
        const firstPlaylistItem = this._libraryItem.items?.[0]
        if (firstPlaylistItem?.libraryItem) {
          return this.store.getters['globals/getLibraryItemCoverSrc'](firstPlaylistItem.libraryItem, this.placeholderUrl)
        }
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
      if (this.isSeriesEntity) return this._libraryItem.name || 'Untitled Series'
      if (this.isCollectionEntity) return this._libraryItem.name || 'Untitled Collection'
      if (this.isPlaylistEntity) return this._libraryItem.name || 'Untitled Playlist'
      if (this.isAuthorEntity) return this._libraryItem.name || 'Unknown Author'

      const ignorePrefix = this.orderBy === 'media.metadata.title' && this.sortingIgnorePrefix
      if (this.collapsedSeries) return ignorePrefix ? this.collapsedSeries.nameIgnorePrefix : this.collapsedSeries.name
      return ignorePrefix ? this.mediaMetadata.titleIgnorePrefix : this.title
    },
    displayAuthor() {
      if (this.isSeriesEntity) {
        const bookCount = this._libraryItem.books ? this._libraryItem.books.length : 0
        return `${bookCount} ${bookCount === 1 ? 'book' : 'books'}`
      }
      if (this.isCollectionEntity) {
        const itemCount = this._libraryItem.books ? this._libraryItem.books.length : 0
        return `${itemCount} ${itemCount === 1 ? 'item' : 'items'}`
      }
      if (this.isPlaylistEntity) {
        const itemCount = this._libraryItem.items ? this._libraryItem.items.length : 0
        return `${itemCount} ${itemCount === 1 ? 'item' : 'items'}`
      }
      if (this.isAuthorEntity) {
        const bookCount = this._libraryItem.numBooks || 0
        return `${bookCount} ${bookCount === 1 ? 'book' : 'books'}`
      }

      if (this.isPodcast) return this.author
      if (this.collapsedSeries) return `${this.booksInSeries} books in series`
      if (this.orderBy === 'media.metadata.authorNameLF') return this.authorLF
      return this.author
    },
    displaySortLine() {
      if (this.collapsedSeries) return null
      if (this.orderBy === 'mtimeMs') return 'Modified ' + this.$formatDate(this._libraryItem.mtimeMs)
      if (this.orderBy === 'birthtimeMs') return 'Born ' + this.$formatDate(this._libraryItem.birthtimeMs)
      if (this.orderBy === 'addedAt') return this.$getString('LabelAddedDate', [this.$formatDate(this._libraryItem.addedAt)])
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
      // Only show play button for actual library items (books/audiobooks), not series/collections/playlists/authors
      return this.isLibraryItem && !this.isSelectionMode && !this.isMissing && !this.isInvalid && this.numTracks && !this.isPodcast
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
    coverWidth() {
      return 80 / this.bookCoverAspectRatio
    },
    isMaterialSymbolPlaceholder() {
      return this.bookCoverSrc === 'material-symbol:book'
    }
  },
  methods: {
    setSelectionMode(val) {
      this.isSelectionMode = val
      if (!val) this.selected = false
    },
    setEntity(libraryItem) {
      this.libraryItem = libraryItem
      // If entity is set after initial loading, stop loading state
      if (this.isLoading && libraryItem) {
        this.isLoading = false
      }
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
          if (this.collapsedSeries) {
            router.push(`/bookshelf/series/${this.collapsedSeries.id}`)
          } else if (this.isSeriesEntity) {
            router.push(`/bookshelf/series/${this.libraryItemId}`)
          } else if (this.isCollectionEntity) {
            router.push(`/collection/${this.libraryItemId}`)
          } else if (this.isPlaylistEntity) {
            router.push(`/playlist/${this.libraryItemId}`)
          } else if (this.isAuthorEntity) {
            // Authors don't have detail pages in mobile app, so do nothing for now
            console.log('Author clicked:', this._libraryItem.name)
          } else {
            router.push(`/item/${this.libraryItemId}`)
          }
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
    // Use IntersectionObserver to load items as they enter the viewport
    // Also track global scroll direction so items animate from the correct side
    if (typeof window !== 'undefined') {
      // Install a single global scroll listener to track direction
      if (!window.__abs_scrollListenerAdded) {
        window.__abs_lastY = window.scrollY || window.pageYOffset || 0
        window.__abs_scrollDir = 'down'
        window.addEventListener(
          'scroll',
          () => {
            const y = window.scrollY || window.pageYOffset || 0
            window.__abs_scrollDir = y > window.__abs_lastY ? 'down' : y < window.__abs_lastY ? 'up' : window.__abs_scrollDir
            window.__abs_lastY = y
          },
          { passive: true }
        )
        window.__abs_scrollListenerAdded = true
      }

      // Observer options: preload a little before items are fully visible
      const options = { root: null, rootMargin: '200px 0px', threshold: 0.05 }
      this._io = new IntersectionObserver((entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue

          // Determine animation direction from the last known scroll direction
          this.animationFromTop = window.__abs_scrollDir === 'up'

          // Small, local stagger so nearby items animate in sequence but quickly
          const stagger = Math.min(5, this.index % 6) * 28 // ~0-140ms

          // Short base delay so items appear quickly
          const base = 20

          setTimeout(() => {
            this.isLoading = false

            if (this.bookMount) {
              this.setEntity(this.bookMount)

              if (this.bookMount.localLibraryItem) {
                this.setLocalLibraryItem(this.bookMount.localLibraryItem)
              }
            }

            // once loaded, stop observing
            if (this._io) {
              this._io.unobserve(this.$refs.card)
              // disconnect later to avoid interrupting other observers
            }
          }, base + stagger)
        }
      }, options)

      // Start observing the root element if present
      this.$nextTick(() => {
        if (this.$refs.card && this._io) {
          this._io.observe(this.$refs.card)
        }
      })
    } else {
      // Fallback for SSR or environments without window
      this.animationDelay = (this.index % 6) * 28
      setTimeout(() => {
        this.isLoading = false

        if (this.bookMount) {
          this.setEntity(this.bookMount)

          if (this.bookMount.localLibraryItem) {
            this.setLocalLibraryItem(this.bookMount.localLibraryItem)
          }
        }
      }, 120 + this.animationDelay)
    }
  },

  beforeDestroy() {
    try {
      if (this._io) {
        this._io.disconnect()
        this._io = null
      }
    } catch (e) {
      // ignore
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
  border-radius: 12px; /* Add consistent rounded corners */
  overflow: hidden; /* Ensure images respect border radius */
}

/* Material 3 List Card Styles */
.material-3-list-card {
  transition: box-shadow 300ms cubic-bezier(0.2, 0, 0, 1), transform 200ms cubic-bezier(0.2, 0, 0, 1);
  position: relative;
  margin-bottom: 8px; /* Add spacing between list items */
}

.material-3-list-card::before {
  content: '';
  position: absolute;
  border-radius: inherit;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-color: transparent;
  transition: background-color 200ms cubic-bezier(0.2, 0, 0, 1);
  pointer-events: none;
  z-index: 1;
}

.material-3-list-card:hover {
  transform: translateY(-1px);
}

.material-3-list-card:hover::before {
  background-color: rgba(var(--md-sys-color-on-surface), 0.08);
}

.material-3-list-card:active {
  transform: translateY(0px);
}

.material-3-list-card:active::before {
  background-color: rgba(var(--md-sys-color-on-surface), 0.12);
}

/* Ensure content stays above state layer */
.material-3-list-card > * {
  position: relative;
  z-index: 2;
}

/* Material 3 Play Button */
.material-3-play-button {
  transition: all 200ms cubic-bezier(0.2, 0, 0, 1);
  display: flex;
  align-items: center;
  justify-content: center;
}

.material-3-play-button:hover {
  transform: scale(1.05);
}

.material-3-play-button:active {
  transform: scale(0.95);
}

/* Expressive easing definition */
.ease-expressive {
  transition-timing-function: cubic-bezier(0.2, 0, 0, 1);
}

/* Line clamp utility */
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* Loading animations */
.loading-item {
  opacity: 0;
  /* initial state is handled by direction-specific keyframes below */
}

.loaded-item {
  opacity: 0;
  /* final-state animation handled by direction-specific keyframes below */
}

/* Slide in from bottom */
@keyframes slideInFromBottom {
  0% {
    opacity: 0;
    transform: translateY(18px) scale(0.97);
  }
  60% {
    opacity: 0.9;
    transform: translateY(-2px) scale(1.01);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

/* Slide in from top */
@keyframes slideInFromTop {
  0% {
    opacity: 0;
    transform: translateY(-18px) scale(0.97);
  }
  60% {
    opacity: 0.9;
    transform: translateY(2px) scale(1.01);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

/* When item is loading and direction is bottom -> use slideInFromBottom */
.loading-item.from-bottom,
.loaded-item.from-bottom {
  animation: slideInFromBottom 320ms cubic-bezier(0.05, 0.7, 0.1, 1) forwards;
}

/* When item is loading and direction is top -> use slideInFromTop */
.loading-item.from-top,
.loaded-item.from-top {
  animation: slideInFromTop 320ms cubic-bezier(0.05, 0.7, 0.1, 1) forwards;
}
</style>
