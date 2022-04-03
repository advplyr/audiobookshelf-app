<template>
  <div class="w-full h-full min-h-full relative">
    <template v-for="(shelf, index) in shelves">
      <bookshelf-shelf :key="shelf.id" :label="shelf.label" :entities="shelf.entities" :type="shelf.type" :style="{ zIndex: shelves.length - index }" />
    </template>

    <div v-if="!shelves.length" class="absolute top-0 left-0 w-full h-full flex items-center justify-center">
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
          <!-- <p class="px-4 text-center text-error absolute bottom-12 left-0 right-0 mx-auto"><strong>Important!</strong> This app requires that you are running <u>your own server</u> and does not provide any content.</p> -->
        </div>
        <div class="flex justify-center">
          <ui-btn v-if="!user" small @click="$router.push('/connect')" class="w-32">Connect</ui-btn>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      shelves: [],
      loading: true
    }
  },
  computed: {
    books() {
      return this.$store.getters['downloads/getDownloads'].map((dl) => {
        var download = { ...dl }
        var ab = { ...download.audiobook }
        delete download.audiobook
        ab.download = download
        return ab
      })
    },
    user() {
      return this.$store.state.user.user
    },
    isSocketConnected() {
      return this.$store.state.socketConnected
    },
    currentLibraryName() {
      return this.$store.getters['libraries/getCurrentLibraryName']
    },
    booksWithUserAbData() {
      var books = this.books.map((b) => {
        var userAbData = this.$store.getters['user/getUserAudiobookData'](b.id)
        return { ...b, userAbData }
      })
      return books
    },
    booksCurrentlyReading() {
      var books = this.booksWithUserAbData
        .map((b) => ({ ...b }))
        .filter((b) => b.userAbData && !b.userAbData.isRead && b.userAbData.progress > 0)
        .sort((a, b) => {
          return b.userAbData.lastUpdate - a.userAbData.lastUpdate
        })
      return books
    },
    booksRecentlyAdded() {
      var books = this.books
        .map((b) => {
          return { ...b }
        })
        .sort((a, b) => b.addedAt - a.addedAt)
      return books.slice(0, 10)
    },
    booksRead() {
      var books = this.booksWithUserAbData
        .filter((b) => b.userAbData && b.userAbData.isRead)
        .sort((a, b) => {
          return b.userAbData.lastUpdate - a.userAbData.lastUpdate
        })
      return books.slice(0, 10)
    },
    downloadOnlyShelves() {
      var shelves = []

      if (this.booksCurrentlyReading.length) {
        shelves.push({
          id: 'recent',
          label: 'Continue Reading',
          type: 'books',
          entities: this.booksCurrentlyReading
        })
      }

      if (this.booksRecentlyAdded.length) {
        shelves.push({
          id: 'added',
          label: 'Recently Added',
          type: 'books',
          entities: this.booksRecentlyAdded
        })
      }

      if (this.booksRead.length) {
        shelves.push({
          id: 'read',
          label: 'Read Again',
          type: 'books',
          entities: this.booksRead
        })
      }
      return shelves
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    }
  },
  methods: {
    async fetchCategories() {
      if (!this.currentLibraryId) return null
      var categories = await this.$axios
        .$get(`/api/libraries/${this.currentLibraryId}/personalized?minified=1`)
        .then((data) => {
          return data
        })
        .catch((error) => {
          console.error('Failed to fetch categories', error)
          return []
        })
      this.shelves = categories
      console.log('Shelves', this.shelves)
    },
    // async socketInit(isConnected) {
    //   if (isConnected && this.currentLibraryId) {
    //     console.log('Connected - Load from server')
    //     await this.fetchCategories()
    //   } else {
    //     console.log('Disconnected - Reset to local storage')
    //     this.shelves = this.downloadOnlyShelves
    //   }
    //   this.loading = false
    // },
    async libraryChanged(libid) {
      if (this.isSocketConnected && this.currentLibraryId) {
        await this.fetchCategories()
      } else {
        this.shelves = this.downloadOnlyShelves
      }
    },
    downloadsLoaded() {
      if (!this.isSocketConnected) {
        this.shelves = this.downloadOnlyShelves
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
    audiobookRemoved(audiobook) {
      this.removeBookFromShelf(audiobook)
    },
    audiobooksAdded(audiobooks) {
      console.log('audiobooks added', audiobooks)
      // TODO: Check if audiobook would be on this shelf
      this.fetchCategories()
    },
    audiobooksUpdated(audiobooks) {
      audiobooks.forEach((ab) => {
        this.audiobookUpdated(ab)
      })
    },
    initListeners() {
      // this.$server.on('initialized', this.socketInit)
      this.$eventBus.$on('library-changed', this.libraryChanged)
      this.$eventBus.$on('downloads-loaded', this.downloadsLoaded)
    },
    removeListeners() {
      // this.$server.off('initialized', this.socketInit)
      this.$eventBus.$off('library-changed', this.libraryChanged)
      this.$eventBus.$off('downloads-loaded', this.downloadsLoaded)
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