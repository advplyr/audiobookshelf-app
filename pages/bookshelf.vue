<template>
  <div class="w-full h-full">
    <home-bookshelf-nav-bar />
    <home-bookshelf-toolbar v-show="!isHome" />
    <div id="bookshelf-wrapper" class="main-content overflow-y-auto overflow-x-hidden relative" :class="isHome ? 'home-page' : ''">
      <nuxt-child />

      <!-- <div v-if="isLoading" class="absolute top-0 left-0 w-full h-full flex items-center justify-center">
        <ui-loading-indicator />
      </div> -->
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {}
  },
  computed: {
    isHome() {
      return this.$route.name === 'bookshelf'
    },
    currentLibrary() {
      return this.$store.getters['libraries/getCurrentLibrary']
    },
    currentLibraryName() {
      return this.currentLibrary ? this.currentLibrary.name : 'Main'
    },
    isSocketConnected() {
      return this.$store.state.socketConnected
    }
  },
  methods: {
    async loadCollections() {
      this.$store.dispatch('user/loadUserCollections')
    },
    socketConnected(isConnected) {
      // if (isConnected) {
      //   console.log('Connected - Load from server')
      // this.loadAudiobooks()
      //   if (this.$route.name === 'bookshelf-collections') this.loadCollections()
      // } else {
      //   console.log('Disconnected - Reset to local storage')
      //   this.$store.commit('audiobooks/reset')
      //   this.$store.dispatch('audiobooks/useDownloaded')
      // }
    }
  },
  mounted() {
    this.$server.on('connected', this.socketConnected)
  },
  beforeDestroy() {
    this.$server.off('connected', this.socketConnected)
  }
}
</script>

<style>
.main-content {
  max-height: calc(100% - 72px);
  min-height: calc(100% - 72px);
  max-width: 100vw;
}
.main-content.home-page {
  max-height: calc(100% - 36px);
  min-height: calc(100% - 36px);
}
</style>