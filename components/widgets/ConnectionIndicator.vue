<template>
  <div v-if="icon" class="flex h-full items-center px-2">
    <span role="img" :aria-label="iconLabel" class="material-symbols text-lg" :class="iconClass" @click="showAlertDialog">{{ icon }}</span>
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
    iconLabel() {
      if (this.attemptingConnection) {
        return this.$strings.MessageAttemptingServerConnection
      } else if (!this.networkConnected) {
        return this.$strings.MessageNoNetworkConnection
      } else if (!this.socketConnected) {
        return this.$strings.MessageSocketNotConnected
      } else if (this.isCellular) {
        return this.isNetworkUnmetered ? this.$strings.MessageSocketConnectedOverUnmeteredCellular : this.$strings.MessageSocketConnectedOverMeteredCellular
      } else {
        return this.isNetworkUnmetered ? this.$strings.MessageSocketConnectedOverUnmeteredWifi : this.$strings.MessageSocketConnectedOverMeteredWifi
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
      if (this.attemptingConnection) {
        msg = this.$strings.MessageAttemptingServerConnection
      } else if (!this.networkConnected) {
        msg = this.$strings.MessageNoNetworkConnection
      } else if (!this.socketConnected) {
        msg = this.$strings.MessageSocketNotConnected
      } else if (this.isCellular) {
        msg = this.isNetworkUnmetered ? this.$strings.MessageSocketConnectedOverUnmeteredCellular : this.$strings.MessageSocketConnectedOverMeteredCellular
      } else {
        msg = this.isNetworkUnmetered ? this.$strings.MessageSocketConnectedOverUnmeteredWifi : this.$strings.MessageSocketConnectedOverMeteredWifi
      }
      Dialog.alert({
        title: this.$strings.HeaderConnectionStatus,
        message: msg
      })
    }
  },
  mounted() {},
  beforeDestroy() {}
}
</script>
