<template>
  <div class="w-full h-full">
    <home-bookshelf-nav-bar />
    <home-bookshelf-toolbar v-show="!isHome" />
    <div class="main-content overflow-y-auto overflow-x-hidden relative" :class="isHome ? 'home-page' : ''">
      <nuxt-child />

      <div v-if="isLoading" class="absolute top-0 left-0 w-full h-full flex items-center justify-center">
        <ui-loading-indicator />
      </div>
      <div v-else-if="!audiobooks.length" class="absolute top-0 left-0 w-full h-full flex items-center justify-center">
        <div>
          <p class="mb-4 text-center text-xl">
            Bookshelf empty<span v-show="isSocketConnected">
              for library <strong>{{ currentLibraryName }}</strong></span
            >
          </p>
          <div class="w-full" v-if="!isSocketConnected">
            <div class="flex justify-center items-center mb-3">
              <span class="material-icons text-error text-lg">cloud_off</span>
              <p class="pl-2 text-error text-sm">Audiobookshelf server not connected.</p>
            </div>
            <p class="px-4 text-center text-error absolute bottom-12 left-0 right-0 mx-auto"><strong>Important!</strong> This app requires that you are running <u>your own server</u> and does not provide any content.</p>
          </div>
          <div class="flex justify-center">
            <ui-btn v-if="!isSocketConnected" small @click="$router.push('/connect')" class="w-32"> Connect </ui-btn>
          </div>
        </div>
      </div>
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
    isLoading() {
      return this.$store.state.audiobooks.isLoading
    },
    audiobooks() {
      return this.$store.state.audiobooks.audiobooks
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
    async loadAudiobooks() {
      var currentLibrary = await this.$localStore.getCurrentLibrary()
      if (currentLibrary) {
        this.$store.commit('libraries/setCurrentLibrary', currentLibrary.id)
      }
      this.$store.dispatch('audiobooks/load')
    },
    async loadCollections() {
      this.$store.dispatch('user/loadUserCollections')
    },
    socketConnected(isConnected) {
      if (isConnected) {
        console.log('Connected - Load from server')
        this.loadAudiobooks()
        if (this.$route.name === 'bookshelf-collections') this.loadCollections()
      } else {
        console.log('Disconnected - Reset to local storage')
        this.$store.commit('audiobooks/reset')
        this.$store.dispatch('audiobooks/useDownloaded')
      }
    }
  },
  mounted() {
    this.$server.on('connected', this.socketConnected)
    if (this.$server.connected) {
      this.loadAudiobooks()
    } else {
      console.log('Bookshelf - Server not connected using downloaded')
    }
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
}
.main-content.home-page {
  max-height: calc(100% - 36px);
  min-height: calc(100% - 36px);
}
</style>