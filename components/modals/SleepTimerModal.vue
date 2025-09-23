<template>
  <modals-modal v-model="show" :width="200" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" data-modal-backdrop>
      <div class="w-full overflow-x-hidden overflow-y-auto bg-surface rounded-2xl border border-outline-variant shadow-elevation-4 backdrop-blur-md" style="max-height: 75%">
        <!-- Material 3 Modal Header -->
        <div class="px-6 py-4 border-b border-outline-variant">
          <h2 class="text-headline-small text-on-surface font-medium">{{ $strings.HeaderSleepTimer }}</h2>
        </div>

        <div v-if="manualTimerModal" class="p-4">
          <div class="flex mb-4 cursor-pointer state-layer rounded-full p-1 w-fit" @click="manualTimerModal = false">
            <span class="material-symbols text-3xl text-on-surface">arrow_back</span>
          </div>
          <div class="flex my-2 justify-between">
            <ui-btn @click="decreaseManualTimeout" class="w-9 h-9" :padding-x="0" small style="max-width: 36px"><span class="material-symbols text-lg text-on-surface">remove</span></ui-btn>
            <p class="text-2xl font-mono text-center text-on-surface">{{ manualTimeoutMin }} min</p>
            <ui-btn @click="increaseManualTimeout" class="w-9 h-9" :padding-x="0" small style="max-width: 36px"><span class="material-symbols text-lg text-on-surface">add</span></ui-btn>
          </div>
          <ui-btn @click="clickedOption(manualTimeoutMin)" class="w-full">{{ $strings.ButtonSetTimer }}</ui-btn>
        </div>
        <ul v-else-if="!sleepTimerRunning" class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="timeout in timeouts">
            <li :key="timeout" class="text-on-surface select-none relative py-4 cursor-pointer state-layer" role="option" @click="clickedOption(timeout)">
              <div class="flex items-center justify-center">
                <span class="font-normal block truncate text-lg">{{ timeout }} min</span>
              </div>
            </li>
          </template>
          <li v-if="currentEndOfChapterTime" class="text-on-surface select-none relative py-4 cursor-pointer state-layer" role="option" @click="clickedChapterOption(timeout)">
            <div class="flex items-center justify-center">
              <span class="font-normal block truncate text-lg text-center">{{ $strings.LabelEndOfChapter }}</span>
            </div>
          </li>
          <li class="text-on-surface select-none relative py-4 cursor-pointer state-layer" role="option" @click="manualTimerModal = true">
            <div class="flex items-center justify-center">
              <span class="font-normal block truncate text-lg text-center">{{ $strings.LabelCustomTime }}</span>
            </div>
          </li>
        </ul>
        <div v-else class="p-4">
          <div class="flex my-2 justify-between">
            <ui-btn @click="decreaseSleepTime" class="w-9 h-9" :padding-x="0" small style="max-width: 36px"><span class="material-symbols text-lg text-on-surface">remove</span></ui-btn>
            <p class="text-2xl font-mono text-center text-on-surface">{{ timeRemainingPretty }}</p>
            <ui-btn @click="increaseSleepTime" class="w-9 h-9" :padding-x="0" small style="max-width: 36px"><span class="material-symbols text-lg text-on-surface">add</span></ui-btn>
          </div>

          <ui-btn @click="cancelSleepTimer" class="w-full">{{ isAuto ? $strings.ButtonDisableAutoTimer : $strings.ButtonCancelTimer }}</ui-btn>
        </div>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  props: {
    value: Boolean,
    currentTime: Number,
    sleepTimerRunning: Boolean,
    currentEndOfChapterTime: Number,
    isAuto: Boolean
  },
  data() {
    return {
      manualTimerModal: false,
      manualTimeoutMin: 1
    }
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        if (!val) {
          this.manualTimerModal = false
        }
        this.$emit('input', val)
      }
    },
    timeouts() {
      return [5, 10, 15, 30, 45, 60, 90]
    },
    timeRemainingPretty() {
      if (this.currentTime <= 0) return '0:00'
      return this.$secondsToTimestamp(this.currentTime)
    },
    isIos() {
      return this.$platform === 'ios'
    }
  },
  methods: {
    async clickedChapterOption() {
      await this.$hapticsImpact()
      this.show = false
      this.$nextTick(() => this.$emit('change', { time: this.currentEndOfChapterTime * 1000, isChapterTime: true }))
    },
    async clickedOption(timeoutMin) {
      await this.$hapticsImpact()
      const timeout = timeoutMin * 1000 * 60
      this.show = false
      this.manualTimerModal = false
      this.$nextTick(() => this.$emit('change', { time: timeout, isChapterTime: false }))
    },
    async cancelSleepTimer() {
      if (this.isAuto) {
        const { value } = await Dialog.confirm({
          title: 'Confirm',
          message: this.$strings.MessageConfirmDisableAutoTimer
        })
        if (!value) return
      }

      await this.$hapticsImpact()
      this.$emit('cancel')
      this.show = false
    },
    async increaseSleepTime() {
      await this.$hapticsImpact()
      this.$emit('increase')
    },
    async decreaseSleepTime() {
      await this.$hapticsImpact()
      this.$emit('decrease')
    },
    async increaseManualTimeout() {
      await this.$hapticsImpact()
      this.manualTimeoutMin++
    },
    async decreaseManualTimeout() {
      await this.$hapticsImpact()
      if (this.manualTimeoutMin > 1) this.manualTimeoutMin--
    }
  },
  mounted() {}
}
</script>
