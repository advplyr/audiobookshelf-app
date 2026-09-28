<template>
  <div>
    <div id="bookshelf" class="w-full h-full p-4 overflow-y-auto">
      <div v-if="authorsListView" class="flex flex-col">
        <template v-for="author in authors">
          <nuxt-link :key="author.id" :to="`/bookshelf/library?filter=authors.${$encode(author.id)}`" class="flex items-center py-2 border-b border-white border-opacity-10">
            <div class="w-14 h-14 flex-shrink-0 rounded overflow-hidden">
              <covers-author-image :author="author" />
            </div>
            <div class="ml-3 min-w-0">
              <p class="font-semibold truncate">{{ author.name }}</p>
              <p class="text-sm text-fg-muted">{{ author.numBooks || 0 }} {{ $strings.LabelBooks }}</p>
            </div>
          </nuxt-link>
        </template>
      </div>
      <div v-else class="flex flex-wrap justify-center">
        <template v-for="author in authors">
          <cards-author-card :key="author.id" :author="author" :width="cardWidth" :height="cardHeight" class="p-2" />
        </template>
      </div>
      <div v-if="!loading && !authors.length" class="w-full py-16 text-center text-xl">
        <div class="py-4">{{ $strings.MessageNoItemsFound }}</div>
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
    authorsListView() {
      return this.$store.state.globals.authorsListView
    },
    authorSortBy() {
      return this.$store.getters['user/getUserSetting']('authorSortBy') || 'name'
    },
    authorSortDesc() {
      return !!this.$store.getters['user/getUserSetting']('authorSortDesc')
    }
  },
  watch: {
    authorsListView() {
      // layout only
    },
    authorSortBy() {
      this.init()
    },
    authorSortDesc() {
      this.init()
    }
  },
  methods: {
    async init() {
      this.cardWidth = (window.innerWidth - 64) / 2
      if (!this.currentLibraryId) {
        return
      }
      this.loadedLibraryId = this.currentLibraryId
      this.loading = true
      const sort = this.authorSortBy
      const desc = this.authorSortDesc ? 1 : 0
      this.authors = await this.$nativeHttp
        .get(`/api/libraries/${this.currentLibraryId}/authors?sort=${encodeURIComponent(sort)}&desc=${desc}`)
        .then((response) => response.authors || response.results || [])
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
    },
    async settingsUpdated() {
      // Sort settings watched separately; ensure authors list view is loaded
    },
    async loadAuthorsListView() {
      const val = await this.$localStore.getAuthorsListView()
      this.$store.commit('globals/setAuthorsListView', val)
    }
  },
  mounted() {
    this.loadAuthorsListView()
    this.init()
    this.$socket.$on('author_added', this.authorAdded)
    this.$socket.$on('author_updated', this.authorUpdated)
    this.$socket.$on('author_removed', this.authorRemoved)
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.$eventBus.$on('user-settings', this.settingsUpdated)
  },
  beforeDestroy() {
    this.$socket.$off('author_added', this.authorAdded)
    this.$socket.$off('author_updated', this.authorUpdated)
    this.$socket.$off('author_removed', this.authorRemoved)
    this.$eventBus.$off('library-changed', this.libraryChanged)
    this.$eventBus.$off('user-settings', this.settingsUpdated)
  }
}
</script>
