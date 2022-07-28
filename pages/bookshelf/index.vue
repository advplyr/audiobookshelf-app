<template>
  <div class="w-full h-full min-h-full relative">
    <div v-if="!loading" class="w-full" :class="{ 'py-6': altViewEnabled }">
      <template v-for="(shelf, index) in shelves">
        <bookshelf-shelf :key="shelf.id" :label="shelf.label" :entities="shelf.entities" :type="shelf.type" :style="{ zIndex: shelves.length - index }" />
      </template>
    </div>

    <div v-if="!shelves.length && !loading" class="absolute top-0 left-0 w-full h-full flex items-center justify-center">
      <div>
        <p class="mb-4 text-center text-xl">
          Bookshelf empty
          <span v-show="user">
            for library
            <strong>{{ currentLibraryName }}</strong>
          </span>
        </p>
        <div class="w-full" v-if="!user">
          <div class="flex justify-center items-center mb-3">
            <span class="material-icons text-error text-lg">cloud_off</span>
            <p class="pl-2 text-error text-sm">Audiobookshelf server not connected.</p>
          </div>
        </div>
        <div class="flex justify-center">
          <ui-btn v-if="!user" small @click="$router.push('/connect')" class="w-32">Connect</ui-btn>
        </div>
      </div>
    </div>
    <div v-if="loading" class="absolute top-0 left-0 w-full h-full flex items-center justify-center">
      <ui-loading-indicator text="Loading Library..." />
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      shelves: [],
      loading: false,
      localLibraryItems: []
    }
  },
  computed: {
    user() {
      return this.$store.state.user.user
    },
    isSocketConnected() {
      return this.$store.state.socketConnected
    },
    currentLibraryName() {
      return this.$store.getters['libraries/getCurrentLibraryName']
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    altViewEnabled() {
      return this.$store.getters['getAltViewEnabled']
    }
  },
  methods: {
    async getLocalMediaItemCategories() {
      var localMedia = await this.$db.getLocalLibraryItems()
      console.log('Got local library items', localMedia ? localMedia.length : 'N/A')
      if (!localMedia || !localMedia.length) return []

      var categories = []
      var books = []
      var podcasts = []
      localMedia.forEach((item) => {
        if (item.mediaType == 'book') {
          books.push(item)
        } else if (item.mediaType == 'podcast') {
          podcasts.push(item)
        }
      })

      if (books.length) {
        categories.push({
          id: 'local-books',
          label: 'Local Books',
          type: 'book',
          entities: books.slice(0, 10)
        })
      }
      if (podcasts.length) {
        categories.push({
          id: 'local-podcasts',
          label: 'Local Podcasts',
          type: 'podcast',
          entities: podcasts.slice(0, 10)
        })
      }

      return categories
    },
    async fetchCategories() {
      if (this.loading) {
        console.log('Already loading categories')
        return
      }
      this.loading = true
      this.shelves = []

      this.localLibraryItems = await this.$db.getLocalLibraryItems()

      var localCategories = await this.getLocalMediaItemCategories()
      this.shelves = this.shelves.concat(localCategories)

      if (this.user && this.currentLibraryId) {
        var categories = await this.$axios.$get(`/api/libraries/${this.currentLibraryId}/personalized?minified=1`).catch((error) => {
          console.error('Failed to fetch categories', error)
          return []
        })
        categories = categories.map((cat) => {
          console.log('[breadcrumb] Personalized category from server', cat.type)
          if (cat.type == 'book' || cat.type == 'podcast') {
            // Map localLibraryItem to entities
            cat.entities = cat.entities.map((entity) => {
              var localLibraryItem = this.localLibraryItems.find((lli) => {
                return lli.libraryItemId == entity.id
              })
              if (localLibraryItem) {
                entity.localLibraryItem = localLibraryItem
              }
              return entity
            })
          }
          return cat
        })
        // Put continue listening shelf first
        var continueListeningShelf = categories.find((c) => c.id == 'continue-listening')
        if (continueListeningShelf) {
          this.shelves = [continueListeningShelf, ...this.shelves]
          console.log(this.shelves)
        }
        this.shelves = this.shelves.concat(categories.filter((c) => c.id != 'continue-listening'))
      }
      this.loading = false
    },
    async libraryChanged() {
      if (this.currentLibraryId) {
        await this.fetchCategories()
      }
    },
    audiobookAdded(audiobook) {
      console.log('Audiobook added', audiobook)
      // TODO: Check if audiobook would be on this shelf
      if (!this.search) {
        this.fetchCategories()
      }
    },
    audiobookUpdated(audiobook) {
      console.log('Audiobook updated', audiobook)
      this.shelves.forEach((shelf) => {
        if (shelf.type === 'books') {
          shelf.entities = shelf.entities.map((ent) => {
            if (ent.id === audiobook.id) {
              return audiobook
            }
            return ent
          })
        } else if (shelf.type === 'series') {
          shelf.entities.forEach((ent) => {
            ent.books = ent.books.map((book) => {
              if (book.id === audiobook.id) return audiobook
              return book
            })
          })
        }
      })
    },
    removeBookFromShelf(audiobook) {
      this.shelves.forEach((shelf) => {
        if (shelf.type === 'books') {
          shelf.entities = shelf.entities.filter((ent) => {
            return ent.id !== audiobook.id
          })
        } else if (shelf.type === 'series') {
          shelf.entities.forEach((ent) => {
            ent.books = ent.books.filter((book) => {
              return book.id !== audiobook.id
            })
          })
        }
      })
    },
    initListeners() {
      this.$eventBus.$on('library-changed', this.libraryChanged)
      // this.$eventBus.$on('downloads-loaded', this.downloadsLoaded)
    },
    removeListeners() {
      this.$eventBus.$off('library-changed', this.libraryChanged)
      // this.$eventBus.$off('downloads-loaded', this.downloadsLoaded)
    }
  },
  mounted() {
    this.initListeners()
    this.fetchCategories()
    // if (this.$server.initialized && this.currentLibraryId) {
    //   this.fetchCategories()
    // } else {
    //   this.shelves = this.downloadOnlyShelves
    // }
  },
  beforeDestroy() {
    this.removeListeners()
  }
}
</script>