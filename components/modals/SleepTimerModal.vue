<template>
  <modals-modal v-model="show" :width="200" height="100%">
    <template #outer>
      <div class="absolute top-5 left-4 z-40">
        <p class="text-white text-2xl truncate">Sleep Timer</p>
      </div>
    </template>

    <div
      class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center"
      @click="
        show = false
        manualTimerModal = false
      "
    >
      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-white border-opacity-20" style="max-height: 75%" @click.stop>
        <div v-if="manualTimerModal" class="p-4">
          <div class="flex mb-4" @click="manualTimerModal = false">
            <span class="material-icons text-3xl">arrow_back</span>
          </div>
          <div class="flex my-2 justify-between">
            <ui-btn @click="decreaseManualTimeout" class="w-9 h-9" :padding-x="0" small style="max-width: 36px"><span class="material-icons">remove</span></ui-btn>
            <p class="text-2xl font-mono text-center">{{ manualTimeoutMin }} min</p>
            <ui-btn @click="increaseManualTimeout" class="w-9 h-9" :padding-x="0" small style="max-width: 36px"><span class="material-icons">add</span></ui-btn>
          </div>
          <ui-btn @click="clickedOption(manualTimeoutMin)" class="w-full">Set Timer</ui-btn>
        </div>
        <ul v-else-if="!sleepTimerRunning" class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="timeout in timeouts">
            <li :key="timeout" class="text-gray-50 select-none relative py-4 cursor-pointer hover:bg-black-400" role="option" @click="clickedOption(timeout)">
              <div class="flex items-center justify-center">
                <span class="font-normal block truncate text-lg">{{ timeout }} min</span>
              </div>
            </li>
          </template>
          <li v-if="currentEndOfChapterTime" class="text-gray-50 select-none relative py-4 cursor-pointer hover:bg-black-400" role="option" @click="clickedChapterOption(timeout)">
            <div class="flex items-center justify-center">
              <span class="font-normal block truncate text-lg text-center">End of Chapter</span>
            </div>
          </li>
          <li class="text-gray-50 select-none relative py-4 cursor-pointer hover:bg-black-400" role="option" @click="manualTimerModal = true">
            <div class="flex items-center justify-center">
              <span class="font-normal block truncate text-lg text-center">Custom time</span>
            </div>
          </li>
        </ul>
        <div v-else class="p-4">
          <div class="flex my-2 justify-between">
            <ui-btn @click="decreaseSleepTime" class="w-9 h-9" :padding-x="0" small style="max-width: 36px"><span class="material-icons">remove</span></ui-btn>
            <p class="text-2xl font-mono text-center">{{ timeRemainingPretty }}</p>
            <ui-btn @click="increaseSleepTime" class="w-9 h-9" :padding-x="0" small style="max-width: 36px"><span class="material-icons">add</span></ui-btn>
          </div>

          <ui-btn @click="cancelSleepTimer" class="w-full">{{ isAuto ? 'Disable Auto Timer' : 'Cancel Timer' }}</ui-btn>
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
      manualTimerModal: null,
      manualTimeoutMin: 1
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
    timeouts() {
      return [5, 10, 15, 30, 45, 60, 90]
    },
    timeRemainingPretty() {
      return this.$secondsToTimestamp(this.currentTime)
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
          message: 'Are you sure you want to disable the auto sleep timer? You will need to enable this again in settings.'
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
