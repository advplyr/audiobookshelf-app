<template>
  <modals-modal v-model="show" :width="320" height="100%">
    <template #outer>
      <div class="absolute top-8 left-4 z-40">
        <p class="text-white text-2xl truncate">{{ $strings.HeaderSelectSpeaker || 'Select Speaker' }}</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-border" style="max-height: 75%" @click.stop>
        <div v-if="devices.length === 0" class="p-6 text-center">
          <span class="material-symbols text-4xl text-fg-muted mb-2">speaker_group</span>
          <p class="text-fg-muted">{{ $strings.MessageNoDevicesFound || 'No speakers found' }}</p>
          <ui-btn class="mt-4" small @click="rescan">{{ $strings.ButtonRescan || 'Scan Again' }}</ui-btn>
        </div>

        <div v-else>
          <ul class="w-full" role="listbox">
            <li
              v-for="device in devices"
              :key="device.id"
              class="text-fg select-none relative py-4 px-4 hover:bg-bg cursor-pointer border-b border-border last:border-b-0"
              :class="{ 'bg-success/20': connectedDeviceId === device.id }"
              role="option"
              @click="selectDevice(device)"
            >
              <div class="flex items-center">
                <span class="material-symbols text-2xl mr-3">speaker</span>
                <div class="flex-1 min-w-0">
                  <p class="font-medium truncate">{{ device.name }}</p>
                  <p v-if="device.manufacturer" class="text-xs text-fg-muted truncate">{{ device.manufacturer }}</p>
                </div>
                <span v-if="connectedDeviceId === device.id" class="material-symbols text-success">check_circle</span>
              </div>
            </li>
          </ul>

          <div class="p-4 border-t border-border flex gap-2">
            <ui-btn v-if="connectedDeviceId" class="flex-1" color="error" small @click="disconnect">
              {{ $strings.ButtonDisconnect || 'Disconnect' }}
            </ui-btn>
            <ui-btn v-if="!connectedDeviceId" class="flex-1" small :disabled="isRescanning" @click="rescan">
              <span v-if="isRescanning" class="material-symbols animate-spin text-sm mr-1">refresh</span>
              {{ $strings.ButtonRescan || 'Rescan' }}
            </ui-btn>
          </div>
        </div>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'

export default {
  props: {
    value: Boolean
  },
  data() {
    return {
      isRescanning: false
    }
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    devices() {
      return this.$store.state.dlnaDevices || []
    },
    connectedDeviceId() {
      return this.$store.state.connectedDlnaDevice?.id || null
    }
  },
  methods: {
    async rescan() {
      this.isRescanning = true
      await AbsAudioPlayer.stopDlnaDiscovery()
      await AbsAudioPlayer.startDlnaDiscovery()
      setTimeout(() => {
        this.isRescanning = false
      }, 3000)
    },
    async selectDevice(device) {
      if (this.connectedDeviceId === device.id) {
        return
      }

      await this.$hapticsImpact()
      
      try {
        const result = await AbsAudioPlayer.connectDlnaDevice({ deviceId: device.id })
        if (result.success) {
          this.$store.commit('setConnectedDlnaDevice', device)
          this.show = false
        }
      } catch (error) {
        console.error('Failed to connect to device:', error)
        this.$toast.error('Failed to connect to speaker')
      }
    },
    async disconnect() {
      await this.$hapticsImpact()
      await AbsAudioPlayer.disconnectDlnaDevice()
      this.$store.commit('clearDlnaConnection')
    }
  }
}
</script>
