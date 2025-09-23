<template>
  <modals-modal v-model="show" :width="200" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" data-modal-backdrop>
      <div class="w-full overflow-x-hidden overflow-y-auto bg-surface rounded-lg border border-outline-variant shadow-elevation-4 backdrop-blur-md" style="max-height: 75%">
        <!-- Material 3 Modal Header -->
        <div class="px-6 py-4 border-b border-outline-variant">
          <h2 class="text-headline-small text-on-surface font-medium">{{ $strings.HeaderSleepTimer }}</h2>
        </div>

        <div v-if="manualTimerModal" class="p-4">
          <div class="flex mb-4" @click="manualTimerModal = false">
            <span class="material-symbols text-3xl text-on-surface">arrow_back</span>
          </div>
          <div class="flex my-2 justify-between">
            <ui-btn @click.stop="decreaseManualTimeout" class="w-9 h-9" :padding-x="0" small style="max-width: 36px"><span class="material-symbols text-on-surface">remove</span></ui-btn>
            <p class="text-2xl font-mono text-center text-on-surface">{{ manualTimeoutMin }} min</p>
            <ui-btn @click.stop="increaseManualTimeout" class="w-9 h-9" :padding-x="0" small style="max-width: 36px"><span class="material-symbols text-on-surface">add</span></ui-btn>
          </div>
          <ui-btn @click="clickedOption(manualTimeoutMin)" class="w-full">{{ $strings.ButtonSetTimer }}</ui-btn>
        </div>
        <ul v-else class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="timeout in timeouts">
            <li :key="timeout" class="text-on-surface select-none relative py-4 cursor-pointer state-layer" role="option" @click="clickedOption(timeout)">
              <div class="flex items-center justify-center">
                <span class="font-normal block truncate text-lg">{{ timeout }} min</span>
              </div>
            </li>
          </template>
          <li class="text-on-surface select-none relative py-4 cursor-pointer state-layer" role="option" @click="clickedChapterOption">
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
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean
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
    }
  },
  methods: {
    async clickedChapterOption() {
      await this.$hapticsImpact()
      this.show = false
      this.$nextTick(() => this.$emit('change', 0))
    },
    async clickedOption(timeoutMin) {
      await this.$hapticsImpact()
      const timeout = timeoutMin * 1000 * 60
      this.show = false
      this.manualTimerModal = false
      this.$nextTick(() => this.$emit('change', timeout))
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
