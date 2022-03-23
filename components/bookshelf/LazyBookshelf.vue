<template>
  <div id="bookshelf" class="w-full max-w-full h-full">
    <template v-for="shelf in totalShelves">
      <div :key="shelf" class="w-full px-2 bookshelfRow relative" :id="`shelf-${shelf - 1}`" :style="{ height: shelfHeight + 'px' }">
        <div class="bookshelfDivider w-full absolute bottom-0 left-0 z-30" style="min-height: 16px" :class="`h-${shelfDividerHeightIndex}`" />
      </div>
    </template>

    <div v-show="!entities.length && initialized" class="w-full py-16 text-center text-xl">
      <div class="py-4 capitalize">No {{ entityName }}</div>
      <ui-btn v-if="hasFilter" @click="clearFilter">Clear Filter</ui-btn>
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
      bookshelfHeight: 0,
      bookshelfWidth: 0,
      bookshelfMarginLeft: 0,
      shelvesPerPage: 0,
      entitiesPerShelf: 8,
      currentPage: 0,
      currentBookWidth: 0,
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
      pendingReset: false
    }
  },
  computed: {
    isSocketConnected() {
      return this.$store.state.socketConnected
    },
    isBookEntity() {
      return this.entityName === 'books' || this.entityName === 'series-books'
    },
    shelfDividerHeightIndex() {
      if (this.isBookEntity) return 4
      return 6
    },
    entityName() {
      return this.page
    },
    bookshelfView() {
      return this.$store.state.bookshelfView
    },
    hasFilter() {
      return this.filterBy !== 'all'
    },
    isListView() {
      return this.bookshelfView === 'list'
    },
    books() {
      return this.$store.getters['downloads/getAudiobooks']
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
    coverAspectRatio() {
      return this.$store.getters['getServerSetting']('coverAspectRatio')
    },
    isCoverSquareAspectRatio() {
      return this.coverAspectRatio === this.$constants.BookCoverAspectRatio.SQUARE
    },
    bookCoverAspectRatio() {
      return this.isCoverSquareAspectRatio ? 1 : 1.6
    },
    bookWidth() {
      var coverSize = 100
      if (window.innerWidth <= 375) coverSize = 90

      if (this.isCoverSquareAspectRatio) return coverSize * 1.6
      return coverSize
    },
    bookHeight() {
      if (this.isCoverSquareAspectRatio) return this.bookWidth
      return this.bookWidth * 1.6
    },
    entityWidth() {
      if (this.isBookEntity) return this.bookWidth
      return this.bookWidth * 2
    },
    entityHeight() {
      return this.bookHeight
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    shelfHeight() {
      return this.entityHeight + 40
    },
    totalEntityCardWidth() {
      // Includes margin
      return this.entityWidth + 24
    },
    downloads() {
      return this.$store.getters['downloads/getDownloads']
    },
    downloadedBooks() {
      return this.downloads.map((dl) => {
        var download = { ...dl }
        var ab = { ...download.audiobook }
        delete download.audiobook
        ab.download = download
        return ab
      })
    }
  },
  methods: {
    clearFilter() {
      this.$store.dispatch('user/updateUserSettings', {
        mobileFilterBy: 'all'
      })
    },
    async fetchEntities(page) {
      var startIndex = page * this.booksPerFetch

      this.isFetchingEntities = true

      if (!this.initialized) {
        this.currentSFQueryString = this.buildSearchParams()
      }
      // var entityPath = this.entityName === 'books' ? `books/all` : this.entityName
      // var sfQueryString = this.currentSFQueryString ? this.currentSFQueryString + '&' : ''
      // var queryString = `?${sfQueryString}&limit=${this.booksPerFetch}&page=${page}`

      // if (this.entityName === 'series-books') {
      //   entityPath = `series/${this.seriesId}`
      //   queryString = ''
      // }

      // var payload = await this.$axios.$get(`/api/libraries/${this.currentLibraryId}/${entityPath}${queryString}`).catch((error) => {
      //   console.error('failed to fetch books', error)
      //   return null
      // })

      var entityPath = this.entityName === 'books' || this.entityName === 'series-books' ? `items` : this.entityName
      var sfQueryString = this.currentSFQueryString ? this.currentSFQueryString + '&' : ''
      var fullQueryString = `?${sfQueryString}limit=${this.booksPerFetch}&page=${page}&minified=1`

      var payload = await this.$axios.$get(`/api/libraries/${this.currentLibraryId}/${entityPath}${fullQueryString}`).catch((error) => {
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
        }

        for (let i = 0; i < payload.results.length; i++) {
          if (this.entityName === 'books' || this.entityName === 'series-books') {
            // Check if has download and append download obj
            var download = this.downloads.find((dl) => dl.id === payload.results[i].id)
            if (download) {
              var dl = { ...download }
              delete dl.audiobook
              payload.results[i].download = dl
            }
          }

          var index = i + startIndex
          this.entities[index] = payload.results[i]
          if (this.entityComponentRefs[index]) {
            this.entityComponentRefs[index].setEntity(this.entities[index])
          }
        }
      }
    },
    async loadPage(page) {
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
        this.entities = this.downloadedBooks
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
      if (this.isSocketConnected) {
        await this.loadPage(0)
        var lastBookIndex = Math.min(this.totalEntities, this.shelvesPerPage * this.entitiesPerShelf)
        this.mountEntites(0, lastBookIndex)
      } else {
        this.setDownloads()

        this.mountEntites(0, this.totalEntities - 1)
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
      if (!bookshelf) {
        console.error('Failed to init size data')
        return
      }
      var entitiesPerShelfBefore = this.entitiesPerShelf

      var { clientHeight, clientWidth } = bookshelf
      this.bookshelfHeight = clientHeight
      this.bookshelfWidth = clientWidth
      this.entitiesPerShelf = Math.floor((this.bookshelfWidth - 16) / this.totalEntityCardWidth)

      this.shelvesPerPage = Math.ceil(this.bookshelfHeight / this.shelfHeight) + 2
      this.bookshelfMarginLeft = (this.bookshelfWidth - this.entitiesPerShelf * this.totalEntityCardWidth) / 2

      this.currentBookWidth = this.bookWidth
      if (this.totalEntities) {
        this.totalShelves = Math.ceil(this.totalEntities / this.entitiesPerShelf)
      }
      return entitiesPerShelfBefore < this.entitiesPerShelf // Books per shelf has changed
    },
    async init() {
      if (this.isFirstInit) return
      this.isFirstInit = true
      this.initSizeData()
      await this.loadPage(0)
      var lastBookIndex = Math.min(this.totalEntities, this.shelvesPerPage * this.entitiesPerShelf)
      this.mountEntites(0, lastBookIndex)
    },
    initDownloads() {
      this.initSizeData()
      this.setDownloads()
      this.$nextTick(() => {
        console.log('Mounting downloads', this.totalEntities, 'total shelves', this.totalShelves)
        this.mountEntites(0, this.totalEntities)
      })
    },
    scroll(e) {
      if (!e || !e.target) return
      if (!this.isSocketConnected) return // Offline books are all mounted at once
      var { scrollTop } = e.target
      this.handleScroll(scrollTop)
    },
    socketInit(isConnected) {
      if (isConnected) {
        this.init()
      } else {
        this.isFirstInit = false
        this.resetEntities()
      }
    },
    buildSearchParams() {
      let searchParams = new URLSearchParams()
      if (this.filterBy && this.filterBy !== 'all') {
        searchParams.set('filter', this.filterBy)
      }
      if (this.orderBy) {
        searchParams.set('sort', this.orderBy)
        searchParams.set('desc', this.orderDesc ? 1 : 0)
      }
      return searchParams.toString()
    },
    checkUpdateSearchParams() {
      var newSearchParams = this.buildSearchParams()
      var currentQueryString = window.location.search
      if (currentQueryString && currentQueryString.startsWith('?')) currentQueryString = currentQueryString.slice(1)

      if (newSearchParams === '') {
        return false
      }
      if (newSearchParams !== this.currentSFQueryString || newSearchParams !== currentQueryString) {
        let newurl = window.location.protocol + '//' + window.location.host + window.location.pathname + '?' + newSearchParams
        window.history.replaceState({ path: newurl }, '', newurl)
        return true
      }

      return false
    },
    settingsUpdated(settings) {
      var wasUpdated = this.checkUpdateSearchParams()
      if (wasUpdated) {
        this.resetEntities()
      }
    },
    libraryChanged(libid) {
      if (this.hasFilter) {
        this.clearFilter()
      } else {
        this.resetEntities()
      }
    },
    downloadsLoaded() {
      if (!this.isSocketConnected) {
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
    initListeners() {
      var bookshelf = document.getElementById('bookshelf-wrapper')
      if (bookshelf) {
        bookshelf.addEventListener('scroll', this.scroll)
      }

      this.$eventBus.$on('library-changed', this.libraryChanged)
      this.$eventBus.$on('downloads-loaded', this.downloadsLoaded)
      this.$store.commit('user/addSettingsListener', { id: 'lazy-bookshelf', meth: this.settingsUpdated })

      if (this.$server.socket) {
        this.$server.socket.on('item_updated', this.libraryItemUpdated)
        this.$server.socket.on('item_added', this.libraryItemAdded)
        this.$server.socket.on('item_removed', this.libraryItemRemoved)
        this.$server.socket.on('items_updated', this.libraryItemsUpdated)
        this.$server.socket.on('items_added', this.libraryItemsAdded)
      } else {
        console.error('Bookshelf - Socket not initialized')
      }
    },
    removeListeners() {
      var bookshelf = document.getElementById('bookshelf-wrapper')
      if (bookshelf) {
        bookshelf.removeEventListener('scroll', this.scroll)
      }

      this.$eventBus.$off('library-changed', this.libraryChanged)
      this.$eventBus.$off('downloads-loaded', this.downloadsLoaded)
      this.$store.commit('user/removeSettingsListener', 'lazy-bookshelf')

      if (this.$server.socket) {
        this.$server.socket.off('item_updated', this.libraryItemUpdated)
        this.$server.socket.off('item_added', this.libraryItemAdded)
        this.$server.socket.off('item_removed', this.libraryItemRemoved)
        this.$server.socket.off('items_updated', this.libraryItemsUpdated)
        this.$server.socket.off('items_added', this.libraryItemsAdded)
      } else {
        console.error('Bookshelf - Socket not initialized')
      }
    }
  },
  mounted() {
    if (this.$server.initialized) {
      this.init()
    } else {
      this.initDownloads()
    }
    this.$server.on('initialized', this.socketInit)
    this.initListeners()
  },
  beforeDestroy() {
    this.$server.off('initialized', this.socketInit)
    this.removeListeners()
  }
}
</script>
