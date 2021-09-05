<template>
  <div class="w-full h-full">
    <div class="w-full h-12 relative z-20">
      <div id="toolbar" class="asolute top-0 left-0 w-full h-full bg-bg flex items-center px-2">
        <span class="material-icons px-2" @click="showSearchModal = true">search</span>
        <p class="font-book">{{ numAudiobooks }} Audiobooks</p>

        <div class="flex-grow" />
        <span class="material-icons px-2" @click="showFilterModal = true">filter_alt</span>
        <span class="material-icons px-2" @click="showSortModal = true">sort</span>
      </div>
    </div>
    <app-bookshelf />

    <modals-order-modal v-model="showSortModal" :order-by.sync="settings.orderBy" :descending.sync="settings.orderDesc" @change="updateOrder" />
    <modals-filter-modal v-model="showFilterModal" :filter-by.sync="settings.filterBy" @change="updateFilter" />
    <modals-search-modal v-model="showSearchModal" />
  </div>
</template>

<script>
export default {
  data() {
    return {
      showSortModal: false,
      showFilterModal: false,
      showSearchModal: false,
      settings: {}
    }
  },
  computed: {
    numAudiobooks() {
      return this.$store.getters['audiobooks/getFiltered']().length
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
      this.$store.dispatch('user/updateUserSettings', this.settings)
    },
    init() {
      this.settings = { ...this.$store.state.user.settings }
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