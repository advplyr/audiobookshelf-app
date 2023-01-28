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
      lastServerFetch: 0,
      localLibraryItems: []
    }
  },
  watch: {
    networkConnected(newVal) {
      // Update shelves when network connect status changes
      console.log(`Network changed to ${newVal} - fetch categories`)

      if (newVal) {
        if (!this.lastServerFetch || Date.now() - this.lastServerFetch < 4000) {
          setTimeout(() => {
            this.fetchCategories()
          }, 4000)
        }
      } else {
        this.fetchCategories()
      }
    }
  },
  computed: {
    user() {
      return this.$store.state.user.user
    },
    networkConnected() {
      return this.$store.state.networkConnected
    },
    currentLibraryName() {
      return this.$store.getters['libraries/getCurrentLibraryName']
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    currentLibraryMediaType() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType']
    },
    altViewEnabled() {
      return this.$store.getters['getAltViewEnabled']
    },
    localMediaProgress() {
      return this.$store.state.globals.localMediaProgress
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
          item.progress = this.localMediaProgress.find((lmp) => lmp.id === item.id)
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
          entities: books.sort((a, b) => {
            if (a.progress && a.progress.isFinished) return 1
            else if (b.progress && b.progress.isFinished) return -1
            return 0
          })
        })
      }
      if (podcasts.length) {
        categories.push({
          id: 'local-podcasts',
          label: 'Local Podcasts',
          type: 'podcast',
          entities: podcasts
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
      const localCategories = await this.getLocalMediaItemCategories()

      if (this.user && this.currentLibraryId && this.networkConnected) {
        this.lastServerFetch = Date.now()
        const categories = await this.$axios.$get(`/api/libraries/${this.currentLibraryId}/personalized?minified=1`).catch((error) => {
          console.error('Failed to fetch categories', error)
          return []
        })
        this.shelves = categories.map((cat) => {
          console.log('[breadcrumb] Personalized category from server', cat.type)
          if (cat.type == 'book' || cat.type == 'podcast' || cat.type == 'episode') {
            // Map localLibraryItem to entities
            cat.entities = cat.entities.map((entity) => {
              const localLibraryItem = this.localLibraryItems.find((lli) => {
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

        // Only add the local shelf with the same media type
        const localShelves = localCategories.filter((cat) => cat.type === this.currentLibraryMediaType)
        this.shelves.push(...localShelves)
      } else {
        // Offline only local
        this.shelves = localCategories
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
    },
    removeListeners() {
      this.$eventBus.$off('library-changed', this.libraryChanged)
    }
  },
  mounted() {
    this.initListeners()
    this.fetchCategories()
  },
  beforeDestroy() {
    this.removeListeners()
  }
}
</script>