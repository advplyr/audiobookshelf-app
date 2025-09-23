<template>
  <div id="bookshelf" class="w-full max-w-full h-full bg-surface-dynamic library-scroll-container">
    <!-- Loading skeleton for initial load -->
    <div v-if="!initialized" class="w-full px-4 space-y-2 py-4">
      <div v-for="n in 8" :key="n" class="w-full h-20 bg-surface-container rounded-2xl shadow-elevation-1 animate-pulse loading-skeleton" :style="{ animationDelay: n * 100 + 'ms' }">
        <div class="h-full flex items-center p-2">
          <!-- Cover placeholder -->
          <div class="w-16 h-16 bg-surface-variant rounded-xl animate-pulse"></div>

          <!-- Content placeholder -->
          <div class="flex-grow pl-4 space-y-2">
            <div class="h-4 bg-surface-variant rounded-md w-3/4 animate-pulse"></div>
            <div class="h-3 bg-surface-variant rounded-md w-1/2 animate-pulse"></div>
            <div class="h-3 bg-surface-variant rounded-md w-1/3 animate-pulse"></div>
          </div>

          <!-- Play button placeholder -->
          <div class="w-12 h-12 bg-surface-variant rounded-full animate-pulse"></div>
        </div>
      </div>
    </div>

    <!-- Actual shelves -->
    <template v-for="shelf in totalShelves">
      <div :key="shelf" class="w-full px-4 bg-surface-dynamic shelf-list-view" :id="`shelf-${shelf - 1}`" :style="shelfContainerStyle">
      </div>
    </template>

    <div v-show="!entities.length && initialized" class="w-full py-16 text-center">
      <div v-if="page === 'collections'" class="py-4 text-on-surface text-title-large">{{ $strings.MessageNoCollections }}</div>
      <div v-else class="py-4 text-on-surface text-title-large capitalize">No {{ entityName }}</div>
      <ui-btn v-if="hasFilter" @click="clearFilter" variant="filled">{{ $strings.ButtonClearFilter }}</ui-btn>
    </div>
  </div>
</template>

<script>
import bookshelfCardsHelpers from '@/mixins/bookshelfCardsHelpers'

export default {
  props: {
    page: String,
    seriesId: String
  },
  mixins: [bookshelfCardsHelpers],
  data() {
    return {
      routeFullPath: null,
      entitiesPerShelf: 2,
      bookshelfHeight: 0,
      bookshelfWidth: 0,
      bookshelfMarginLeft: 0,
      shelvesPerPage: 0,
      currentPage: 0,
      booksPerFetch: 20,
      initialized: false,
      currentSFQueryString: null,
      isFetchingEntities: false,
      entities: [],
      totalEntities: 0,
      totalShelves: 0,
      entityComponentRefs: {},
      entityIndexesMounted: [],
      pagesLoaded: {},
      isFirstInit: false,
      pendingReset: false,
      localLibraryItems: []
    }
  },
  watch: {
    seriesId() {
      this.resetEntities()
    }
  },
  computed: {
    user() {
      return this.$store.state.user.user
    },
    isBookEntity() {
      return this.entityName === 'books' || this.entityName === 'series-books'
    },
    shelfDividerHeightIndex() {
      if (this.isBookEntity) return 4
      return 6
    },
    bookshelfListView() {
      return this.$store.state.globals.bookshelfListView
    },
    showBookshelfListView() {
      // Always use list view for all entities - cards only on home page
      return true
    },
    sortingIgnorePrefix() {
      return this.$store.getters['getServerSetting']('sortingIgnorePrefix')
    },
    entityName() {
      return this.page
    },
    hasFilter() {
      if (this.page === 'series' || this.page === 'collections' || this.page === 'playlists') return false
      return this.filterBy !== 'all'
    },
    orderBy() {
      return this.$store.getters['user/getUserSetting']('mobileOrderBy')
    },
    orderDesc() {
      return this.$store.getters['user/getUserSetting']('mobileOrderDesc')
    },
    filterBy() {
      return this.$store.getters['user/getUserSetting']('mobileFilterBy')
    },
    collapseSeries() {
      return this.$store.getters['user/getUserSetting']('collapseSeries')
    },
    collapseBookSeries() {
      return this.$store.getters['user/getUserSetting']('collapseBookSeries')
    },
    isCoverSquareAspectRatio() {
      return this.bookCoverAspectRatio === 1
    },
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    bookWidth() {
      // Simplified since we only need this for home page card sizing now
      // List view doesn't use this for sizing
      const baseWidth = this.isCoverSquareAspectRatio ? 192 : 120
      return baseWidth
    },
    bookHeight() {
      if (this.isCoverSquareAspectRatio || this.entityName === 'playlists') return this.bookWidth
      return this.bookWidth * 1.6
    },
    entityWidth() {
      // Always use list view width since we removed card view
      return this.bookshelfWidth - 32 // Account for px-4 padding (16px each side)
    },
    entityHeight() {
      // Always use list view height since we removed card view
      return 88
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    currentLibraryMediaType() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType']
    },
    shelfHeight() {
      if (this.showBookshelfListView) return this.entityHeight + 6 // Reduced from 8
      if (this.altViewEnabled) {
        var extraTitleSpace = this.isBookEntity ? 60 : 30 // Reduced from 80:40
        return this.entityHeight + extraTitleSpace * this.sizeMultiplier
      }
      return this.entityHeight + 24 // Reduced from 40
    },
    totalEntityCardWidth() {
      if (this.showBookshelfListView) return this.entityWidth

      // Since everything uses list view now, always return standard width

      // Use Material 3 spacing - 16px between cards for absolute positioned items
      return this.entityWidth + 16
    },
    altViewEnabled() {
      return this.$store.getters['getAltViewEnabled']
    },
    sizeMultiplier() {
      const baseSize = this.isCoverSquareAspectRatio ? 192 : 120
      return this.entityWidth / baseSize
    },
    shelfContainerStyle() {
      // Always use list view layout since we removed card view
      return { height: this.shelfHeight + 'px' }
    }
  },
  methods: {
    clearFilter() {
      this.$store.dispatch('user/updateUserSettings', {
        mobileFilterBy: 'all'
      })
    },
    async fetchEntities(page) {
      const startIndex = page * this.booksPerFetch

      this.isFetchingEntities = true

      if (!this.initialized) {
        this.currentSFQueryString = this.buildSearchParams()
      }

      const entityPath = this.entityName === 'books' || this.entityName === 'series-books' ? `items` : this.entityName
      const sfQueryString = this.currentSFQueryString ? this.currentSFQueryString + '&' : ''
      const fullQueryString = `?${sfQueryString}limit=${this.booksPerFetch}&page=${page}&minified=1&include=rssfeed,numEpisodesIncomplete`

      const payload = await this.$nativeHttp.get(`/api/libraries/${this.currentLibraryId}/${entityPath}${fullQueryString}`).catch((error) => {
        console.error('failed to fetch books', error)
        return null
      })

      this.isFetchingEntities = false
      if (this.pendingReset) {
        this.pendingReset = false
        this.resetEntities()
        return
      }
      if (payload && payload.results) {
        console.log('Received payload', payload)
        if (!this.initialized) {
          this.initialized = true
          this.totalEntities = payload.total
          this.totalShelves = Math.ceil(this.totalEntities / this.entitiesPerShelf)
          this.entities = new Array(this.totalEntities)
          this.$eventBus.$emit('bookshelf-total-entities', this.totalEntities)
        } else {
          // Handle filter changes - recalculate total entities and shelves
          const previousTotal = this.totalEntities
          this.totalEntities = payload.total
          this.totalShelves = Math.ceil(this.totalEntities / this.entitiesPerShelf)

          if (previousTotal !== this.totalEntities) {
            const changeRatio = this.totalEntities / Math.max(previousTotal, 1)

            // If the change is significant (more than 3x increase), force a full reset
            if (changeRatio > 3) {
              console.log(`[LazyBookshelf] Significant entity change detected (${changeRatio.toFixed(1)}x) - forcing full reset`)
              this.resetEntities()
              return
            }

            // Clear old entity components to prevent stale data
            this.destroyEntityComponents()
            this.entityIndexesMounted = []
            this.entityComponentRefs = {}

            // Resize entities array and recalculate viewable area
            this.entities = new Array(this.totalEntities)
            this.$eventBus.$emit('bookshelf-total-entities', this.totalEntities)

            // Update the viewable area calculation
            this.initSizeData()

            // Ensure we mount entities for the expanded viewable area
            if (this.totalEntities > previousTotal) {
              // Use $nextTick to ensure DOM has updated with new totalShelves
              this.$nextTick(() => {
                // Force recalculate container dimensions after DOM update
                const bookshelf = document.getElementById('bookshelf')
                const bookshelfWrapper = document.getElementById('bookshelf-wrapper')
                if (bookshelf && bookshelfWrapper) {
                  const { clientWidth } = bookshelf
                  // Use the scroll container viewport height, not the content height
                  const { clientHeight: wrapperHeight } = bookshelfWrapper
                  this.bookshelfHeight = wrapperHeight
                  this.bookshelfWidth = clientWidth
                  console.log(`[LazyBookshelf] Updated dimensions - content: ${clientWidth}x${bookshelf.clientHeight}, viewport: ${clientWidth}x${wrapperHeight}`)

                  // Force recalculate viewport-based values
                  this.shelvesPerPage = Math.ceil(this.bookshelfHeight / this.shelfHeight) + 2
                  const entitiesPerPage = this.shelvesPerPage * this.entitiesPerShelf
                  this.booksPerFetch = Math.ceil(entitiesPerPage / 20) * 20

                  console.log(`[LazyBookshelf] Recalculated viewport - shelvesPerPage: ${this.shelvesPerPage}, booksPerFetch: ${this.booksPerFetch}`)
                }

                const currentScrollTop = window['bookshelf-wrapper']?.scrollTop || 0

                // Ensure we mount entities for the current scroll position with new viewport
                if (currentScrollTop === 0) {
                  const initialLastBookIndex = Math.min(this.totalEntities, this.shelvesPerPage * this.entitiesPerShelf)
                  console.log(`[LazyBookshelf] Mounting initial entities 0-${initialLastBookIndex}`)

                  // Load additional pages if needed for the expanded viewport
                  const lastBookPage = Math.floor(initialLastBookIndex / this.booksPerFetch)
                  for (let page = 0; page <= lastBookPage; page++) {
                    if (!this.pagesLoaded[page]) {
                      console.log(`[LazyBookshelf] Loading additional page ${page} for expanded viewport`)
                      this.loadPage(page)
                    }
                  }

                  this.mountEntites(0, initialLastBookIndex)
                } else {
                  // If not at top, ensure we handle the current scroll position with new viewport
                  this.handleScroll(currentScrollTop)
                }
              })
            }

            console.log(`[LazyBookshelf] Filter changed - entities: ${previousTotal} → ${this.totalEntities}, shelves: ${this.totalShelves}`)
          }
        }

        for (let i = 0; i < payload.results.length; i++) {
          const index = i + startIndex
          this.entities[index] = payload.results[i]
          if (this.entityComponentRefs[index]) {
            this.entityComponentRefs[index].setEntity(this.entities[index])

            if (this.isBookEntity) {
              const localLibraryItem = this.localLibraryItems.find((lli) => lli.libraryItemId == this.entities[index].id)
              if (localLibraryItem) {
                this.entityComponentRefs[index].setLocalLibraryItem(localLibraryItem)
              }
            }
          }
        }
      }
    },
    async loadPage(page) {
      if (!this.currentLibraryId) {
        console.error('[LazyBookshelf] loadPage current library id not set')
        return
      }
      this.pagesLoaded[page] = true
      await this.fetchEntities(page)
    },
    mountEntites(fromIndex, toIndex) {
      for (let i = fromIndex; i < toIndex; i++) {
        if (!this.entityIndexesMounted.includes(i)) {
          this.cardsHelpers.mountEntityCard(i)
        }
      }
    },
    handleScroll(scrollTop) {
      this.currScrollTop = scrollTop
      var firstShelfIndex = Math.floor(scrollTop / this.shelfHeight)
      var lastShelfIndex = Math.ceil((scrollTop + this.bookshelfHeight) / this.shelfHeight)
      lastShelfIndex = Math.min(this.totalShelves - 1, lastShelfIndex)

      var firstBookIndex = firstShelfIndex * this.entitiesPerShelf
      var lastBookIndex = lastShelfIndex * this.entitiesPerShelf + this.entitiesPerShelf
      lastBookIndex = Math.min(this.totalEntities, lastBookIndex)

      var firstBookPage = Math.floor(firstBookIndex / this.booksPerFetch)
      var lastBookPage = Math.floor(lastBookIndex / this.booksPerFetch)
      if (!this.pagesLoaded[firstBookPage]) {
        console.log('Must load next batch', firstBookPage, 'book index', firstBookIndex)
        this.loadPage(firstBookPage)
      }
      if (!this.pagesLoaded[lastBookPage]) {
        console.log('Must load last next batch', lastBookPage, 'book index', lastBookIndex)
        this.loadPage(lastBookPage)
      }

      // Remove entities out of view
      this.entityIndexesMounted = this.entityIndexesMounted.filter((_index) => {
        if (_index < firstBookIndex || _index >= lastBookIndex) {
          var el = document.getElementById(`book-card-${_index}`)
          if (el) el.remove()
          return false
        }
        return true
      })
      this.mountEntites(firstBookIndex, lastBookIndex)
    },
    destroyEntityComponents() {
      for (const key in this.entityComponentRefs) {
        if (this.entityComponentRefs[key] && this.entityComponentRefs[key].destroy) {
          this.entityComponentRefs[key].destroy()
        }
      }
    },
    setDownloads() {
      if (this.entityName === 'books') {
        this.entities = []
        // TOOD: Sort and filter here
        this.totalEntities = this.entities.length
        this.totalShelves = Math.ceil(this.totalEntities / this.entitiesPerShelf)
      } else {
        // TODO: Support offline series and collections
        this.entities = []
        this.totalEntities = 0
        this.totalShelves = 0
      }
      this.$eventBus.$emit('bookshelf-total-entities', this.totalEntities)
    },
    async resetEntities() {
      if (this.isFetchingEntities) {
        this.pendingReset = true
        return
      }
      this.destroyEntityComponents()
      this.entityIndexesMounted = []
      this.entityComponentRefs = {}
      this.pagesLoaded = {}
      this.entities = []
      this.totalShelves = 0
      this.totalEntities = 0
      this.currentPage = 0
      this.initialized = false

      this.initSizeData()
      if (this.user) {
        await this.loadPage(0)
        var lastBookIndex = Math.min(this.totalEntities, this.shelvesPerPage * this.entitiesPerShelf)
        this.mountEntites(0, lastBookIndex)
      } else {
        // Local only
      }
    },
    remountEntities() {
      // Remount when an entity is removed
      for (const key in this.entityComponentRefs) {
        if (this.entityComponentRefs[key]) {
          this.entityComponentRefs[key].destroy()
        }
      }
      this.entityComponentRefs = {}
      this.entityIndexesMounted.forEach((i) => {
        this.cardsHelpers.mountEntityCard(i)
      })
    },
    initSizeData() {
      var bookshelf = document.getElementById('bookshelf')
      var bookshelfWrapper = document.getElementById('bookshelf-wrapper')
      if (!bookshelf || !bookshelfWrapper) {
        console.error('Failed to init size data')
        return
      }
      var entitiesPerShelfBefore = this.entitiesPerShelf

      var { clientWidth } = bookshelf
      // Use the scroll container viewport height, not the content height
      var { clientHeight: wrapperHeight } = bookshelfWrapper
      this.bookshelfHeight = wrapperHeight
      this.bookshelfWidth = clientWidth
      console.log(`[LazyBookshelf] initSizeData - content: ${clientWidth}x${bookshelf.clientHeight}, viewport: ${clientWidth}x${wrapperHeight}`)

      if (this.showBookshelfListView) {
        this.entitiesPerShelf = 1
        this.bookshelfMarginLeft = 0
      } else {
        // Use responsive column calculation
        const availableWidth = this.bookshelfWidth - 32 // Account for padding
        this.entitiesPerShelf = this.getTargetColumnsForWidth(availableWidth)

        // Center the grid if there's extra space
        const usedWidth = this.entitiesPerShelf * this.totalEntityCardWidth - 16 // Remove last gap
        this.bookshelfMarginLeft = Math.max(0, (availableWidth - usedWidth) / 2)
      }

      this.shelvesPerPage = Math.ceil(this.bookshelfHeight / this.shelfHeight) + 2

      const entitiesPerPage = this.shelvesPerPage * this.entitiesPerShelf
      this.booksPerFetch = Math.ceil(entitiesPerPage / 20) * 20 // Round up to the nearest 20

      if (this.totalEntities) {
        this.totalShelves = Math.ceil(this.totalEntities / this.entitiesPerShelf)
      }
      return entitiesPerShelfBefore !== this.entitiesPerShelf // Column count has changed
    },
    async init() {
      if (this.isFirstInit) return
      if (!this.user) {
        // Offline support not available
        await this.resetEntities()
        this.$eventBus.$emit('bookshelf-total-entities', 0)
        return
      }

      this.localLibraryItems = await this.$db.getLocalLibraryItems(this.currentLibraryMediaType)
      console.log('Local library items loaded for lazy bookshelf', this.localLibraryItems.length)

      this.isFirstInit = true
      this.initSizeData()
      await this.loadPage(0)
      var lastBookIndex = Math.min(this.totalEntities, this.shelvesPerPage * this.entitiesPerShelf)
      this.mountEntites(0, lastBookIndex)

      // Set last scroll position for this bookshelf page
      if (this.$store.state.lastBookshelfScrollData[this.page] && window['bookshelf-wrapper']) {
        const { path, scrollTop } = this.$store.state.lastBookshelfScrollData[this.page]
        if (path === this.routeFullPath) {
          // Exact path match with query so use scroll position
          window['bookshelf-wrapper'].scrollTop = scrollTop
        }
      }
    },
    scroll(e) {
      if (!e || !e.target) return
      if (!this.user) return
      var { scrollTop } = e.target
      this.handleScroll(scrollTop)
    },
    buildSearchParams() {
      if (this.page === 'search' || this.page === 'collections') {
        return ''
      } else if (this.page === 'series') {
        // Sort by name ascending
        let searchParams = new URLSearchParams()
        searchParams.set('sort', 'name')
        searchParams.set('desc', 0)
        return searchParams.toString()
      }

      let searchParams = new URLSearchParams()
      if (this.page === 'series-books') {
        searchParams.set('filter', `series.${this.$encode(this.seriesId)}`)
        if (this.collapseBookSeries) {
          searchParams.set('collapseseries', 1)
        }
      } else {
        if (this.filterBy && this.filterBy !== 'all') {
          searchParams.set('filter', this.filterBy)
        }
        if (this.orderBy) {
          searchParams.set('sort', this.orderBy)
          searchParams.set('desc', this.orderDesc ? 1 : 0)
        }
        if (this.collapseSeries) {
          searchParams.set('collapseseries', 1)
        }
      }
      return searchParams.toString()
    },
    checkUpdateSearchParams() {
      const newSearchParams = this.buildSearchParams()
      let currentQueryString = window.location.search
      if (currentQueryString && currentQueryString.startsWith('?')) currentQueryString = currentQueryString.slice(1)

      if (newSearchParams === '' && !currentQueryString) {
        return false
      }
      if (newSearchParams !== this.currentSFQueryString || newSearchParams !== currentQueryString) {
        const queryString = newSearchParams ? `?${newSearchParams}` : ''
        let newurl = window.location.protocol + '//' + window.location.host + window.location.pathname + queryString
        window.history.replaceState({ path: newurl }, '', newurl)

        this.routeFullPath = window.location.pathname + (window.location.search || '') // Update for saving scroll position
        return true
      }

      return false
    },
    settingsUpdated() {
      const wasUpdated = this.checkUpdateSearchParams()
      if (wasUpdated) {
        this.resetEntities()
      }
    },
    libraryChanged() {
      if (this.currentLibraryMediaType !== 'book' && (this.page === 'series' || this.page === 'collections' || this.page === 'series-books')) {
        this.$router.replace('/bookshelf')
        return
      }

      if (this.hasFilter) {
        this.clearFilter()
      } else {
        this.resetEntities()
      }
    },
    libraryItemAdded(libraryItem) {
      console.log('libraryItem added', libraryItem)
      // TODO: Check if item would be on this shelf
      this.resetEntities()
    },
    libraryItemUpdated(libraryItem) {
      console.log('Item updated', libraryItem)
      if (this.entityName === 'books' || this.entityName === 'series-books') {
        var indexOf = this.entities.findIndex((ent) => ent && ent.id === libraryItem.id)
        if (indexOf >= 0) {
          this.entities[indexOf] = libraryItem
          if (this.entityComponentRefs[indexOf]) {
            this.entityComponentRefs[indexOf].setEntity(libraryItem)

            if (this.isBookEntity) {
              var localLibraryItem = this.localLibraryItems.find((lli) => lli.libraryItemId == libraryItem.id)
              if (localLibraryItem) {
                this.entityComponentRefs[indexOf].setLocalLibraryItem(localLibraryItem)
              }
            }
          }
        }
      }
    },
    libraryItemRemoved(libraryItem) {
      if (this.entityName === 'books' || this.entityName === 'series-books') {
        var indexOf = this.entities.findIndex((ent) => ent && ent.id === libraryItem.id)
        if (indexOf >= 0) {
          this.entities = this.entities.filter((ent) => ent.id !== libraryItem.id)
          this.totalEntities = this.entities.length
          this.$eventBus.$emit('bookshelf-total-entities', this.totalEntities)
          this.executeRebuild()
        }
      }
    },
    libraryItemsAdded(libraryItems) {
      console.log('items added', libraryItems)
      // TODO: Check if item would be on this shelf
      this.resetEntities()
    },
    libraryItemsUpdated(libraryItems) {
      libraryItems.forEach((ab) => {
        this.libraryItemUpdated(ab)
      })
    },
    screenOrientationChange() {
      setTimeout(() => {
        console.log('LazyBookshelf Screen orientation change')
        this.resetEntities()
      }, 50)
    },
    initListeners() {
      const bookshelf = document.getElementById('bookshelf-wrapper')
      if (bookshelf) {
        bookshelf.addEventListener('scroll', this.scroll)
      }

      this.$eventBus.$on('library-changed', this.libraryChanged)
      this.$eventBus.$on('user-settings', this.settingsUpdated)

      this.$socket.$on('item_updated', this.libraryItemUpdated)
      this.$socket.$on('item_added', this.libraryItemAdded)
      this.$socket.$on('item_removed', this.libraryItemRemoved)
      this.$socket.$on('items_updated', this.libraryItemsUpdated)
      this.$socket.$on('items_added', this.libraryItemsAdded)

      if (screen.orientation) {
        // Not available on ios
        screen.orientation.addEventListener('change', this.screenOrientationChange)
      } else {
        document.addEventListener('orientationchange', this.screenOrientationChange)
      }
    },
    removeListeners() {
      const bookshelf = document.getElementById('bookshelf-wrapper')
      if (bookshelf) {
        bookshelf.removeEventListener('scroll', this.scroll)
      }

      this.$eventBus.$off('library-changed', this.libraryChanged)
      this.$eventBus.$off('user-settings', this.settingsUpdated)

      this.$socket.$off('item_updated', this.libraryItemUpdated)
      this.$socket.$off('item_added', this.libraryItemAdded)
      this.$socket.$off('item_removed', this.libraryItemRemoved)
      this.$socket.$off('items_updated', this.libraryItemsUpdated)
      this.$socket.$off('items_added', this.libraryItemsAdded)

      if (screen.orientation) {
        // Not available on ios
        screen.orientation.removeEventListener('change', this.screenOrientationChange)
      } else {
        document.removeEventListener('orientationchange', this.screenOrientationChange)
      }
    }
  },
  updated() {
    this.routeFullPath = window.location.pathname + (window.location.search || '')
  },
  mounted() {
    this.routeFullPath = window.location.pathname + (window.location.search || '')

    this.init()
    this.initListeners()
  },
  beforeDestroy() {
    this.removeListeners()

    // Set bookshelf scroll position for specific bookshelf page and query
    if (window['bookshelf-wrapper']) {
      this.$store.commit('setLastBookshelfScrollData', { scrollTop: window['bookshelf-wrapper'].scrollTop || 0, path: this.routeFullPath, name: this.page })
    }
  }
}
</script>

<style>
/* Material 3 Expressive Vertical Scroll Container */
.library-scroll-container {
  scroll-behavior: smooth;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior-y: contain;
}

/* Loading skeleton animations */
.loading-skeleton {
  opacity: 0;
  transform: translateY(20px) scale(0.95);
  animation: skeletonSlideIn 600ms cubic-bezier(0.2, 0, 0, 1) forwards;
}

@keyframes skeletonSlideIn {
  0% {
    opacity: 0;
    transform: translateY(20px) scale(0.95);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

/* Grid layout styles */
.shelf-grid-view {
  /* Grid layout is set via inline styles for dynamic columns */
  position: relative;
}

.shelf-list-view {
  position: relative;
}

</style>
