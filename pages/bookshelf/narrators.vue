<template>
  <div class="p-4">
    <p v-if="loading" class="py-8 text-center text-fg-muted" role="status">{{ $strings.MessageLoading }}</p>
    <div v-else-if="loadFailed" class="py-8 text-center" role="alert">
      <p class="mb-4">{{ $strings.MessageFailedToLoadNarrators }}</p>
      <button type="button" class="bg-secondary rounded-md px-4 py-3" @click="init">{{ $strings.ButtonRetry }}</button>
    </div>
    <p v-else-if="!narrators.length" class="py-8 text-center text-fg-muted">{{ $strings.MessageNoItemsFound }}</p>
    <ul v-else class="space-y-2">
      <li v-for="narrator in sortedNarrators" :key="narrator.name">
        <nuxt-link :to="`/bookshelf/library?filter=narrators.${$encode(narrator.name)}`" class="flex items-center w-full bg-primary rounded-md p-4 focus-visible:ring-2 focus-visible:ring-yellow-300">
          <span class="material-symbols text-2xl mr-4 text-fg-muted" aria-hidden="true">mic</span>
          <div class="min-w-0 flex-1">
            <p class="font-semibold break-words">{{ narrator.name }}</p>
            <p class="text-sm text-fg-muted">{{ narrator.numBooks }} {{ $strings.LabelBooks }}</p>
          </div>
          <span class="material-symbols ml-2 text-fg-muted" aria-hidden="true">chevron_right</span>
        </nuxt-link>
      </li>
    </ul>
  </div>
</template>

<script>
const itemEvents = ['item_added', 'item_updated', 'item_removed', 'items_added', 'items_updated']

export default {
  data() {
    return { narrators: [], loading: true, loadFailed: false, loadRequestId: 0, reloadTimer: null }
  },
  computed: {
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    sortBy() {
      return this.$store.state.user.settings.mobileNarratorSortBy === 'numBooks' ? 'numBooks' : 'name'
    },
    sortDesc() {
      return this.$store.state.user.settings.mobileNarratorSortDesc === true
    },
    sortedNarrators() {
      const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
      const direction = this.sortDesc ? -1 : 1
      return [...this.narrators].sort((a, b) => {
        const nameOrder = collator.compare(a.name, b.name)
        if (this.sortBy === 'numBooks') return (a.numBooks - b.numBooks) * direction || nameOrder
        return nameOrder * direction
      })
    }
  },
  methods: {
    async init() {
      clearTimeout(this.reloadTimer)
      const requestId = ++this.loadRequestId
      this.loading = true
      this.loadFailed = false
      if (!this.currentLibraryId) {
        this.narrators = []
        this.loading = false
        this.$eventBus.$emit('bookshelf-total-entities', 0)
        return
      }
      try {
        const response = await this.$nativeHttp.get(`/api/libraries/${this.currentLibraryId}/narrators`)
        if (!Array.isArray(response?.narrators) || response.narrators.some((n) => typeof n?.name !== 'string' || !Number.isFinite(n.numBooks) || n.numBooks < 0)) {
          throw new Error('Invalid narrators response')
        }
        if (requestId !== this.loadRequestId) return
        this.narrators = response.narrators
        this.$eventBus.$emit('bookshelf-total-entities', this.narrators.length)
      } catch (error) {
        if (requestId !== this.loadRequestId) return
        this.narrators = []
        this.loadFailed = true
        this.$eventBus.$emit('bookshelf-total-entities', 0)
      } finally {
        if (requestId === this.loadRequestId) this.loading = false
      }
    },
    libraryChanged() {
      if (this.$store.getters['libraries/getCurrentLibraryMediaType'] === 'podcast') {
        this.loadRequestId++
        this.$router.replace('/bookshelf')
      } else {
        this.init()
      }
    },
    scheduleReload() {
      // Coalesce bulk metadata updates and invalidate any older in-flight list.
      this.loadRequestId++
      clearTimeout(this.reloadTimer)
      this.reloadTimer = setTimeout(() => this.init(), 250)
    }
  },
  mounted() {
    this.libraryChanged()
    this.$eventBus.$on('library-changed', this.libraryChanged)
    itemEvents.forEach((event) => this.$socket.$on(event, this.scheduleReload))
  },
  beforeDestroy() {
    this.loadRequestId++
    clearTimeout(this.reloadTimer)
    this.$eventBus.$off('library-changed', this.libraryChanged)
    itemEvents.forEach((event) => this.$socket.$off(event, this.scheduleReload))
  }
}
</script>
