<template>
  <div class="w-full h-full px-4 py-8 overflow-y-auto">
    <!-- Display settings -->
    <p class="uppercase text-xs font-semibold text-fg-muted mb-2">{{ $strings.HeaderUserInterfaceSettings }}</p>
    <div class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleEnableAltView">
        <ui-toggle-switch v-model="enableBookshelfView" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelUseBookshelfView }}</p>
    </div>
    <!-- screen.orientation.lock not supported on iOS webview -->
    <div v-if="!isiOS" class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click.stop="toggleLockOrientation">
        <ui-toggle-switch v-model="lockCurrentOrientation" class="pointer-events-none" />
      </div>
      <p class="pl-4">{{ $strings.LabelLockOrientation }}</p>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelHapticFeedback }}</p>
      <div @click.stop="showHapticFeedbackOptions">
        <ui-text-input :value="hapticFeedbackOption" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelLanguage }}</p>
      <div @click.stop="showLanguageOptions">
        <ui-text-input :value="languageOption" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelTheme }}</p>
      <div @click.stop="showThemeOptions">
        <ui-text-input :value="themeOption" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>

    <!-- Playback settings -->
    <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderPlaybackSettings }}</p>
    <div v-if="!isiOS" class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleDisableAutoRewind">
        <ui-toggle-switch v-model="settings.disableAutoRewind" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelDisableAutoRewind }}</p>
    </div>
    <div class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleJumpBackwards">
        <span class="material-icons text-4xl">{{ currentJumpBackwardsTimeIcon }}</span>
      </div>
      <p class="pl-4">{{ $strings.LabelJumpBackwardsTime }}</p>
    </div>
    <div class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleJumpForward">
        <span class="material-icons text-4xl">{{ currentJumpForwardTimeIcon }}</span>
      </div>
      <p class="pl-4">{{ $strings.LabelJumpForwardsTime }}</p>
    </div>
    <div v-if="!isiOS" class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleEnableMp3IndexSeeking">
        <ui-toggle-switch v-model="settings.enableMp3IndexSeeking" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelEnableMp3IndexSeeking }}</p>
      <span class="material-icons-outlined ml-2" @click.stop="showConfirmMp3IndexSeeking">info</span>
    </div>
    <div class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleAllowSeekingOnMediaControls">
        <ui-toggle-switch v-model="settings.allowSeekingOnMediaControls" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelAllowSeekingOnMediaControls }}</p>
    </div>

    <!-- Sleep timer settings -->
    <template v-if="!isiOS">
      <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderSleepTimerSettings }}</p>
      <div class="flex items-center py-3">
        <div class="w-10 flex justify-center" @click="toggleDisableShakeToResetSleepTimer">
          <ui-toggle-switch v-model="settings.disableShakeToResetSleepTimer" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelDisableShakeToReset }}</p>
        <span class="material-icons-outlined ml-2" @click.stop="showInfo('disableShakeToResetSleepTimer')">info</span>
      </div>
      <div v-if="!settings.disableShakeToResetSleepTimer" class="py-3 flex items-center">
        <p class="pr-4 w-36">{{ $strings.LabelShakeSensitivity }}</p>
        <div @click.stop="showShakeSensitivityOptions">
          <ui-text-input :value="shakeSensitivityOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
        </div>
      </div>
      <div class="flex items-center py-3">
        <div class="w-10 flex justify-center" @click="toggleDisableSleepTimerFadeOut">
          <ui-toggle-switch v-model="settings.disableSleepTimerFadeOut" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelDisableAudioFadeOut }}</p>
        <span class="material-icons-outlined ml-2" @click.stop="showInfo('disableSleepTimerFadeOut')">info</span>
      </div>
      <div class="flex items-center py-3">
        <div class="w-10 flex justify-center" @click="toggleDisableSleepTimerResetFeedback">
          <ui-toggle-switch v-model="settings.disableSleepTimerResetFeedback" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelDisableVibrateOnReset }}</p>
        <span class="material-icons-outlined ml-2" @click.stop="showInfo('disableSleepTimerResetFeedback')">info</span>
      </div>
      <div class="flex items-center py-3">
        <div class="w-10 flex justify-center" @click="toggleAutoSleepTimer">
          <ui-toggle-switch v-model="settings.autoSleepTimer" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelAutoSleepTimer }}</p>
        <span class="material-icons-outlined ml-2" @click.stop="showInfo('autoSleepTimer')">info</span>
      </div>
    </template>
    <!-- Auto Sleep timer settings -->
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelStartTime }}</p>
      <ui-text-input type="time" v-model="settings.autoSleepTimerStartTime" style="width: 145px; max-width: 145px" @input="autoSleepTimerTimeUpdated" />
    </div>
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelEndTime }}</p>
      <ui-text-input type="time" v-model="settings.autoSleepTimerEndTime" style="width: 145px; max-width: 145px" @input="autoSleepTimerTimeUpdated" />
    </div>
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelSleepTimer }}</p>
      <div @click.stop="showSleepTimerOptions">
        <ui-text-input :value="sleepTimerLengthOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
      </div>
    </div>
    <div v-if="settings.autoSleepTimer" class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleAutoSleepTimerAutoRewind">
        <ui-toggle-switch v-model="settings.autoSleepTimerAutoRewind" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelAutoSleepTimerAutoRewind }}</p>
      <span class="material-icons-outlined ml-2" @click.stop="showInfo('autoSleepTimerAutoRewind')">info</span>
    </div>
    <div v-if="settings.autoSleepTimerAutoRewind" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelAutoRewindTime }}</p>
      <div @click.stop="showAutoSleepTimerRewindOptions">
        <ui-text-input :value="autoSleepTimerRewindLengthOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
      </div>
    </div>

    <!-- Data settings -->
    <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderDataSettings }}</p>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelDownloadUsingCellular }}</p>
      <div @click.stop="showDownloadUsingCellularOptions">
        <ui-text-input :value="downloadUsingCellularOption" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelStreamingUsingCellular }}</p>
      <div @click.stop="showStreamingUsingCellularOptions">
        <ui-text-input :value="streamingUsingCellularOption" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>

    <div v-show="loading" class="w-full h-full absolute top-0 left-0 flex items-center justify-center z-10">
      <ui-loading-indicator />
    </div>

    <modals-dialog v-model="showMoreMenuDialog" :items="moreMenuItems" @action="clickMenuAction" />
    <modals-sleep-timer-length-modal v-model="showSleepTimerLengthModal" @change="sleepTimerLengthModalSelection" />
    <modals-auto-sleep-timer-rewind-length-modal v-model="showAutoSleepTimerRewindLengthModal" @change="showAutoSleepTimerRewindLengthModalSelection" />
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {
      loading: false,
      deviceData: null,
      showMoreMenuDialog: false,
      showSleepTimerLengthModal: false,
      showAutoSleepTimerRewindLengthModal: false,
      moreMenuSetting: '',
      settings: {
        disableAutoRewind: false,
        enableAltView: true,
        allowSeekingOnMediaControls: false,
        jumpForwardTime: 10,
        jumpBackwardsTime: 10,
        enableMp3IndexSeeking: false,
        disableShakeToResetSleepTimer: false,
        shakeSensitivity: 'MEDIUM',
        lockOrientation: 0,
        hapticFeedback: 'LIGHT',
        autoSleepTimer: false,
        autoSleepTimerStartTime: '22:00',
        autoSleepTimerEndTime: '06:00',
        sleepTimerLength: 900000, // 15 minutes
        disableSleepTimerFadeOut: false,
        disableSleepTimerResetFeedback: false,
        autoSleepTimerAutoRewind: false,
        autoSleepTimerAutoRewindTime: 300000, // 5 minutes
        languageCode: 'en-us',
        downloadUsingCellular: 'ALWAYS',
        streamingUsingCellular: 'ALWAYS'
      },
      theme: 'dark',
      lockCurrentOrientation: false,
      settingInfo: {
        disableShakeToResetSleepTimer: {
          name: this.$strings.LabelDisableShakeToReset,
          message: this.$strings.LabelDisableShakeToResetHelp
        },
        autoSleepTimer: {
          name: this.$strings.LabelAutoSleepTimer,
          message: this.$strings.LabelAutoSleepTimerHelp
        },
        disableSleepTimerFadeOut: {
          name: this.$strings.LabelDisableAudioFadeOut,
          message: this.$strings.LabelDisableAudioFadeOutHelp
        },
        disableSleepTimerResetFeedback: {
          name: this.$strings.LabelDisableVibrateOnReset,
          message: this.$strings.LabelDisableVibrateOnResetHelp
        },
        autoSleepTimerAutoRewind: {
          name: this.$strings.LabelAutoSleepTimerAutoRewind,
          message: this.$strings.LabelAutoSleepTimerAutoRewindHelp
        },
        enableMp3IndexSeeking: {
          name: this.$strings.LabelEnableMp3IndexSeeking,
          message: this.$strings.LabelEnableMp3IndexSeekingHelp
        }
      },
      hapticFeedbackItems: [
        {
          text: this.$strings.LabelOff,
          value: 'OFF'
        },
        {
          text: this.$strings.LabelLight,
          value: 'LIGHT'
        },
        {
          text: this.$strings.LabelMedium,
          value: 'MEDIUM'
        },
        {
          text: this.$strings.LabelHeavy,
          value: 'HEAVY'
        }
      ],
      shakeSensitivityItems: [
        {
          text: this.$strings.LabelVeryLow,
          value: 'VERY_LOW'
        },
        {
          text: this.$strings.LabelLow,
          value: 'LOW'
        },
        {
          text: this.$strings.LabelMedium,
          value: 'MEDIUM'
        },
        {
          text: this.$strings.LabelHigh,
          value: 'HIGH'
        },
        {
          text: this.$strings.LabelVeryHigh,
          value: 'VERY_HIGH'
        }
      ],
      downloadUsingCellularItems: [
        {
          text: this.$strings.LabelAskConfirmation,
          value: 'ASK'
        },
        {
          text: this.$strings.LabelAlways,
          value: 'ALWAYS'
        },
        {
          text: this.$strings.LabelNever,
          value: 'NEVER'
        }
      ],
      streamingUsingCellularItems: [
        {
          text: this.$strings.LabelAskConfirmation,
          value: 'ASK'
        },
        {
          text: this.$strings.LabelAlways,
          value: 'ALWAYS'
        },
        {
          text: this.$strings.LabelNever,
          value: 'NEVER'
        }
      ]
    }
  },
  computed: {
    // This is flipped because alt view was the default until v0.9.61-beta
    enableBookshelfView: {
      get() {
        return !this.settings.enableAltView
      },
      set(val) {
        this.settings.enableAltView = !val
      }
    },
    isiOS() {
      return this.$platform === 'ios'
    },
    jumpForwardItems() {
      return this.$store.state.globals.jumpForwardItems || []
    },
    jumpBackwardsItems() {
      return this.$store.state.globals.jumpBackwardsItems || []
    },
    languageOptionItems() {
      return this.$languageCodeOptions || []
    },
    themeOptionItems() {
      return [
        {
          text: this.$strings.LabelThemeDark,
          value: 'dark'
        },
        {
          text: this.$strings.LabelThemeLight,
          value: 'light'
        }
      ]
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
      return item?.text || 'Error'
    },
    hapticFeedbackOption() {
      const item = this.hapticFeedbackItems.find((i) => i.value === this.settings.hapticFeedback)
      return item?.text || 'Error'
    },
    languageOption() {
      return this.languageOptionItems.find((i) => i.value === this.settings.languageCode)?.text || ''
    },
    themeOption() {
      return this.themeOptionItems.find((i) => i.value === this.theme)?.text || ''
    },
    sleepTimerLengthOption() {
      if (!this.settings.sleepTimerLength) return this.$strings.LabelEndOfChapter
      const minutes = Number(this.settings.sleepTimerLength) / 1000 / 60
      return `${minutes} min`
    },
    autoSleepTimerRewindLengthOption() {
      const minutes = Number(this.settings.autoSleepTimerAutoRewindTime) / 1000 / 60
      return `${minutes} min`
    },
    downloadUsingCellularOption() {
      const item = this.downloadUsingCellularItems.find((i) => i.value === this.settings.downloadUsingCellular)
      return item?.text || 'Error'
    },
    streamingUsingCellularOption() {
      const item = this.streamingUsingCellularItems.find((i) => i.value === this.settings.streamingUsingCellular)
      return item?.text || 'Error'
    },
    moreMenuItems() {
      if (this.moreMenuSetting === 'shakeSensitivity') return this.shakeSensitivityItems
      else if (this.moreMenuSetting === 'hapticFeedback') return this.hapticFeedbackItems
      else if (this.moreMenuSetting === 'language') return this.languageOptionItems
      else if (this.moreMenuSetting === 'theme') return this.themeOptionItems
      else if (this.moreMenuSetting === 'downloadUsingCellular') return this.downloadUsingCellularItems
      else if (this.moreMenuSetting === 'streamingUsingCellular') return this.streamingUsingCellularItems
      return []
    }
  },
  methods: {
    sleepTimerLengthModalSelection(value) {
      this.settings.sleepTimerLength = value
      this.saveSettings()
    },
    showAutoSleepTimerRewindLengthModalSelection(value) {
      this.settings.autoSleepTimerAutoRewindTime = value
      this.saveSettings()
    },
    showSleepTimerOptions() {
      this.showSleepTimerLengthModal = true
    },
    showAutoSleepTimerRewindOptions() {
      this.showAutoSleepTimerRewindLengthModal = true
    },
    showHapticFeedbackOptions() {
      this.moreMenuSetting = 'hapticFeedback'
      this.showMoreMenuDialog = true
    },
    showShakeSensitivityOptions() {
      this.moreMenuSetting = 'shakeSensitivity'
      this.showMoreMenuDialog = true
    },
    showLanguageOptions() {
      this.moreMenuSetting = 'language'
      this.showMoreMenuDialog = true
    },
    showThemeOptions() {
      this.moreMenuSetting = 'theme'
      this.showMoreMenuDialog = true
    },
    showDownloadUsingCellularOptions() {
      this.moreMenuSetting = 'downloadUsingCellular'
      this.showMoreMenuDialog = true
    },
    showStreamingUsingCellularOptions() {
      this.moreMenuSetting = 'streamingUsingCellular'
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
      } else if (this.moreMenuSetting === 'language') {
        this.settings.languageCode = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'theme') {
        this.theme = action
        this.saveTheme(action)
      } else if (this.moreMenuSetting === 'downloadUsingCellular') {
        this.settings.downloadUsingCellular = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'streamingUsingCellular') {
        this.settings.streamingUsingCellular = action
        this.saveSettings()
      }
    },
    saveTheme(theme) {
      document.documentElement.dataset.theme = theme
      this.$localStore.setTheme(theme)
    },
    autoSleepTimerTimeUpdated(val) {
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
    async showConfirmMp3IndexSeeking() {
      const confirmResult = await Dialog.confirm({
        title: this.settingInfo.enableMp3IndexSeeking.name,
        message: this.settingInfo.enableMp3IndexSeeking.message,
        cancelButtonTitle: 'View More'
      })
      if (!confirmResult.value) {
        window.open('https://exoplayer.dev/troubleshooting.html#why-is-seeking-inaccurate-in-some-mp3-files', '_blank')
      }
    },
    toggleEnableMp3IndexSeeking() {
      this.settings.enableMp3IndexSeeking = !this.settings.enableMp3IndexSeeking
      this.saveSettings()
    },
    toggleAutoSleepTimer() {
      this.settings.autoSleepTimer = !this.settings.autoSleepTimer
      this.saveSettings()
    },
    toggleAutoSleepTimerAutoRewind() {
      this.settings.autoSleepTimerAutoRewind = !this.settings.autoSleepTimerAutoRewind
      this.saveSettings()
    },
    toggleDisableSleepTimerFadeOut() {
      this.settings.disableSleepTimerFadeOut = !this.settings.disableSleepTimerFadeOut
      this.saveSettings()
    },
    toggleDisableShakeToResetSleepTimer() {
      this.settings.disableShakeToResetSleepTimer = !this.settings.disableShakeToResetSleepTimer
      this.saveSettings()
    },
    toggleDisableSleepTimerResetFeedback() {
      this.settings.disableSleepTimerResetFeedback = !this.settings.disableSleepTimerResetFeedback
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
    toggleAllowSeekingOnMediaControls() {
      this.settings.allowSeekingOnMediaControls = !this.settings.allowSeekingOnMediaControls
      this.saveSettings()
    },
    getCurrentOrientation() {
      const orientation = window.screen?.orientation || {}
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
        this.deviceData = updatedDeviceData
        this.$setLanguageCode(updatedDeviceData.deviceSettings?.languageCode || 'en-us')
        this.setDeviceSettings()
      }
    },
    setDeviceSettings() {
      const deviceSettings = this.deviceData.deviceSettings || {}
      this.settings.disableAutoRewind = !!deviceSettings.disableAutoRewind
      this.settings.enableAltView = !!deviceSettings.enableAltView
      this.settings.allowSeekingOnMediaControls = !!deviceSettings.allowSeekingOnMediaControls
      this.settings.jumpForwardTime = deviceSettings.jumpForwardTime || 10
      this.settings.jumpBackwardsTime = deviceSettings.jumpBackwardsTime || 10
      this.settings.enableMp3IndexSeeking = !!deviceSettings.enableMp3IndexSeeking

      this.settings.lockOrientation = deviceSettings.lockOrientation || 'NONE'
      this.lockCurrentOrientation = this.settings.lockOrientation !== 'NONE'
      this.settings.hapticFeedback = deviceSettings.hapticFeedback || 'LIGHT'

      this.settings.disableShakeToResetSleepTimer = !!deviceSettings.disableShakeToResetSleepTimer
      this.settings.shakeSensitivity = deviceSettings.shakeSensitivity || 'MEDIUM'
      this.settings.autoSleepTimer = !!deviceSettings.autoSleepTimer
      this.settings.autoSleepTimerStartTime = deviceSettings.autoSleepTimerStartTime || '22:00'
      this.settings.autoSleepTimerEndTime = deviceSettings.autoSleepTimerEndTime || '06:00'
      this.settings.sleepTimerLength = !isNaN(deviceSettings.sleepTimerLength) ? deviceSettings.sleepTimerLength : 900000 // 15 minutes
      this.settings.disableSleepTimerFadeOut = !!deviceSettings.disableSleepTimerFadeOut
      this.settings.disableSleepTimerResetFeedback = !!deviceSettings.disableSleepTimerResetFeedback

      this.settings.autoSleepTimerAutoRewind = !!deviceSettings.autoSleepTimerAutoRewind
      this.settings.autoSleepTimerAutoRewindTime = !isNaN(deviceSettings.autoSleepTimerAutoRewindTime) ? deviceSettings.autoSleepTimerAutoRewindTime : 300000 // 5 minutes

      this.settings.languageCode = deviceSettings.languageCode || 'en-us'

      this.settings.downloadUsingCellular = deviceSettings.downloadUsingCellular || 'ALWAYS'
      this.settings.streamingUsingCellular = deviceSettings.streamingUsingCellular || 'ALWAYS'
    },
    async init() {
      this.loading = true
      this.theme = (await this.$localStore.getTheme()) || 'dark'
      this.deviceData = await this.$db.getDeviceData()
      this.$store.commit('setDeviceData', this.deviceData)
      this.setDeviceSettings()
      this.loading = false
    }
  },
  mounted() {
    this.init()
  }
}
</script>
