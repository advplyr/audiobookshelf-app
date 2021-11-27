<template>
  <div class="w-full h-full">
    <div class="px-4 py-6">
      <ui-text-input ref="input" v-model="search" @input="updateSearch" borderless placeholder="Search" bg="white bg-opacity-5" rounded="md" prepend-icon="search" text-size="base" class="w-full text-lg" />
    </div>
    <div class="w-full overflow-x-hidden overflow-y-auto search-content px-4" @click.stop>
      <div v-show="isFetching" class="w-full py-8 flex justify-center">
        <p class="text-lg text-gray-400">Fetching...</p>
      </div>
      <div v-if="!isFetching && lastSearch && !items.length" class="w-full py-8 flex justify-center">
        <p class="text-lg text-gray-400">Nothing found</p>
      </div>
      <template v-for="item in items">
        <div class="py-2 border-b border-bg flex" :key="item.id" @click="clickItem(item)">
          <cards-book-cover :audiobook="item.data" :width="50" />
          <div class="flex-grow px-4 h-full">
            <div class="w-full h-full">
              <p class="text-base truncate">{{ item.data.book.title }}</p>
              <p class="text-sm text-gray-400 truncate">{{ item.data.book.author }}</p>
            </div>
          </div>
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
      items: []
    }
  },
  computed: {},
  methods: {
    clickItem(item) {
      this.show = false
      this.$router.push(`/audiobook/${item.id}`)
    },
    async runSearch(value) {
      this.lastSearch = value
      if (!this.lastSearch) {
        this.items = []
        return
      }
      this.isFetching = true
      var results = await this.$axios.$get(`/api/books?q=${value}`).catch((error) => {
        console.error('Search error', error)
        return []
      })
      this.isFetching = false
      this.items = results.map((res) => {
        return {
          id: res.id,
          data: res,
          type: 'audiobook'
        }
      })
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