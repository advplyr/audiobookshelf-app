<template>
  <div id="bookshelf" ref="wrapper" class="w-full overflow-y-auto">
    <template v-for="(ab, index) in audiobooks">
      <div :key="index" class="border-b border-opacity-10 w-full bookshelfRow py-4 px-2 flex relative">
        <app-bookshelf-list-row :audiobook="ab" :card-width="cardWidth" :page-width="pageWidth" />
        <div class="bookshelfDivider h-4 w-full absolute bottom-0 left-0 right-0 z-10" />
      </div>
    </template>
    <div v-show="!audiobooks.length" class="w-full py-16 text-center text-xl">
      <div class="py-4">No Audiobooks</div>
      <ui-btn v-if="hasFilters" @click="clearFilter">Clear Filter</ui-btn>
    </div>
    <div v-show="isLoading" class="absolute top-0 left-0 w-full h-full flex items-center justify-center bg-black bg-opacity-70 z-20">
      <div class="py-4">Loading...</div>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      currFilterOrderKey: null,
      pageWidth: 0,
      audiobooks: []
    }
  },
  computed: {
    isLoading() {
      return this.$store.state.audiobooks.isLoading
    },
    cardWidth() {
      return 75
    },
    cardHeight() {
      return this.cardWidth * 2
    },
    contentRowWidth() {
      return this.pageWidth - 16 - this.cardWidth
    },
    filterOrderKey() {
      return this.$store.getters['user/getFilterOrderKey']
    },
    hasFilters() {
      return this.$store.getters['user/getUserSetting']('mobileFilterBy') !== 'all'
    }
  },
  methods: {
    clearFilter() {
      this.$store.dispatch('user/updateUserSettings', {
        mobileFilterBy: 'all'
      })
    },
    calcShelves() {},
    audiobooksUpdated() {
      this.calcShelves()
    },
    init() {
      if (this.$refs.wrapper) {
        this.pageWidth = this.$refs.wrapper.clientWidth
        this.calcShelves()
      }
    },
    resize() {
      this.init()
    },
    settingsUpdated() {
      if (this.currFilterOrderKey !== this.filterOrderKey) {
        this.calcShelves()
      }
    },
    async loadAudiobooks() {
      var currentLibrary = await this.$localStore.getCurrentLibrary()
      if (currentLibrary) {
        this.$store.commit('libraries/setCurrentLibrary', currentLibrary.id)
      }
    },
    socketConnected(isConnected) {
      if (isConnected) {
        console.log('Connected - Load from server')
        this.loadAudiobooks()
      } else {
        console.log('Disconnected - Reset to local storage')
        this.$store.commit('audiobooks/reset')
        this.$store.dispatch('audiobooks/useDownloaded')
        // this.calcShelves()
        // this.$store.dispatch('downloads/loadFromStorage')
      }
    }
  },
  mounted() {
    this.$store.commit('audiobooks/addListener', { id: 'bookshelf', meth: this.audiobooksUpdated })
    this.$store.commit('user/addSettingsListener', { id: 'bookshelf', meth: this.settingsUpdated })
    window.addEventListener('resize', this.resize)

    if (!this.$server) {
      console.error('Bookshelf mounted no server')
      return
    }

    this.$server.on('connected', this.socketConnected)
    if (this.$server.connected) {
      this.loadAudiobooks()
    } else {
      console.log('Bookshelf - Server not connected using downloaded')
    }
    this.init()
  },
  beforeDestroy() {
    this.$store.commit('audiobooks/removeListener', 'bookshelf')
    this.$store.commit('user/removeSettingsListener', 'bookshelf')
    window.removeEventListener('resize', this.resize)

    if (!this.$server) {
      console.error('Bookshelf beforeDestroy no server')
      return
    }
    this.$server.off('connected', this.socketConnected)
  }
}
</script>

<style>
#bookshelf {
  height: calc(100% - 48px);
}
.bookshelfRow {
  background-image: url(/wood_panels.jpg);
}
.bookshelfDivider {
  background: rgb(149, 119, 90);
  background: linear-gradient(180deg, rgba(149, 119, 90, 1) 0%, rgba(103, 70, 37, 1) 17%, rgba(103, 70, 37, 1) 88%, rgba(71, 48, 25, 1) 100%);
  box-shadow: 2px 14px 8px #111111aa;
}
</style>