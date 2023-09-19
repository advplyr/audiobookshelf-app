<template>
  <div class="w-full h-9 bg-bg relative z-20">
    <div id="bookshelf-toolbar" class="absolute top-0 left-0 w-full h-full z-20 flex items-center px-2">
      <div class="flex items-center w-full text-sm">
        <p v-show="!selectedSeriesName" class="pt-1">{{ totalEntities }} {{ entityTitle }}</p>
        <p v-show="selectedSeriesName" class="ml-2 pt-1">{{ selectedSeriesName }} ({{ totalEntities }})</p>
        <div class="flex-grow" />
        <span v-if="page == 'library' || seriesBookPage" class="material-icons px-2" @click="changeView">{{ !bookshelfListView ? 'view_list' : 'grid_view' }}</span>
        <template v-if="page === 'library'">
          <div class="relative flex items-center px-2">
            <span class="material-icons" @click="showFilterModal = true">filter_alt</span>
            <div v-show="hasFilters" class="absolute top-0 right-2 w-2 h-2 rounded-full bg-success border border-green-300 shadow-sm z-10 pointer-events-none" />
          </div>
          <span class="material-icons px-2" @click="showSortModal = true">sort</span>
        </template>
        <span v-if="(page == 'library' && isBookLibrary) || seriesBookPage" class="material-icons px-2" @click="showMoreMenuDialog = true">more_vert</span>
      </div>
    </div>

    <modals-order-modal v-model="showSortModal" :order-by.sync="settings.mobileOrderBy" :descending.sync="settings.mobileOrderDesc" @change="updateOrder" />
    <modals-filter-modal v-model="showFilterModal" :filter-by.sync="settings.mobileFilterBy" @change="updateFilter" />
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
    currentLibraryMediaType() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType']
    },
    isBookLibrary() {
      return this.currentLibraryMediaType === 'book'
    },
    hasFilters() {
      return this.$store.getters['user/getUserSetting']('mobileFilterBy') !== 'all'
    },
    page() {
      var routeName = this.$route.name || ''
      return routeName.split('-')[1]
    },
    seriesBookPage() {
      return this.$route.name == 'bookshelf-series-id'
    },
    routeQuery() {
      return this.$route.query || {}
    },
    entityTitle() {
      if (this.isPodcast) return this.$strings.LabelPodcasts
      if (this.page === 'library') return this.$strings.LabelBooks
      else if (this.page === 'series') {
        return this.$strings.LabelSeries
      } else if (this.page === 'collections') {
        return this.$strings.LabelCollections
      } else if (this.page === 'playlists') {
        return this.$strings.LabelPlaylists
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
            text: this.$strings.MenuCollapseSubSeries,
            value: 'collapse_subseries',
            icon: this.settings.collapseBookSeries ? 'check_box' : 'check_box_outline_blank'
          }
        ]
      } else {
        return [
          {
            text: this.$strings.MenuCollapseSeries,
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
