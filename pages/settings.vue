<template>
  <div class="w-full h-full px-6 py-8 overflow-y-auto" :style="contentPaddingStyle">
    <!-- Display settings -->
    <p class="uppercase text-label-small font-semibold text-fg-muted mb-2">{{ $strings.HeaderUserInterfaceSettings }}</p>
    <div class="flex items-center py-3">
      <div class="w-12 flex justify-center mr-2" @click="toggleEnableAltView">
        <ui-toggle-switch v-model="enableBookshelfView" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelUseBookshelfView }}</p>
    </div>
    <!-- screen.orientation.lock not supported on iOS webview -->
    <div v-if="!isiOS" class="flex items-center py-3">
      <div class="w-12 flex justify-center mr-2" @click.stop="toggleLockOrientation">
        <ui-toggle-switch v-model="lockCurrentOrientation" class="pointer-events-none" />
      </div>
      <p class="pl-4">{{ $strings.LabelLockOrientation }}</p>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelHapticFeedback }}</p>
      <div @click.stop="showHapticFeedbackOptions">
        <ui-text-input :value="hapticFeedbackOption" readonly append-icon="expand_more" variant="outlined" style="max-width: 200px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelLanguage }}</p>
      <div @click.stop="showLanguageOptions">
        <ui-text-input :value="languageOption" readonly append-icon="expand_more" variant="outlined" style="max-width: 200px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelTheme }}</p>
      <div @click.stop="showThemeOptions">
        <ui-text-input :value="themeOption" readonly append-icon="expand_more" variant="outlined" style="max-width: 200px" />
      </div>
    </div>
    <div v-if="$platform === 'android'" class="flex items-center py-3">
      <div class="w-12 flex justify-center mr-2" @click="toggleDynamicColors">
        <ui-toggle-switch v-model="settings.enableDynamicColors" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelUseDynamicColors || 'Use Dynamic Colors (Material You)' }}</p>
      <span class="material-symbols text-display-small ml-2 text-on-surface" @click.stop="showInfo('dynamicColors')">info</span>
    </div>

    <!-- Playback settings -->
    <p class="uppercase text-label-small font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderPlaybackSettings }}</p>
    <div class="flex items-center py-3">
      <div class="w-12 flex justify-center mr-2" @click="toggleDisableAutoRewind">
        <ui-toggle-switch v-model="settings.disableAutoRewind" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelDisableAutoRewind }}</p>
    </div>
    <div class="flex items-center py-3">
      <div class="w-12 flex justify-center mr-2" @click="toggleJumpBackwards">
        <span class="material-symbols text-display-large text-on-surface">{{ currentJumpBackwardsTimeIcon }}</span>
      </div>
      <p class="pl-4">{{ $strings.LabelJumpBackwardsTime }}</p>
    </div>
    <div class="flex items-center py-3">
      <div class="w-12 flex justify-center mr-2" @click="toggleJumpForward">
        <span class="material-symbols text-display-large text-on-surface">{{ currentJumpForwardTimeIcon }}</span>
      </div>
      <p class="pl-4">{{ $strings.LabelJumpForwardsTime }}</p>
    </div>
    <div v-if="!isiOS" class="flex items-center py-3">
      <div class="w-12 flex justify-center mr-2" @click="toggleEnableMp3IndexSeeking">
        <ui-toggle-switch v-model="settings.enableMp3IndexSeeking" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelEnableMp3IndexSeeking }}</p>
      <span class="material-symbols text-display-small ml-2 text-on-surface" @click.stop="showConfirmMp3IndexSeeking">info</span>
    </div>
    <div class="flex items-center py-3">
      <div class="w-12 flex justify-center mr-2" @click="toggleAllowSeekingOnMediaControls">
        <ui-toggle-switch v-model="settings.allowSeekingOnMediaControls" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelAllowSeekingOnMediaControls }}</p>
    </div>

    <!-- Sleep timer settings -->
    <template v-if="!isiOS">
      <p class="uppercase text-label-small font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderSleepTimerSettings }}</p>
      <div class="flex items-center py-3">
        <div class="w-12 flex justify-center mr-2" @click="toggleDisableShakeToResetSleepTimer">
          <ui-toggle-switch v-model="settings.disableShakeToResetSleepTimer" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelDisableShakeToReset }}</p>
        <span class="material-symbols text-display-small ml-2 text-on-surface" @click.stop="showInfo('disableShakeToResetSleepTimer')">info</span>
      </div>
      <div v-if="!settings.disableShakeToResetSleepTimer" class="py-3 flex items-center">
        <p class="pr-4 w-36">{{ $strings.LabelShakeSensitivity }}</p>
        <div @click.stop="showShakeSensitivityOptions">
          <ui-text-input :value="shakeSensitivityOption" readonly append-icon="expand_more" variant="outlined" style="width: 145px; max-width: 145px" />
        </div>
      </div>
    </template>
    <div class="flex items-center py-3">
      <div class="w-12 flex justify-center mr-2" @click="toggleDisableSleepTimerFadeOut">
        <ui-toggle-switch v-model="settings.disableSleepTimerFadeOut" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelDisableAudioFadeOut }}</p>
      <span class="material-symbols text-display-small ml-2 text-on-surface" @click.stop="showInfo('disableSleepTimerFadeOut')">info</span>
    </div>
    <template v-if="!isiOS">
      <div class="flex items-center py-3">
        <div class="w-12 flex justify-center mr-2" @click="toggleDisableSleepTimerResetFeedback">
          <ui-toggle-switch v-model="settings.disableSleepTimerResetFeedback" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelDisableVibrateOnReset }}</p>
        <span class="material-symbols text-display-small ml-2 text-on-surface" @click.stop="showInfo('disableSleepTimerResetFeedback')">info</span>
      </div>
      <div class="flex items-center py-3">
        <div class="w-12 flex justify-center mr-2" @click="toggleSleepTimerAlmostDoneChime">
          <ui-toggle-switch v-model="settings.enableSleepTimerAlmostDoneChime" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelSleepTimerAlmostDoneChime }}</p>
        <span class="material-symbols text-display-small ml-2 text-on-surface" @click.stop="showInfo('enableSleepTimerAlmostDoneChime')">info</span>
      </div>
      <div class="flex items-center py-3">
        <div class="w-12 flex justify-center mr-2" @click="toggleAutoSleepTimer">
          <ui-toggle-switch v-model="settings.autoSleepTimer" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelAutoSleepTimer }}</p>
        <span class="material-symbols text-display-small ml-2 text-on-surface" @click.stop="showInfo('autoSleepTimer')">info</span>
      </div>
    </template>
    <!-- Auto Sleep timer settings -->
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelStartTime }}</p>
      <ui-text-input type="time" v-model="settings.autoSleepTimerStartTime" variant="outlined" style="width: 145px; max-width: 145px" @input="autoSleepTimerTimeUpdated" />
    </div>
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelEndTime }}</p>
      <ui-text-input type="time" v-model="settings.autoSleepTimerEndTime" variant="outlined" style="width: 145px; max-width: 145px" @input="autoSleepTimerTimeUpdated" />
    </div>
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelSleepTimer }}</p>
      <div @click.stop="showSleepTimerOptions">
        <ui-text-input :value="sleepTimerLengthOption" readonly append-icon="expand_more" variant="outlined" style="width: 145px; max-width: 145px" />
      </div>
    </div>
    <div v-if="settings.autoSleepTimer" class="flex items-center py-3">
      <div class="w-12 flex justify-center mr-2" @click="toggleAutoSleepTimerAutoRewind">
        <ui-toggle-switch v-model="settings.autoSleepTimerAutoRewind" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelAutoSleepTimerAutoRewind }}</p>
      <span class="material-symbols text-display-small ml-2 text-on-surface" @click.stop="showInfo('autoSleepTimerAutoRewind')">info</span>
    </div>
    <div v-if="settings.autoSleepTimerAutoRewind" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelAutoRewindTime }}</p>
      <div @click.stop="showAutoSleepTimerRewindOptions">
        <ui-text-input :value="autoSleepTimerRewindLengthOption" readonly append-icon="expand_more" variant="outlined" style="width: 145px; max-width: 145px" />
      </div>
    </div>

    <!-- Data settings -->
    <p class="uppercase text-label-small font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderDataSettings }}</p>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelDownloadUsingCellular }}</p>
      <div @click.stop="showDownloadUsingCellularOptions">
        <ui-text-input :value="downloadUsingCellularOption" readonly append-icon="expand_more" variant="outlined" style="max-width: 200px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelStreamingUsingCellular }}</p>
      <div @click.stop="showStreamingUsingCellularOptions">
        <ui-text-input :value="streamingUsingCellularOption" readonly append-icon="expand_more" variant="outlined" style="max-width: 200px" />
      </div>
    </div>

    <!-- Android Auto settings -->
    <template v-if="!isiOS">
      <p class="uppercase text-label-small font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderAndroidAutoSettings }}</p>
      <div class="py-3 flex items-center">
        <p class="pr-4 w-36">{{ $strings.LabelAndroidAutoBrowseLimitForGrouping }}</p>
        <ui-text-input type="number" v-model="settings.androidAutoBrowseLimitForGrouping" variant="outlined" style="width: 145px; max-width: 145px" @input="androidAutoBrowseLimitForGroupingUpdated" />
        <span class="material-symbols text-display-small ml-2 text-on-surface" @click.stop="showInfo('androidAutoBrowseLimitForGrouping')">info</span>
      </div>
      <div class="py-3 flex items-center">
        <p class="pr-4 w-36">{{ $strings.LabelAndroidAutoBrowseSeriesSequenceOrder }}</p>
        <div @click.stop="showAndroidAutoBrowseSeriesSequenceOrderOptions">
          <ui-text-input :value="androidAutoBrowseSeriesSequenceOrderOption" readonly append-icon="expand_more" variant="outlined" style="max-width: 200px" />
        </div>
      </div>
    </template>

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
        enableSleepTimerAlmostDoneChime: false,
        autoSleepTimerAutoRewind: false,
        autoSleepTimerAutoRewindTime: 300000, // 5 minutes
        languageCode: 'en-us',
        downloadUsingCellular: 'ALWAYS',
        streamingUsingCellular: 'ALWAYS',
        androidAutoBrowseLimitForGrouping: 100,
        androidAutoBrowseSeriesSequenceOrder: 'ASC',
        enableDynamicColors: true
      },
      theme: 'system',
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
        dynamicColors: {
          name: 'Dynamic Colors',
          message: 'Use Material You dynamic colors based on your wallpaper. Available on Android 12+ devices. The app will restart to apply changes.'
        },
        enableSleepTimerAlmostDoneChime: {
          name: this.$strings.LabelSleepTimerAlmostDoneChime,
          message: this.$strings.LabelSleepTimerAlmostDoneChimeHelp
        },
        autoSleepTimerAutoRewind: {
          name: this.$strings.LabelAutoSleepTimerAutoRewind,
          message: this.$strings.LabelAutoSleepTimerAutoRewindHelp
        },
        enableMp3IndexSeeking: {
          name: this.$strings.LabelEnableMp3IndexSeeking,
          message: this.$strings.LabelEnableMp3IndexSeekingHelp
        },
        androidAutoBrowseLimitForGrouping: {
          name: this.$strings.LabelAndroidAutoBrowseLimitForGrouping,
          message: this.$strings.LabelAndroidAutoBrowseLimitForGroupingHelp
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
      ],
      androidAutoBrowseSeriesSequenceOrderItems: [
        {
          text: this.$strings.LabelSequenceAscending,
          value: 'ASC'
        },
        {
          text: this.$strings.LabelSequenceDescending,
          value: 'DESC'
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
          text: this.$strings.LabelThemeSystem || 'System',
          value: 'system'
        },
        {
          text: this.$strings.LabelThemeDark || 'Dark',
          value: 'dark'
        },
        {
          text: this.$strings.LabelThemeLight || 'Light',
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
    androidAutoBrowseSeriesSequenceOrderOption() {
      const item = this.androidAutoBrowseSeriesSequenceOrderItems.find((i) => i.value === this.settings.androidAutoBrowseSeriesSequenceOrder)
      return item?.text || 'Error'
    },
    moreMenuItems() {
      if (this.moreMenuSetting === 'shakeSensitivity') return this.shakeSensitivityItems
      else if (this.moreMenuSetting === 'hapticFeedback') return this.hapticFeedbackItems
      else if (this.moreMenuSetting === 'language') return this.languageOptionItems
      else if (this.moreMenuSetting === 'theme') return this.themeOptionItems
      else if (this.moreMenuSetting === 'downloadUsingCellular') return this.downloadUsingCellularItems
      else if (this.moreMenuSetting === 'streamingUsingCellular') return this.streamingUsingCellularItems
      else if (this.moreMenuSetting === 'androidAutoBrowseSeriesSequenceOrder') return this.androidAutoBrowseSeriesSequenceOrderItems
      return []
    },
    contentPaddingStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { paddingBottom: '120px' } : {}
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
    showAndroidAutoBrowseSeriesSequenceOrderOptions() {
      this.moreMenuSetting = 'androidAutoBrowseSeriesSequenceOrder'
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
      } else if (this.moreMenuSetting === 'androidAutoBrowseSeriesSequenceOrder') {
        this.settings.androidAutoBrowseSeriesSequenceOrder = action
        this.saveSettings()
      }
    },
    saveTheme(theme) {
      console.log('=== THEME CHANGE DEBUG ===')
      console.log('New theme requested:', theme)
      console.log('Current document theme:', document.documentElement.dataset.theme)
      console.log('Dynamic colors enabled:', this.settings.enableDynamicColors)
      console.log('DynamicColor service available:', !!this.$dynamicColor)

      if (theme === 'system') {
        // Use system theme - detect and apply based on Android system preference
        const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
        console.log('System prefers dark mode:', prefersDark)
        document.documentElement.dataset.theme = prefersDark ? 'dark' : 'light'
        console.log('Applied document theme:', document.documentElement.dataset.theme)

        // Listen for system theme changes
        if (window.matchMedia) {
          const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
          mediaQuery.addEventListener('change', (e) => {
            if (this.theme === 'system') {
              console.log('System theme changed, new dark mode:', e.matches)
              document.documentElement.dataset.theme = e.matches ? 'dark' : 'light'
              // Reapply Material You colors for the new theme
              if (this.$dynamicColor && this.settings.enableDynamicColors) {
                console.log('Reapplying Material You colors for system theme change')
                this.$dynamicColor.initialize('system')
              }
            }
          })
        }
      } else if (theme === 'dark') {
        // Use Material You dark theme
        console.log('Applying dark theme to document')
        document.documentElement.dataset.theme = 'dark'
      } else if (theme === 'light') {
        // Use Material You light theme
        console.log('Applying light theme to document')
        document.documentElement.dataset.theme = 'light'
      }

      // Apply Material You colors for all themes if enabled - pass the theme parameter
      if (this.$dynamicColor && this.settings.enableDynamicColors) {
        console.log('Calling Material You initialize with theme:', theme)
        this.$dynamicColor.initialize(theme)
      } else if (!this.$dynamicColor) {
        console.log('DynamicColor service not available - Material You colors will not be applied')
      } else if (!this.settings.enableDynamicColors) {
        console.log('Dynamic colors disabled in settings - skipping Material You colors')
      }

      console.log('=== END THEME CHANGE DEBUG ===')

      this.$localStore.setTheme(theme)
    },
    autoSleepTimerTimeUpdated(val) {
      if (!val) return // invalid times return falsy
      this.saveSettings()
    },
    androidAutoBrowseLimitForGroupingUpdated(val) {
      if (!val) return // invalid times return falsy
      if (val > 1000) val = 1000
      if (val < 30) val = 30
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
    toggleSleepTimerAlmostDoneChime() {
      this.settings.enableSleepTimerAlmostDoneChime = !this.settings.enableSleepTimerAlmostDoneChime
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
    async toggleDynamicColors() {
      this.settings.enableDynamicColors = !this.settings.enableDynamicColors
      this.saveSettings()

      // Apply or remove dynamic colors immediately
      if (this.$dynamicColor) {
        if (this.settings.enableDynamicColors) {
          // Get current theme and pass it to initialize
          const currentTheme = this.theme || 'system'
          await this.$dynamicColor.initialize(currentTheme)
          this.$toast.info('Material You colors enabled', { timeout: 2000 })
        } else {
          // Clear dynamic colors and use static Material 3 theme
          this.$dynamicColor.clearDynamicColors()
          this.$toast.info('Using static Material 3 theme', { timeout: 2000 })
        }
      }
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
      this.settings.enableSleepTimerAlmostDoneChime = !!deviceSettings.enableSleepTimerAlmostDoneChime

      this.settings.autoSleepTimerAutoRewind = !!deviceSettings.autoSleepTimerAutoRewind
      this.settings.autoSleepTimerAutoRewindTime = !isNaN(deviceSettings.autoSleepTimerAutoRewindTime) ? deviceSettings.autoSleepTimerAutoRewindTime : 300000 // 5 minutes

      this.settings.languageCode = deviceSettings.languageCode || 'en-us'

      this.settings.downloadUsingCellular = deviceSettings.downloadUsingCellular || 'ALWAYS'
      this.settings.streamingUsingCellular = deviceSettings.streamingUsingCellular || 'ALWAYS'

      this.settings.enableDynamicColors = deviceSettings.enableDynamicColors !== undefined ? deviceSettings.enableDynamicColors : true

      this.settings.androidAutoBrowseLimitForGrouping = deviceSettings.androidAutoBrowseLimitForGrouping
      this.settings.androidAutoBrowseSeriesSequenceOrder = deviceSettings.androidAutoBrowseSeriesSequenceOrder || 'ASC'
    },
    async init() {
      this.loading = true
      this.theme = (await this.$localStore.getTheme()) || 'system'

      // Apply theme immediately
      this.saveTheme(this.theme)

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
