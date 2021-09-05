<template>
  <modals-modal v-model="show" width="90%" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-white border-opacity-20 p-8" style="max-height: 75%" @click.stop>
        <ui-text-input ref="input" v-model="search" @input="updateSearch" placeholder="Search" class="w-full text-lg" />
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
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean
  },
  data() {
    return {
      search: null,
      searchTimeout: null,
      lastSearch: null,
      isFetching: false,
      items: []
    }
  },
  watch: {
    value(newVal) {
      if (newVal) {
        this.$nextTick(this.setFocus())
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    }
  },
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
      var results = await this.$axios.$get(`/api/audiobooks?q=${value}`).catch((error) => {
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
  mounted() {}
}
</script>