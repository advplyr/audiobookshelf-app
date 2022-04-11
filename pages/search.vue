<template>
  <div class="w-full h-full">
    <div class="px-4 py-6">
      <ui-text-input ref="input" v-model="search" @input="updateSearch" borderless placeholder="Search" bg="white bg-opacity-5" rounded="md" prepend-icon="search" text-size="base" class="w-full text-lg" />
    </div>
    <div class="w-full overflow-x-hidden overflow-y-auto search-content px-4" @click.stop>
      <div v-show="isFetching" class="w-full py-8 flex justify-center">
        <p class="text-lg text-gray-400">Fetching...</p>
      </div>
      <div v-if="!isFetching && lastSearch && !totalResults" class="w-full py-8 flex justify-center">
        <p class="text-lg text-gray-400">Nothing found</p>
      </div>
      <p v-if="bookResults.length" class="font-semibold text-sm mb-1">Books</p>
      <template v-for="bookResult in bookResults">
        <div :key="bookResult.audiobook.id" class="w-full h-16 py-1">
          <nuxt-link :to="`/item/${bookResult.audiobook.id}`">
            <cards-book-search-card :audiobook="bookResult.audiobook" :search="lastSearch" :match-key="bookResult.matchKey" :match-text="bookResult.matchText" />
          </nuxt-link>
        </div>
      </template>

      <p v-if="seriesResults.length" class="font-semibold text-sm mb-1 mt-2">Series</p>
      <template v-for="seriesResult in seriesResults">
        <div :key="seriesResult.series" class="w-full h-16 py-1">
          <nuxt-link :to="`/bookshelf/series/${$encode(seriesResult.series)}`">
            <cards-series-search-card :series="seriesResult.series" :book-items="seriesResult.audiobooks" />
          </nuxt-link>
        </div>
      </template>

      <p v-if="authorResults.length" class="font-semibold text-sm mb-1 mt-2">Authors</p>
      <template v-for="authorResult in authorResults">
        <div :key="authorResult.author" class="w-full h-14 py-1">
          <nuxt-link :to="`/bookshelf/library?filter=authors.${$encode(authorResult.author)}`">
            <cards-author-search-card :key="authorResult.author" :author="authorResult.author" />
          </nuxt-link>
        </div>
      </template>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      search: null,
      searchTimeout: null,
      lastSearch: null,
      isFetching: false,
      bookResults: [],
      seriesResults: [],
      authorResults: []
    }
  },
  computed: {
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    bookCoverAspectRatio() {
      return this.$store.getters['getBookCoverAspectRatio']
    },
    totalResults() {
      return this.bookResults.length + this.seriesResults.length + this.authorResults.length
    }
  },
  methods: {
    async runSearch(value) {
      this.lastSearch = value
      if (!this.lastSearch) {
        this.bookResults = []
        this.seriesResults = []
        this.authorResults = []
        return
      }
      this.isFetching = true
      var results = await this.$axios.$get(`/api/libraries/${this.currentLibraryId}/search?q=${value}&limit=5`).catch((error) => {
        console.error('Search error', error)
        return []
      })
      console.log('RESULTS', results)

      this.isFetching = false

      this.bookResults = results ? results.audiobooks || [] : []
      this.seriesResults = results ? results.series || [] : []
      this.authorResults = results ? results.authors || [] : []
    },
    updateSearch(val) {
      clearTimeout(this.searchTimeout)
      this.searchTimeout = setTimeout(() => {
        this.runSearch(val)
      }, 500)
    },
    setFocus() {
      setTimeout(() => {
        if (this.$refs.input) {
          this.$refs.input.focus()
        }
      }, 100)
    }
  },
  mounted() {
    this.$nextTick(this.setFocus())
  }
}
</script>

<style>
.search-content {
  height: calc(100% - 108px);
  max-height: calc(100% - 108px);
}
</style>