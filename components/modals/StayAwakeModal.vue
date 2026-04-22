<template>
  <modals-modal v-model="show" :width="280">
    <div class="p-6 text-center">
      <p class="text-lg font-semibold mb-2">{{ $strings.LabelStillListening || 'Still listening?' }}</p>
      <p class="text-sm text-fg-muted mb-1">
        {{ missedChecks === 0 ? '' : missedChecks === 1 ? 'Volume lowered — tap to confirm' : '' }}
      </p>
      <div class="flex justify-center mt-4">
        <div
          class="w-20 h-20 rounded-full bg-primary flex items-center justify-center cursor-pointer active:scale-95 transition-transform"
          @click="confirmAwake"
        >
          <span class="material-symbols text-4xl text-white">check</span>
        </div>
      </div>
      <p class="text-xs text-fg-muted mt-3">
        {{ timeRemaining }}s
      </p>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    missedChecks: { type: Number, default: 0 },
    responseWindowSec: { type: Number, default: 60 }
  },
  data() {
    return {
      timeRemaining: this.responseWindowSec,
      countdownInterval: null
    }
  },
  computed: {
    show: {
      get() { return this.value },
      set(v) { this.$emit('input', v) }
    }
  },
  watch: {
    value(v) {
      if (v) {
        this.timeRemaining = this.responseWindowSec
        this.startCountdown()
      } else {
        this.stopCountdown()
      }
    }
  },
  methods: {
    confirmAwake() {
      this.show = false
      this.$emit('confirm')
    },
    startCountdown() {
      this.stopCountdown()
      this.countdownInterval = setInterval(() => {
        this.timeRemaining--
        if (this.timeRemaining <= 0) {
          this.stopCountdown()
          this.show = false
        }
      }, 1000)
    },
    stopCountdown() {
      if (this.countdownInterval) {
        clearInterval(this.countdownInterval)
        this.countdownInterval = null
      }
    }
  },
  beforeDestroy() {
    this.stopCountdown()
  }
}
</script>
