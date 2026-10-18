<template>
  <div class="w-full h-9 bg-bg relative z-20">
    <div id="bookshelf-toolbar" class="absolute top-0 left-0 w-full h-full z-20 flex items-center px-2">
      <div class="flex items-center w-full text-sm">
        <p v-show="!selectedSeriesName" class="pt-1">{{ $formatNumber(totalEntities) }} {{ entityTitle }}</p>
        <p v-show="selectedSeriesName" class="ml-2 pt-1">{{ selectedSeriesName }} ({{ $formatNumber(totalEntities) }})</p>
        <div class="flex-grow" />
        <span v-if="showListGridToggle" class="material-symbols text-2xl px-2" @click="changeView">{{ !bookshelfListView ? 'view_list' : 'grid_view' }}</span>
        <span v-if="page === 'authors'" class="material-symbols text-2xl px-2" @click="changeAuthorsView">{{ !authorsListView ? 'view_list' : 'grid_view' }}</span>
        <template v-if="showFilterSort">
          <div class="relative flex items-center px-2">
            <span class="material-symbols text-2xl" @click="showFilterModal = true">filter_alt</span>
            <div v-show="hasFilters" class="absolute top-0 right-2 w-2 h-2 rounded-full bg-success border border-green-300 shadow-sm z-10 pointer-events-none" />
          </div>
          <span class="material-symbols text-2xl px-2" @click="showSortModal = true">sort</span>
        </template>
        <span v-if="page === 'authors'" class="material-symbols text-2xl px-2" @click="showSortModal = true">sort</span>
        <span v-if="seriesBookPage" class="material-symbols text-2xl px-2" @click="downloadSeries">download</span>
        <span v-if="(page == 'library' && isBookLibrary) || seriesBookPage" class="material-symbols text-2xl px-2" @click="showMoreMenuDialog = true">more_vert</span>
      </div>
    </div>

    <modals-order-modal v-model="showSortModal" :order-by.sync="orderBySetting" :descending.sync="orderDescSetting" :items="orderModalItems" @change="updateOrder" />
    <modals-filter-modal v-model="showFilterModal" :filter-by.sync="filterBySetting" :filter-context="filterContext" @change="updateFilter" />
    <modals-dialog v-model="showMoreMenuDialog" :items="menuItems" @action="clickMenuAction" />
  </div>
</template>

<script>
export default {
  data() {
    return {
      showSortModal: false,
      showFilterModal: false,
      settings: {},
      totalEntities: 0,
      showMoreMenuDialog: false
    }
  },
  computed: {
    bookshelfListView: {
      get() {
        return this.$store.state.globals.bookshelfListView
      },
      set(val) {
        this.$localStore.setBookshelfListView(val)
        this.$store.commit('globals/setBookshelfListView', val)
      }
    },
    authorsListView: {
      get() {
        return this.$store.state.globals.authorsListView
      },
      set(val) {
        this.$localStore.setAuthorsListView(val)
        this.$store.commit('globals/setAuthorsListView', val)
      }
    },
    currentLibraryMediaType() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType']
    },
    isBookLibrary() {
      return this.currentLibraryMediaType === 'book'
    },
    page() {
      var routeName = this.$route.name || ''
      return routeName.split('-')[1]
    },
    seriesBookPage() {
      return this.$route.name == 'bookshelf-series-id'
    },
    showListGridToggle() {
      return this.page == 'library' || this.seriesBookPage
    },
    showFilterSort() {
      return this.page === 'library' || (this.page === 'series' && !this.seriesBookPage) || this.page === 'collections'
    },
    filterContext() {
      if (this.page === 'series' && !this.seriesBookPage) return 'series'
      if (this.page === 'collections') return 'collections'
      return 'books'
    },
    hasFilters() {
      return this.filterBySetting !== 'all'
    },
    filterBySetting: {
      get() {
        if (this.page === 'series' && !this.seriesBookPage) return this.settings.seriesFilterBy || 'all'
        if (this.page === 'collections') return this.settings.collectionFilterBy || 'all'
        return this.settings.mobileFilterBy || 'all'
      },
      set(val) {
        if (this.page === 'series' && !this.seriesBookPage) this.settings.seriesFilterBy = val
        else if (this.page === 'collections') this.settings.collectionFilterBy = val
        else this.settings.mobileFilterBy = val
      }
    },
    orderBySetting: {
      get() {
        if (this.page === 'series' && !this.seriesBookPage) return this.settings.seriesSortBy || 'name'
        if (this.page === 'collections') return this.settings.collectionSortBy || 'name'
        if (this.page === 'authors') return this.settings.authorSortBy || 'name'
        return this.settings.mobileOrderBy || 'addedAt'
      },
      set(val) {
        if (this.page === 'series' && !this.seriesBookPage) this.settings.seriesSortBy = val
        else if (this.page === 'collections') this.settings.collectionSortBy = val
        else if (this.page === 'authors') this.settings.authorSortBy = val
        else this.settings.mobileOrderBy = val
      }
    },
    orderDescSetting: {
      get() {
        if (this.page === 'series' && !this.seriesBookPage) return !!this.settings.seriesSortDesc
        if (this.page === 'collections') return !!this.settings.collectionSortDesc
        if (this.page === 'authors') return !!this.settings.authorSortDesc
        return !!this.settings.mobileOrderDesc
      },
      set(val) {
        if (this.page === 'series' && !this.seriesBookPage) this.settings.seriesSortDesc = val
        else if (this.page === 'collections') this.settings.collectionSortDesc = val
        else if (this.page === 'authors') this.settings.authorSortDesc = val
        else this.settings.mobileOrderDesc = val
      }
    },
    orderModalItems() {
      if (this.page === 'series' && !this.seriesBookPage) {
        return [
          { text: this.$strings.LabelName, value: 'name' },
          { text: this.$strings.LabelNumberOfBooks, value: 'numBooks' },
          { text: this.$strings.LabelAddedAt, value: 'addedAt' },
          { text: this.$strings.LabelLastBookAdded, value: 'lastBookAdded' },
          { text: this.$strings.LabelLastBookUpdated, value: 'lastBookUpdated' },
          { text: this.$strings.LabelTotalDuration, value: 'totalDuration' },
          { text: this.$strings.LabelRandomly, value: 'random' }
        ]
      }
      if (this.page === 'collections') {
        return [
          { text: this.$strings.LabelName, value: 'name' },
          { text: this.$strings.LabelNumberOfBooks, value: 'numBooks' },
          { text: this.$strings.LabelCreatedAt, value: 'createdAt' },
          { text: this.$strings.LabelRandomly, value: 'random' }
        ]
      }
      if (this.page === 'authors') {
        return [
          { text: this.$strings.LabelAuthorFirstLast, value: 'name' },
          { text: this.$strings.LabelAuthorLastFirst, value: 'lastFirst' },
          { text: this.$strings.LabelNumberOfBooks, value: 'numBooks' },
          { text: this.$strings.LabelAddedAt, value: 'addedAt' }
        ]
      }
      return null
    },
    entityTitle() {
      if (this.page === 'library') {
        return this.isPodcast ? this.$strings.LabelPodcasts : this.$strings.LabelBooks
      } else if (this.page === 'playlists') {
        return this.$strings.ButtonPlaylists
      } else if (this.page === 'series') {
        return this.$strings.LabelSeries
      } else if (this.page === 'collections') {
        return this.$strings.ButtonCollections
      } else if (this.page === 'authors') {
        return this.$strings.LabelAuthors
      }
      return ''
    },
    selectedSeriesName() {
      if (this.page === 'series' && this.$route.params.id && this.$store.state.globals.series) {
        return this.$store.state.globals.series.name
      }
      return null
    },
    isPodcast() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType'] === 'podcast'
    },
    menuItems() {
      if (!this.isBookLibrary) return []

      if (this.seriesBookPage) {
        return [
          {
            text: this.$strings.LabelCollapseSeries,
            value: 'collapse_subseries',
            icon: this.settings.collapseBookSeries ? 'check_box' : 'check_box_outline_blank'
          }
        ]
      } else {
        return [
          {
            text: this.$strings.LabelCollapseSeries,
            value: 'collapse_series',
            icon: this.settings.collapseSeries ? 'check_box' : 'check_box_outline_blank'
          }
        ]
      }
    }
  },
  methods: {
    clickMenuAction(action) {
      this.showMoreMenuDialog = false
      if (action === 'collapse_series') {
        this.settings.collapseSeries = !this.settings.collapseSeries
        this.saveSettings()
      } else if (action === 'collapse_subseries') {
        this.settings.collapseBookSeries = !this.settings.collapseBookSeries
        this.saveSettings()
      }
    },
    updateOrder() {
      this.saveSettings()
    },
    updateFilter() {
      this.saveSettings()
    },
    saveSettings() {
      this.$store.dispatch('user/updateUserSettings', this.settings)
    },
    async init() {
      this.bookshelfListView = await this.$localStore.getBookshelfListView()
      this.authorsListView = await this.$localStore.getAuthorsListView()
      this.settings = { ...this.$store.state.user.settings }
      this.bookshelfReady = true
    },
    settingsUpdated(settings) {
      for (const key in settings) {
        this.settings[key] = settings[key]
      }
    },
    setTotalEntities(total) {
      this.totalEntities = total
    },
    async changeView() {
      this.bookshelfListView = !this.bookshelfListView
      await this.$hapticsImpact()
    },
    async changeAuthorsView() {
      this.authorsListView = !this.authorsListView
      await this.$hapticsImpact()
    },
    downloadSeries() {
      console.log('Download Series click')
      this.$eventBus.$emit('download-series-click')
    }
  },
  mounted() {
    this.init()
    this.$eventBus.$on('bookshelf-total-entities', this.setTotalEntities)
    this.$eventBus.$on('user-settings', this.settingsUpdated)
  },
  beforeDestroy() {
    this.$eventBus.$off('bookshelf-total-entities', this.setTotalEntities)
    this.$eventBus.$off('user-settings', this.settingsUpdated)
  }
}
</script>

<style>
#bookshelf-toolbar {
  box-shadow: 0px 5px 5px #11111155;
}
</style>
