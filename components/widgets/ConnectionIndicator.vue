<template>
  <div v-if="icon" class="flex h-full items-center px-2">
    <span class="material-icons-outlined text-lg" :class="iconClass" @click="showAlertDialog">{{ icon }}</span>
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {}
  },
  computed: {
    user() {
      return this.$store.state.user.user
    },
    socketConnected() {
      return this.$store.state.socketConnected
    },
    networkConnected() {
      return this.$store.state.networkConnected
    },
    networkConnectionType() {
      return this.$store.state.networkConnectionType
    },
    isNetworkUnmetered() {
      return this.$store.state.isNetworkUnmetered
    },
    isCellular() {
      return this.networkConnectionType === 'cellular'
    },
    attemptingConnection() {
      return this.$store.state.attemptingConnection
    },
    icon() {
      if (!this.user && !this.attemptingConnection) return null // hide when not connected to server

      if (this.attemptingConnection) {
        return 'cloud_sync'
      } else if (!this.networkConnected) {
        return 'wifi_off'
      } else if (!this.socketConnected) {
        return 'cloud_off'
      } else if (this.isCellular) {
        return 'signal_cellular_alt'
      } else {
        return 'cloud_done'
      }
    },
    iconClass() {
      if (!this.networkConnected) return 'text-error'
      else if (!this.socketConnected) return 'text-warning'
      else if (!this.isNetworkUnmetered) return 'text-yellow-400'
      else if (this.isCellular) return 'text-gray-200'
      else return 'text-success'
    }
  },
  methods: {
    showAlertDialog() {
      var msg = ''
      var meteredString = this.isNetworkUnmetered ? 'unmetered' : 'metered'
      if (this.attemptingConnection) {
        msg = 'Attempting server connection'
      } else if (!this.networkConnected) {
        msg = 'No internet'
      } else if (!this.socketConnected) {
        msg = 'Socket not connected'
      } else if (this.isCellular) {
        msg = `Socket connected over ${meteredString} cellular`
      } else {
        msg = `Socket connected over ${meteredString} wifi`
      }
      Dialog.alert({
        title: 'Connection Status',
        message: msg
      })
    }
  },
  mounted() {},
  beforeDestroy() {}
}
</script>