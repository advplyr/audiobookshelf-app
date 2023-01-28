<template>
  <div class="w-full h-full px-8 pt-8 pb-48 overflow-y-auto">
    <!-- Display settings -->
    <p class="uppercase text-xs font-semibold text-gray-300 mb-2">User Interface Settings</p>
    <div class="flex items-center py-3" @click="toggleEnableAltView">
      <div class="w-10 flex justify-center">
        <ui-toggle-switch v-model="settings.enableAltView" @input="saveSettings" />
      </div>
      <p class="pl-4">Alternative bookshelf view</p>
    </div>
    <div class="flex items-center py-3" @click.stop="toggleLockOrientation">
      <div class="w-10 flex justify-center pointer-events-none">
        <ui-toggle-switch v-model="lockCurrentOrientation" />
      </div>
      <p class="pl-4">Lock orientation</p>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4">Haptic feedback</p>
      <ui-dropdown v-model="settings.hapticFeedback" :items="hapticFeedbackItems" style="max-width: 105px" @input="hapticFeedbackUpdated" />
    </div>

    <!-- Playback settings -->
    <p class="uppercase text-xs font-semibold text-gray-300 mb-2 mt-6">Playback Settings</p>
    <div v-if="!isiOS" class="flex items-center py-3" @click="toggleDisableAutoRewind">
      <div class="w-10 flex justify-center">
        <ui-toggle-switch v-model="settings.disableAutoRewind" @input="saveSettings" />
      </div>
      <p class="pl-4">Disable auto rewind</p>
    </div>
    <div class="flex items-center py-3" @click="toggleJumpBackwards">
      <div class="w-10 flex justify-center">
        <span class="material-icons text-4xl">{{ currentJumpBackwardsTimeIcon }}</span>
      </div>
      <p class="pl-4">Jump backwards time</p>
    </div>
    <div class="flex items-center py-3" @click="toggleJumpForward">
      <div class="w-10 flex justify-center">
        <span class="material-icons text-4xl">{{ currentJumpForwardTimeIcon }}</span>
      </div>
      <p class="pl-4">Jump forwards time</p>
    </div>

    <!-- Sleep timer settings -->
    <p v-if="!isiOS" class="uppercase text-xs font-semibold text-gray-300 mb-2 mt-6">Sleep Timer Settings</p>
    <div v-if="!isiOS" class="flex items-center py-3" @click="toggleDisableShakeToResetSleepTimer">
      <div class="w-10 flex justify-center">
        <ui-toggle-switch v-model="settings.disableShakeToResetSleepTimer" @input="saveSettings" />
      </div>
      <p class="pl-4">Disable shake to reset</p>
      <span class="material-icons-outlined ml-2" @click.stop="showInfo('disableShakeToResetSleepTimer')">info</span>
    </div>
    <div v-if="!isiOS && !settings.disableShakeToResetSleepTimer" class="py-3 flex items-center">
      <p class="pr-4">Shake Sensitivity</p>
      <ui-dropdown v-model="settings.shakeSensitivity" :items="shakeSensitivityItems" style="max-width: 125px" @input="sensitivityUpdated" />
    </div>
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {
      deviceData: null,
      settings: {
        disableAutoRewind: false,
        enableAltView: false,
        jumpForwardTime: 10,
        jumpBackwardsTime: 10,
        disableShakeToResetSleepTimer: false,
        shakeSensitivity: 'MEDIUM',
        lockOrientation: 0,
        hapticFeedback: 'LIGHT'
      },
      settingInfo: {
        disableShakeToResetSleepTimer: {
          name: 'Disable shake to reset sleep timer',
          message: 'Shaking your device while the timer is running OR within 2 minutes of the timer expiring will reset the sleep timer. Enable this setting to disable shake to reset.'
        }
      },
      lockCurrentOrientation: false,
      hapticFeedbackItems: [
        {
          text: 'Off',
          value: 'OFF'
        },
        {
          text: 'Light',
          value: 'LIGHT'
        },
        {
          text: 'Medium',
          value: 'MEDIUM'
        },
        {
          text: 'Heavy',
          value: 'HEAVY'
        }
      ],
      shakeSensitivityItems: [
        {
          text: 'Very Low',
          value: 'VERY_LOW'
        },
        {
          text: 'Low',
          value: 'LOW'
        },
        {
          text: 'Medium',
          value: 'MEDIUM'
        },
        {
          text: 'High',
          value: 'HIGH'
        },
        {
          text: 'Very High',
          value: 'VERY_HIGH'
        }
      ]
    }
  },
  computed: {
    isiOS() {
      return this.$platform === 'ios'
    },
    jumpForwardItems() {
      return this.$store.state.globals.jumpForwardItems || []
    },
    jumpBackwardsItems() {
      return this.$store.state.globals.jumpBackwardsItems || []
    },
    currentJumpForwardTimeIcon() {
      return this.jumpForwardItems[this.currentJumpForwardTimeIndex].icon
    },
    currentJumpForwardTimeIndex() {
      var index = this.jumpForwardItems.findIndex((jfi) => jfi.value === this.settings.jumpForwardTime)
      return index >= 0 ? index : 1
    },
    currentJumpBackwardsTimeIcon() {
      return this.jumpBackwardsItems[this.currentJumpBackwardsTimeIndex].icon
    },
    currentJumpBackwardsTimeIndex() {
      var index = this.jumpBackwardsItems.findIndex((jfi) => jfi.value === this.settings.jumpBackwardsTime)
      return index >= 0 ? index : 1
    }
  },
  methods: {
    sensitivityUpdated(val) {
      this.saveSettings()
    },
    hapticFeedbackUpdated(val) {
      this.$store.commit('globals/setHapticFeedback', val)
      this.saveSettings()
    },
    showInfo(setting) {
      if (this.settingInfo[setting]) {
        Dialog.alert({
          title: this.settingInfo[setting].name,
          message: this.settingInfo[setting].message
        })
      }
    },
    toggleDisableShakeToResetSleepTimer() {
      this.settings.disableShakeToResetSleepTimer = !this.settings.disableShakeToResetSleepTimer
      this.saveSettings()
    },
    toggleDisableAutoRewind() {
      this.settings.disableAutoRewind = !this.settings.disableAutoRewind
      this.saveSettings()
    },
    toggleEnableAltView() {
      this.settings.enableAltView = !this.settings.enableAltView
      this.saveSettings()
    },
    getCurrentOrientation() {
      const orientation = window.screen ? window.screen.orientation || {} : {}
      const type = orientation.type || ''

      if (type.includes('landscape')) return 'LANDSCAPE'
      return 'PORTRAIT' // default
    },
    toggleLockOrientation() {
      this.lockCurrentOrientation = !this.lockCurrentOrientation
      if (this.lockCurrentOrientation) {
        this.settings.lockOrientation = this.getCurrentOrientation()
      } else {
        this.settings.lockOrientation = 'NONE'
      }
      this.$setOrientationLock(this.settings.lockOrientation)
      this.saveSettings()
    },
    toggleJumpForward() {
      var next = (this.currentJumpForwardTimeIndex + 1) % 3
      this.settings.jumpForwardTime = this.jumpForwardItems[next].value
      this.saveSettings()
    },
    toggleJumpBackwards() {
      var next = (this.currentJumpBackwardsTimeIndex + 4) % 3
      if (next > 2) return
      this.settings.jumpBackwardsTime = this.jumpBackwardsItems[next].value
      this.saveSettings()
    },
    async saveSettings() {
      await this.$hapticsImpact()
      const updatedDeviceData = await this.$db.updateDeviceSettings({ ...this.settings })
      if (updatedDeviceData) {
        this.$store.commit('setDeviceData', updatedDeviceData)
        this.init()
      }
    },
    async init() {
      this.deviceData = await this.$db.getDeviceData()
      this.$store.commit('setDeviceData', this.deviceData)

      const deviceSettings = this.deviceData.deviceSettings || {}
      this.settings.disableAutoRewind = !!deviceSettings.disableAutoRewind
      this.settings.enableAltView = !!deviceSettings.enableAltView
      this.settings.jumpForwardTime = deviceSettings.jumpForwardTime || 10
      this.settings.jumpBackwardsTime = deviceSettings.jumpBackwardsTime || 10
      this.settings.disableShakeToResetSleepTimer = !!deviceSettings.disableShakeToResetSleepTimer
      this.settings.shakeSensitivity = deviceSettings.shakeSensitivity || 'MEDIUM'
      this.settings.lockOrientation = deviceSettings.lockOrientation || 'NONE'
      this.lockCurrentOrientation = this.settings.lockOrientation !== 'NONE'
      this.settings.hapticFeedback = deviceSettings.hapticFeedback || 'LIGHT'
    }
  },
  mounted() {
    this.init()
  }
}
</script>
