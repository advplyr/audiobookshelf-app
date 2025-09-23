<template>
  <div class="w-full h-12 bg-surface-container relative z-20 shadow-elevation-1">
    <div id="bookshelf-toolbar" class="absolute top-0 left-0 w-full h-full z-20 flex items-center px-4">
      <div class="flex items-center w-full">
        <p v-show="!selectedSeriesName" class="text-body-medium text-on-surface">{{ $formatNumber(totalEntities) }} {{ entityTitle }}</p>
        <p v-show="selectedSeriesName" class="text-body-medium text-on-surface">{{ selectedSeriesName }} ({{ $formatNumber(totalEntities) }})</p>
        <div class="flex-grow" />

        <!-- Filter Button -->
        <div v-if="page === 'library'" class="relative mx-1">
          <ui-icon-btn icon="filter_alt" variant="standard" size="medium" @click="showFilterModal = true" />
          <div v-show="hasFilters" class="absolute top-0 -right-1 w-3 h-3 rounded-full bg-tertiary shadow-elevation-1 z-10 pointer-events-none" />
        </div>

        <!-- Sort Button -->
        <ui-icon-btn v-if="page === 'library'" icon="sort" variant="standard" size="medium" class="mx-1" @click="showSortModal = true" />

        <!-- Download Series Button -->
        <ui-icon-btn v-if="seriesBookPage" icon="download" variant="standard" size="medium" class="mx-1" @click="downloadSeries" />

        <!-- More Menu Button -->
        <ui-icon-btn v-if="(page == 'library' && isBookLibrary) || seriesBookPage" icon="more_vert" variant="standard" size="medium" class="mx-1" @click="showMoreMenuDialog = true" />
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
      if (this.page === 'library') {
        return this.isPodcast ? this.$strings.LabelPodcasts : this.$strings.LabelBooks
      } else if (this.page === 'playlists') {
        return this.$strings.ButtonPlaylists
      } else if (this.page === 'series') {
        return this.$strings.LabelSeries
      } else if (this.page === 'collections') {
        return this.$strings.ButtonCollections
      } else if (this.page === 'collections-playlists') {
        return this.$strings.ButtonCollections + ' & ' + this.$strings.ButtonPlaylists
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

<style scoped>
/* Material 3 Toolbar Styles */
#bookshelf-toolbar {
  box-shadow: var(--md-sys-elevation-level1);
}
</style>
