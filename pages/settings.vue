<template>
  <div class="w-full h-full px-8 py-8 overflow-y-auto">
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
      <p class="pr-4 w-36">Haptic feedback</p>
      <div @click.stop="showHapticFeedbackOptions">
        <ui-text-input :value="hapticFeedbackOption" readonly append-icon="expand_more" style="max-width: 145px" />
      </div>
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
    <template v-if="!isiOS">
      <p class="uppercase text-xs font-semibold text-gray-300 mb-2 mt-6">Sleep Timer Settings</p>
      <div class="flex items-center py-3" @click="toggleDisableShakeToResetSleepTimer">
        <div class="w-10 flex justify-center">
          <ui-toggle-switch v-model="settings.disableShakeToResetSleepTimer" @input="saveSettings" />
        </div>
        <p class="pl-4">Disable shake to reset</p>
        <span class="material-icons-outlined ml-2" @click.stop="showInfo('disableShakeToResetSleepTimer')">info</span>
      </div>
      <div v-if="!settings.disableShakeToResetSleepTimer" class="py-3 flex items-center">
        <p class="pr-4 w-36">Shake Sensitivity</p>
        <div @click.stop="showShakeSensitivityOptions">
          <ui-text-input :value="shakeSensitivityOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
        </div>
      </div>
      <div class="flex items-center py-3" @click="toggleAutoSleepTimer">
        <div class="w-10 flex justify-center">
          <ui-toggle-switch v-model="settings.autoSleepTimer" @input="saveSettings" />
        </div>
        <p class="pl-4">Auto Sleep Timer</p>
        <span class="material-icons-outlined ml-2" @click.stop="showInfo('autoSleepTimer')">info</span>
      </div>
    </template>
    <!-- Auto Sleep timer settings -->
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">Start Time</p>
      <ui-text-input type="time" v-model="settings.autoSleepTimerStartTime" style="width: 145px; max-width: 145px" @input="autoSleepTimerTimeUpdated" />
    </div>
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">End Time</p>
      <ui-text-input type="time" v-model="settings.autoSleepTimerEndTime" style="width: 145px; max-width: 145px" @input="autoSleepTimerTimeUpdated" />
    </div>
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">Sleep Timer</p>
      <div @click.stop="showSleepTimerOptions">
        <ui-text-input :value="sleepTimerLengthOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
      </div>
    </div>

    <modals-dialog v-model="showMoreMenuDialog" :items="moreMenuItems" @action="clickMenuAction" />
    <modals-sleep-timer-length-modal v-model="showSleepTimerLengthModal" @change="sleepTimerLengthModalSelection" />
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {
      deviceData: null,
      showMoreMenuDialog: false,
      showSleepTimerLengthModal: false,
      moreMenuSetting: '',
      settings: {
        disableAutoRewind: false,
        enableAltView: false,
        jumpForwardTime: 10,
        jumpBackwardsTime: 10,
        disableShakeToResetSleepTimer: false,
        shakeSensitivity: 'MEDIUM',
        lockOrientation: 0,
        hapticFeedback: 'LIGHT',
        autoSleepTimer: false,
        autoSleepTimerStartTime: '22:00',
        autoSleepTimerEndTime: '06:00',
        sleepTimerLength: 900000 // 15 minutes
      },
      lockCurrentOrientation: false,
      settingInfo: {
        disableShakeToResetSleepTimer: {
          name: 'Disable shake to reset sleep timer',
          message: 'Shaking your device while the timer is running OR within 2 minutes of the timer expiring will reset the sleep timer. Enable this setting to disable shake to reset.'
        },
        autoSleepTimer: {
          name: 'Auto Sleep Timer',
          message: 'When playing media between the specified start and end times a sleep timer will automatically start.'
        }
      },
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
    },
    shakeSensitivityOption() {
      const item = this.shakeSensitivityItems.find((i) => i.value === this.settings.shakeSensitivity)
      return item ? item.text : 'Error'
    },
    hapticFeedbackOption() {
      const item = this.hapticFeedbackItems.find((i) => i.value === this.settings.hapticFeedback)
      return item ? item.text : 'Error'
    },
    sleepTimerLengthOption() {
      if (!this.settings.sleepTimerLength) return 'End of Chapter'
      const minutes = Number(this.settings.sleepTimerLength) / 1000 / 60
      return `${minutes} min`
    },
    moreMenuItems() {
      if (this.moreMenuSetting === 'shakeSensitivity') return this.shakeSensitivityItems
      else if (this.moreMenuSetting === 'hapticFeedback') return this.hapticFeedbackItems
      return []
    }
  },
  methods: {
    sleepTimerLengthModalSelection(value) {
      this.settings.sleepTimerLength = value
      this.saveSettings()
    },
    showSleepTimerOptions() {
      this.showSleepTimerLengthModal = true
    },
    showHapticFeedbackOptions() {
      this.moreMenuSetting = 'hapticFeedback'
      this.showMoreMenuDialog = true
    },
    showShakeSensitivityOptions() {
      this.moreMenuSetting = 'shakeSensitivity'
      this.showMoreMenuDialog = true
    },
    clickMenuAction(action) {
      this.showMoreMenuDialog = false
      if (this.moreMenuSetting === 'shakeSensitivity') {
        this.settings.shakeSensitivity = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'hapticFeedback') {
        this.settings.hapticFeedback = action
        this.hapticFeedbackUpdated(action)
      }
    },
    autoSleepTimerTimeUpdated(val) {
      console.log('[settings] Auto sleep timer time=', val)
      if (!val) return // invalid times return falsy
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
    toggleAutoSleepTimer() {
      this.settings.autoSleepTimer = !this.settings.autoSleepTimer
      this.saveSettings()
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
      this.settings.lockOrientation = deviceSettings.lockOrientation || 'NONE'
      this.lockCurrentOrientation = this.settings.lockOrientation !== 'NONE'
      this.settings.hapticFeedback = deviceSettings.hapticFeedback || 'LIGHT'

      this.settings.disableShakeToResetSleepTimer = !!deviceSettings.disableShakeToResetSleepTimer
      this.settings.shakeSensitivity = deviceSettings.shakeSensitivity || 'MEDIUM'
      this.settings.autoSleepTimer = !!deviceSettings.autoSleepTimer
      this.settings.autoSleepTimerStartTime = deviceSettings.autoSleepTimerStartTime || '22:00'
      this.settings.autoSleepTimerEndTime = deviceSettings.autoSleepTimerEndTime || '06:00'
      this.settings.sleepTimerLength = !isNaN(deviceSettings.sleepTimerLength) ? deviceSettings.sleepTimerLength : 900000 // 15 minutes
    }
  },
  mounted() {
    this.init()
  }
}
</script>
