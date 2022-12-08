<template>
  <div class="w-full h-9 bg-bg relative z-20">
    <div id="bookshelf-toolbar" class="absolute top-0 left-0 w-full h-full z-20 flex items-center px-2">
      <div class="flex items-center w-full text-sm">
        <nuxt-link to="/bookshelf/series" v-if="selectedSeriesName" class="pt-1">
          <span class="material-icons">arrow_back</span>
        </nuxt-link>
        <p v-show="!selectedSeriesName" class="font-book pt-1">{{ totalEntities }} {{ entityTitle }}</p>
        <p v-show="selectedSeriesName" class="ml-2 font-book pt-1">{{ selectedSeriesName }} ({{ totalEntities }})</p>
        <div class="flex-grow" />
        <span v-if="page == 'library' || seriesBookPage" class="material-icons px-2" @click="changeView()">{{ !bookshelfListView ? 'view_list' : 'grid_view' }}</span>
        <template v-if="page === 'library'">
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
import { Haptics, ImpactStyle } from '@capacitor/haptics';

export default {
  data() {
    return {
      showSortModal: false,
      showFilterModal: false,
      settings: {},
      totalEntities: 0
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
      if (this.isPodcast) return 'Podcasts'
      if (this.page === 'library') return 'Books'
      else if (this.page === 'series') {
        return 'Series'
      } else if (this.page === 'collections') {
        return 'Collections'
      } else if (this.page === 'playlists') {
        return 'Playlists'
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
    }
  },
  methods: {
    updateOrder() {
      this.saveSettings()
    },
    updateFilter() {
      this.saveSettings()
    },
    saveSettings() {
      this.$store.commit('user/setSettings', this.settings) // Immediate update
      this.$store.dispatch('user/updateUserSettings', this.settings) // TODO: No need to update settings on server...
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
      await Haptics.impact({ style: ImpactStyle.Medium });
    }
  },
  mounted() {
    this.init()
    this.$eventBus.$on('bookshelf-total-entities', this.setTotalEntities)
    this.$store.commit('user/addSettingsListener', { id: 'bookshelftoolbar', meth: this.settingsUpdated })
  },
  beforeDestroy() {
    this.$eventBus.$off('bookshelf-total-entities', this.setTotalEntities)
    this.$store.commit('user/removeSettingsListener', 'bookshelftoolbar')
  }
}
</script>

<style>
#bookshelf-toolbar {
  box-shadow: 0px 5px 5px #11111155;
}
</style>
