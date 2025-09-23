<template>
  <modals-modal v-model="show" width="90%" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" data-modal-backdrop>
      <div ref="container" class="w-full overflow-x-hidden overflow-y-auto bg-surface rounded-2xl border border-outline-variant shadow-elevation-4 backdrop-blur-md mt-8" style="max-height: 75%">
        <!-- Material 3 Modal Header -->
        <div class="px-6 py-4 border-b border-outline-variant">
          <div class="w-full flex items-center">
            <ui-icon-btn icon="close" size="sm" class="mr-2" @click="show = false" />
            <h2 class="text-headline-small text-on-surface font-medium">{{ $strings.HeaderCastDevices }}</h2>
          </div>
        </div>

        <div class="w-full max-h-96 overflow-y-auto px-6 py-4">
          <!-- Loading state -->
          <div v-if="loading" class="flex items-center justify-center py-8">
            <ui-loading-indicator />
            <span class="ml-2 text-on-surface">{{ $strings.MessageDiscoveringCastDevices }}</span>
          </div>

          <!-- No devices found -->
          <div v-else-if="!castDevices.length" class="text-center py-8">
            <ui-icon icon="cast" size="3xl" class="text-on-surface-variant mb-4" />
            <p class="text-on-surface mb-2">{{ $strings.MessageNoCastDevicesFound }}</p>
            <p class="text-sm text-on-surface-variant">{{ $strings.MessageEnsureCastDevicesOnNetwork }}</p>
            <ui-btn size="sm" color="primary" class="mt-4" @click="refreshDevices">
              <ui-icon icon="refresh" class="mr-1" />
              {{ $strings.ButtonRefresh }}
            </ui-btn>
          </div>

          <!-- Device list -->
          <div v-else class="space-y-2">
            <div
              v-for="device in castDevices"
              :key="device.id"
              class="flex items-center justify-between p-3 border border-outline-variant rounded-lg transition-colors"
              :class="{
                'bg-primary-container border-primary': device.isConnected,
                'hover:bg-surface-variant cursor-pointer': !device.isConnected
              }"
              @click="!device.isConnected && connectToDevice(device)"
            >
              <div class="flex items-center flex-1">
                <ui-icon :icon="device.isConnected ? 'cast_connected' : 'cast'" :class="device.isConnected ? 'text-on-primary-container' : 'text-on-surface'" class="mr-3" />
                <div>
                  <p class="font-medium" :class="device.isConnected ? 'text-on-primary-container' : 'text-on-surface'">{{ device.name }}</p>
                  <p v-if="device.description" class="text-sm" :class="device.isConnected ? 'text-on-primary-container' : 'text-on-surface-variant'">{{ device.description }}</p>
                  <p v-if="device.isConnected" class="text-xs font-medium text-on-primary-container">{{ $strings.LabelConnected }}</p>
                </div>
              </div>

              <!-- Disconnect button for connected devices -->
              <div v-if="device.isConnected" class="ml-2">
                <ui-btn size="sm" color="error" variant="outlined" @click="disconnectFromDevice(device)" :loading="connectingDeviceId === device.id">
                  {{ $strings.ButtonDisconnect }}
                </ui-btn>
              </div>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="px-6 py-4 border-t border-outline-variant">
          <div class="flex items-center justify-between">
            <!-- Only show refresh button when devices are available or loading -->
            <ui-btn v-if="castDevices.length > 0" size="sm" @click="refreshDevices" :loading="loading">
              <ui-icon icon="refresh" class="mr-1" />
              {{ $strings.ButtonRefresh }}
            </ui-btn>
            <div v-else></div>
            <!-- Empty div to maintain layout -->
            <ui-btn size="sm" @click="show = false">
              {{ $strings.ButtonClose }}
            </ui-btn>
          </div>
        </div>
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  name: 'CastDeviceSelectionModal',
  data() {
    return {
      show: false,
      loading: false,
      castDevices: [],
      connectingDeviceId: null
    }
  },
  methods: {
    init() {
      this.show = true
      this.refreshDevices()
    },
    async refreshDevices() {
      this.loading = true
      try {
        const response = await this.$nativeHttp.getCastDevices()
        if (response?.devices) {
          this.castDevices = response.devices
        } else {
          this.castDevices = []
        }
      } catch (error) {
        console.error('Failed to get cast devices:', error)
        this.$toast.error(this.$strings.ToastCastDeviceDiscoveryFailed)
        this.castDevices = []
      }
      this.loading = false
    },
    async connectToDevice(device) {
      if (device.isConnected) {
        // Don't attempt to reconnect to already connected device when clicking the main area
        return
      }

      this.connectingDeviceId = device.id
      try {
        await this.$nativeHttp.connectToCastDevice(device.id)
        this.$toast.success(this.$strings.ToastCastDeviceConnected.replace('{0}', device.name))

        // Emit connection event to parent components first
        this.$emit('cast-device-connected', device)

        // Brief delay to prevent modal flashing, then close
        setTimeout(() => {
          this.show = false
        }, 500)
      } catch (error) {
        console.error('Failed to connect to cast device:', error)
        this.$toast.error(this.$strings.ToastCastDeviceConnectionFailed.replace('{0}', device.name))
      }
      this.connectingDeviceId = null
    },
    async disconnectFromDevice(device) {
      try {
        await this.$nativeHttp.disconnectFromCastDevice()
        this.$toast.success(this.$strings.ToastCastDeviceDisconnected.replace('{0}', device.name))

        // Emit disconnection event to parent components
        this.$emit('cast-device-disconnected', device)

        // Refresh device list to update connection states after disconnect
        await this.refreshDevices()
      } catch (error) {
        console.error('Failed to disconnect from cast device:', error)
        this.$toast.error(this.$strings.ToastCastDeviceDisconnectionFailed.replace('{0}', device.name))
      }
    }
  }
}
</script>

<style scoped>
/* Custom styles can be added here if needed */
</style>
