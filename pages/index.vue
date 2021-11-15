<template>
  <div class="w-full h-full">
    <div class="w-full h-12 relative z-20">
      <div id="toolbar" class="asolute top-0 left-0 w-full h-full bg-bg flex items-center px-2">
        <span class="material-icons px-2" @click="showSearchModal = true">search</span>
        <p class="font-book">{{ numAudiobooks }} Audiobooks</p>

        <div class="flex-grow" />
        <span class="material-icons px-2" @click="changeView">{{ viewIcon }}</span>
        <div class="relative flex items-center px-2">
          <span class="material-icons" @click="showFilterModal = true">filter_alt</span>
          <div v-show="hasFilters" class="absolute top-0 right-2 w-2 h-2 rounded-full bg-success border border-green-300 shadow-sm z-10 pointer-events-none" />
        </div>
        <span class="material-icons px-2" @click="showSortModal = true">sort</span>
      </div>
    </div>
    <template v-if="bookshelfReady">
      <app-bookshelf v-if="!isListView" />
      <app-bookshelf-list v-else />
    </template>

    <modals-order-modal v-model="showSortModal" :order-by.sync="settings.mobileOrderBy" :descending.sync="settings.mobileOrderDesc" @change="updateOrder" />
    <modals-filter-modal v-model="showFilterModal" :filter-by.sync="settings.mobileFilterBy" @change="updateFilter" />
    <modals-search-modal v-model="showSearchModal" />
  </div>
</template>

<script>
export default {
  asyncData({ redirect }) {
    return redirect('/bookshelf')
  },
  data() {
    return {
      showSortModal: false,
      showFilterModal: false,
      showSearchModal: false,
      settings: {},
      isListView: false,
      bookshelfReady: false
    }
  },
  computed: {
    hasFilters() {
      return this.$store.getters['user/getUserSetting']('filterBy') !== 'all'
    },
    numAudiobooks() {
      return this.$store.getters['audiobooks/getFiltered']().length
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
#toolbar {
  box-shadow: 0px 5px 5px #11111155;
}
</style>