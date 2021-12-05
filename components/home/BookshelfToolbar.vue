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
      isListView: false,
      totalEntities: 0
    }
  },
  computed: {
    hasFilters() {
      return this.$store.getters['user/getUserSetting']('mobileFilterBy') !== 'all'
    },
    page() {
      var routeName = this.$route.name || ''
      return routeName.split('-')[1]
    },
    routeQuery() {
      return this.$route.query || {}
    },
    entityTitle() {
      if (this.page === 'library') return 'Books'
      else if (this.page === 'series') {
        return 'Series'
      } else if (this.page === 'collections') {
        return 'Collections'
      }
      return ''
    },
    selectedSeriesName() {
      if (this.page === 'series' && this.$route.params.id) {
        return this.$decode(this.$route.params.id)
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
      this.$store.commit('setBookshelfView', bookshelfView)
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
      this.$store.commit('setBookshelfView', bookshelfView)
    },
    settingsUpdated(settings) {
      for (const key in settings) {
        this.settings[key] = settings[key]
      }
    },
    setTotalEntities(total) {
      this.totalEntities = total
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