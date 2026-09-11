<template>
  <div class="flex flex-col h-full">
    <!-- Toolbar: sort selector + list/grid toggle -->
    <div class="flex items-center px-4 py-2 border-b border-white border-opacity-10 flex-shrink-0">
      <div class="flex items-center border border-white border-opacity-25 rounded px-2 cursor-pointer" @click="cycleSortKey">
        <p class="text-sm text-fg">{{ sortLabel }}</p>
        <span class="material-symbols ml-1 text-fg">{{ sortDir === 'asc' ? 'arrow_drop_up' : 'arrow_drop_down' }}</span>
      </div>
      <div class="flex-grow" />
      <span class="material-symbols text-2xl px-2 cursor-pointer text-fg" @click="toggleListView">{{ listView ? 'grid_view' : 'view_list' }}</span>
    </div>

    <!-- Content -->
    <div id="bookshelf" class="flex-grow overflow-y-auto">
      <!-- Grid view -->
      <div v-if="!listView" class="flex flex-wrap justify-center p-4">
        <div v-for="author in sortedAuthors" :key="author.id" class="p-2 cursor-pointer" @click="navigateToAuthor(author)">
          <cards-author-card :author="author" :width="cardWidth" :height="cardHeight" />
        </div>
      </div>

      <!-- List view -->
      <div v-else class="w-full">
        <cards-author-list-row v-for="author in sortedAuthors" :key="author.id" :author="author" />
      </div>
    </div>
  </div>
</template>

<script>
const SORT_CYCLE = [
  { key: 'name', dir: 'asc', label: 'Name (A-Z)' },
  { key: 'name', dir: 'desc', label: 'Name (Z-A)' },
  { key: 'numBooks', dir: 'desc', label: 'Most Books' },
  { key: 'numBooks', dir: 'asc', label: 'Fewest Books' }
]

export default {
  data() {
    return {
      loading: true,
      authors: [],
      loadedLibraryId: null,
      cardWidth: 200,
      listView: false,
      sortIndex: 0
    }
  },
  computed: {
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    cardHeight() {
      return this.cardWidth * 1.25
    },
    sortKey() {
      return SORT_CYCLE[this.sortIndex].key
    },
    sortDir() {
      return SORT_CYCLE[this.sortIndex].dir
    },
    sortLabel() {
      return SORT_CYCLE[this.sortIndex].label
    },
    sortedAuthors() {
      return [...this.authors].sort((a, b) => {
        let av = a[this.sortKey]
        let bv = b[this.sortKey]
        if (typeof av === 'string') av = av.toLowerCase()
        if (typeof bv === 'string') bv = bv.toLowerCase()
        if (av < bv) return this.sortDir === 'asc' ? -1 : 1
        if (av > bv) return this.sortDir === 'asc' ? 1 : -1
        return 0
      })
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
    cycleSortKey() {
      this.sortIndex = (this.sortIndex + 1) % SORT_CYCLE.length
    },
    toggleListView() {
      this.listView = !this.listView
      this.$localStore.setAuthorsListView(this.listView)
    },
    navigateToAuthor(author) {
      this.$router.push(`/bookshelf/library?filter=authors.${this.$encode(author.id)}`)
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
  async mounted() {
    this.listView = await this.$localStore.getAuthorsListView()
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
