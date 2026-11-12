<template>
  <div>
    <div id="bookshelf" class="w-full h-full p-4 overflow-y-auto">
      <div class="flex flex-wrap justify-center">
        <template v-for="author in authors">
          <cards-author-card :key="author.id" :author="author" :width="cardWidth" :height="cardHeight" class="p-2" />
        </template>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      loading: true,
      authors: [],
      loadedLibraryId: null,
      loadRequestId: 0,
      cardWidth: 200
    }
  },
  computed: {
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    cardHeight() {
      return this.cardWidth * 1.25
    },
    sortBy() {
      return this.$store.state.user.settings.mobileAuthorSortBy || 'name'
    },
    sortDesc() {
      return this.$store.state.user.settings.mobileAuthorSortDesc === true
    },
    sortKey() {
      return `${this.sortBy}|${this.sortDesc}`
    }
  },
  watch: {
    sortKey() {
      this.init()
    }
  },
  methods: {
    async init() {
      const requestId = ++this.loadRequestId
      this.cardWidth = (window.innerWidth - 64) / 2
      if (!this.currentLibraryId) {
        return
      }
      this.loadedLibraryId = this.currentLibraryId
      const authors = await this.$nativeHttp
        .get(`/api/libraries/${this.currentLibraryId}/authors?sort=${this.sortBy}&desc=${this.sortDesc ? 1 : 0}`)
        .then((response) => response.authors)
        .catch((error) => {
          console.error('Failed to load authors', error)
          return []
        })
      if (requestId !== this.loadRequestId) return
      this.authors = authors
      console.log('Loaded authors', this.authors)
      this.$eventBus.$emit('bookshelf-total-entities', this.authors.length)
      this.loading = false
    },
    authorAdded(author) {
      if (this.authors.some((au) => au.id === author.id)) return
      // Reload instead of pushing, so the new author lands in the sorted position for any sort option
      this.init()
    },
    authorUpdated(author) {
      if (!this.authors.some((au) => au.id === author.id)) return
      this.init()
    },
    authorRemoved(author) {
      // Invalidate pending responses that may still contain the removed author.
      this.loadRequestId++
      this.authors = this.authors.filter((au) => au.id !== author.id)
      this.$eventBus.$emit('bookshelf-total-entities', this.authors.length)
      this.init()
    },
    libraryChanged(libraryId) {
      if (libraryId !== this.loadedLibraryId) {
        if (this.$store.getters['libraries/getCurrentLibraryMediaType'] === 'book') {
          this.init()
        } else {
          this.$router.replace('/bookshelf')
        }
      }
    }
  },
  mounted() {
    this.init()
    this.$socket.$on('author_added', this.authorAdded)
    this.$socket.$on('author_updated', this.authorUpdated)
    this.$socket.$on('author_removed', this.authorRemoved)
    this.$eventBus.$on('library-changed', this.libraryChanged)
  },
  beforeDestroy() {
    this.loadRequestId++
    this.$socket.$off('author_added', this.authorAdded)
    this.$socket.$off('author_updated', this.authorUpdated)
    this.$socket.$off('author_removed', this.authorRemoved)
    this.$eventBus.$off('library-changed', this.libraryChanged)
  }
}
</script>
