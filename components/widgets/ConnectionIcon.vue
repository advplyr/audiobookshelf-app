<template>
  <div>
    <nuxt-link v-if="isConnected" to="/account" class="p-2 bg-white bg-opacity-10 border border-white border-opacity-40 rounded-full h-11 w-11 flex items-center justify-center">
      <span class="material-icons">person</span>
    </nuxt-link>
    <div v-else-if="processing" class="relative p-2 bg-warning bg-opacity-10 border border-warning border-opacity-40 rounded-full h-11 w-11 flex items-center justify-center">
      <div class="loader-dots block relative w-10 h-2.5">
        <div class="absolute top-0 mt-0.5 w-1.5 h-1.5 rounded-full bg-warning"></div>
        <div class="absolute top-0 mt-0.5 w-1.5 h-1.5 rounded-full bg-warning"></div>
        <div class="absolute top-0 mt-0.5 w-1.5 h-1.5 rounded-full bg-warning"></div>
        <div class="absolute top-0 mt-0.5 w-1.5 h-1.5 rounded-full bg-warning"></div>
      </div>
    </div>
    <nuxt-link v-else to="/connect" class="relative p-2 bg-warning bg-opacity-10 border border-warning border-opacity-40 rounded-full h-11 w-11 flex items-center justify-center">
      <span class="material-icons">{{ networkIcon }}</span>
      <!-- <div class="absolute top-0 left-0"> -->
      <!-- <div class="absolute -top-5 -right-5 overflow-hidden">
          <svg class="w-20 h-20 animate-spin" viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg">
            <path clip-rule="evenodd" d="M15.165 8.53a.5.5 0 01-.404.58A7 7 0 1023 16a.5.5 0 011 0 8 8 0 11-9.416-7.874.5.5 0 01.58.404z" fill="currentColor" fill-rule="evenodd" />
          </svg>
        </div> -->
    </nuxt-link>
  </div>
</template>

<script>
export default {
  props: {},
  data() {
    return {
      processing: false,
      serverUrl: null,
      isConnected: false
    }
  },
  watch: {
    networkConnected(newVal) {
      if (newVal) {
        this.init()
      }
    }
  },
  computed: {
    user() {
      return this.$store.state.user.user
    },
    networkIcon() {
      if (!this.networkConnected) return 'signal_wifi_connected_no_internet_4'
      return 'cloud_off'
    },
    networkConnected() {
      return this.$store.state.networkConnected
    }
  },
  methods: {
    socketConnected(val) {
      this.processing = false
      this.isConnected = val
    },
    async init() {
      if (this.isConnected) {
        return
      }

      if (!this.$server) {
        console.error('Invalid server not initialized')
        return
      }

      if (!this.networkConnected) return

      var localServerUrl = await this.$localStore.getServerUrl()
      var localUserToken = await this.$localStore.getToken()
      if (localServerUrl) {
        this.serverUrl = localServerUrl

        // Server and Token are stored
        if (localUserToken) {
          this.processing = true
          var isSocketAlreadyEstablished = this.$server.socket
          var success = await this.$server.connect(localServerUrl, localUserToken)
          if (!success && !this.$server.url) {
            this.processing = false
            this.serverUrl = null
          } else if (!success) {
            this.processing = false
          } else if (isSocketAlreadyEstablished) {
            // No need to wait for connect event
            this.processing = false
          }
        } else {
          // Server only is stored
          var success = await this.$server.check(this.serverUrl)
          if (!success) {
            console.error('Invalid server')
            this.$server.setServerUrl(null)
          }
        }
      }
    }
  },
  mounted() {
    if (!this.$server) {
      console.error('Server not initalized in connection icon')
      return
    }
    if (this.$server.connected) {
      this.isConnected = true
    }
    this.$server.on('connected', this.socketConnected)
    this.init()
  },
  beforeDestroy() {
    if (this.$server) this.$server.off('connected', this.socketConnected)
  }
}
</script>