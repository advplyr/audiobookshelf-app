<template>
  <div class="w-full h-9 bg-bg relative z-20">
    <div id="bookshelf-toolbar" class="absolute top-0 left-0 w-full h-full z-20 flex items-center px-2">
      <div class="flex items-center w-full text-sm">
        <nuxt-link to="/bookshelf/series" v-if="selectedSeriesName" class="pt-1">
          <span class="material-icons">arrow_back</span>
        </nuxt-link>
        <p v-show="!selectedSeriesName" class="font-book pt-1">{{ numEntities }} {{ entityTitle }}</p>
        <p v-show="selectedSeriesName" class="ml-2 font-book pt-1">{{ selectedSeriesName }}</p>

        <div class="flex-grow" />
        <template v-if="page === 'library'">
          <span class="material-icons px-2" @click="changeView">{{ viewIcon }}</span>
          <div class="relative flex items-center px-2">
            <span class="material-icons" @click="showFilterModal = true">filter_alt</span>
            <div v-show="hasFilters" class="absolute top-0 right-2 w-2 h-2 rounded-full bg-success border border-green-300 shadow-sm z-10 pointer-events-none" />
          </div>
          <span class="material-icons px-2" @click="showSortModal = true">sort</span>
        </template>
      </div>
    </div>

    <modals-order-modal v-model="showSortModal" :order-by.sync="settings.mobileOrderBy" :descending.sync="settings.mobileOrderDesc" @change="updateOrder" />
    <modals-filter-modal v-model="showFilterModal" :filter-by.sync="settings.mobileFilterBy" @change="updateFilter" />
  </div>
</template>

<script>
export default {
  data() {
    return {
      showSortModal: false,
      showFilterModal: false,
      settings: {},
      isListView: false
    }
  },
  computed: {
    hasFilters() {
      return this.$store.getters['user/getUserSetting']('filterBy') !== 'all'
    },
    page() {
      var routeName = this.$route.name || ''
      return routeName.split('-')[1]
    },
    routeQuery() {
      return this.$route.query || {}
    },
    entityTitle() {
      if (this.page === 'library') return 'Audiobooks'
      else if (this.page === 'series') {
        if (this.selectedSeriesName) return 'Books in ' + this.selectedSeriesName
        return 'Series'
      } else if (this.page === 'collections') {
        return 'Collections'
      }
      return ''
    },
    numEntities() {
      if (this.page === 'library') return this.numAudiobooks
      else if (this.page === 'series') {
        if (this.selectedSeriesName) return this.numBooksInSeries
        return this.series.length
      } else if (this.page === 'collections') return this.numCollections
      return 0
    },
    series() {
      return this.$store.getters['audiobooks/getSeriesGroups']() || []
    },
    numCollections() {
      return (this.$store.state.user.collections || []).length
    },
    numAudiobooks() {
      return this.$store.getters['audiobooks/getFiltered']().length
    },
    numBooksInSeries() {
      return this.selectedSeries ? (this.selectedSeries.books || []).length : 0
    },
    selectedSeries() {
      if (!this.selectedSeriesName) return null
      return this.series.find((s) => s.name === this.selectedSeriesName)
    },
    selectedSeriesName() {
      if (this.page === 'series' && this.routeQuery.series) {
        return this.$decode(this.routeQuery.series)
      }
      return null
    },
    viewIcon() {
      return this.isListView ? 'grid_view' : 'view_stream'
    }
  },
  methods: {
    changeView() {
      this.isListView = !this.isListView

      var bookshelfView = this.isListView ? 'list' : 'grid'
      this.$localStore.setBookshelfView(bookshelfView)
    },
    updateOrder() {
      this.saveSettings()
    },
    updateFilter() {
      this.saveSettings()
    },
    saveSettings() {
      this.$store.commit('user/setSettings', this.settings) // Immediate update
      this.$store.dispatch('user/updateUserSettings', this.settings)
    },
    async init() {
      this.settings = { ...this.$store.state.user.settings }

      var bookshelfView = await this.$localStore.getBookshelfView()
      this.isListView = bookshelfView === 'list'
      this.bookshelfReady = true
      console.log('Bookshelf view', bookshelfView)
    },
    settingsUpdated(settings) {
      for (const key in settings) {
        this.settings[key] = settings[key]
      }
    }
  },
  mounted() {
    this.init()
    this.$store.commit('user/addSettingsListener', { id: 'bookshelftoolbar', meth: this.settingsUpdated })
  },
  beforeDestroy() {
    this.$store.commit('user/removeSettingsListener', 'bookshelftoolbar')
  }
}
</script>

<style>
#bookshelf-toolbar {
  box-shadow: 0px 5px 5px #11111155;
}
</style>