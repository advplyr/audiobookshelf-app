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
    isCellular() {
      return this.networkConnectionType === 'cellular'
    },
    icon() {
      if (!this.user) return null // hide when not connected to server

      if (!this.networkConnected) {
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
      else if (this.isCellular) return 'text-gray-200'
      else return 'text-success'
    }
  },
  methods: {
    showAlertDialog() {
      var msg = ''
      if (!this.networkConnected) {
        msg = 'No internet'
      } else if (!this.socketConnected) {
        msg = 'Socket not connected'
      } else if (this.isCellular) {
        msg = 'Socket connected over cellular'
      } else {
        msg = 'Socket connected over wifi'
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