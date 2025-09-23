<template>
  <div>
    <div id="bookshelf" class="w-full h-full p-4 overflow-y-auto library-scroll-container" :style="contentPaddingStyle">
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
    contentPaddingStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { paddingBottom: '120px' } : {}
    }
  },
  methods: {
    async init() {
      this.cardWidth = (window.innerWidth - 64) / 2
      if (!this.currentLibraryId) {
        return
      }
      this.loadedLibraryId = this.currentLibraryId
      this.authors = await this.$nativeHttp
        .get(`/api/libraries/${this.currentLibraryId}/authors`)
        .then((response) => response.authors)
        .catch((error) => {
          console.error('Failed to load authors', error)
          return []
        })
      console.log('Loaded authors', this.authors)
      this.$eventBus.$emit('bookshelf-total-entities', this.authors.length)
      this.loading = false
    },
    authorAdded(author) {
      if (!this.authors.some((au) => au.id === author.id)) {
        this.authors.push(author)
        this.$eventBus.$emit('bookshelf-total-entities', this.authors.length)
      }
    },
    authorUpdated(author) {
      this.authors = this.authors.map((au) => {
        if (au.id === author.id) {
          return author
        }
        return au
      })
    },
    authorRemoved(author) {
      this.authors = this.authors.filter((au) => au.id !== author.id)
      this.$eventBus.$emit('bookshelf-total-entities', this.authors.length)
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
    this.$socket.$off('author_added', this.authorAdded)
    this.$socket.$off('author_updated', this.authorUpdated)
    this.$socket.$off('author_removed', this.authorRemoved)
    this.$eventBus.$off('library-changed', this.libraryChanged)
  }
}
</script>

<style scoped>
/* Material 3 Expressive Vertical Scroll Container */
.library-scroll-container {
  scroll-behavior: smooth;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior-y: contain;
}
</style>
