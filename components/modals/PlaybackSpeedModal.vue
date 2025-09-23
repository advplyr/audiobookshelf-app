<template>
  <modals-modal v-model="show" @input="modalInput" :width="200" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" data-modal-backdrop>
      <div class="w-full overflow-x-hidden overflow-y-auto bg-surface rounded-2xl border border-outline-variant shadow-elevation-4 backdrop-blur-md" style="max-height: 75%">
        <!-- Material 3 Modal Header -->
        <div class="px-6 py-4 border-b border-outline-variant">
          <h2 class="text-headline-small text-on-surface font-medium">{{ $strings.LabelPlaybackSpeed }}</h2>
        </div>

        <ul class="w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="rate in rates">
            <li :key="rate" class="text-on-surface select-none relative py-4 cursor-pointer state-layer" :class="rate === selected ? 'bg-primary-container text-on-primary-container' : ''" role="option" @click="clickedOption(rate)">
              <div class="flex items-center justify-center">
                <span class="font-normal block truncate text-lg">{{ rate }}x</span>
              </div>
            </li>
          </template>
        </ul>
        <div class="flex items-center justify-center py-3 border-t border-outline-variant">
          <button :disabled="!canDecrement" @click.stop="decrementClick" class="w-8 h-8 text-on-surface-variant rounded border border-outline-variant flex items-center justify-center state-layer disabled:opacity-50">
            <span class="material-symbols text-on-surface">remove</span>
          </button>
          <div class="w-24 text-center">
            <p class="text-xl text-on-surface">{{ playbackRate }}<span class="text-lg">⨯</span></p>
          </div>
          <button :disabled="!canIncrement" @click.stop="incrementClick" class="w-8 h-8 text-on-surface-variant rounded border border-outline-variant flex items-center justify-center state-layer disabled:opacity-50">
            <span class="material-symbols text-on-surface">add</span>
          </button>
        </div>
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    playbackRate: Number
  },
  data() {
    return {
      currentPlaybackRate: 0,
      MIN_SPEED: 0.5,
      MAX_SPEED: 10
    }
  },
  watch: {
    show(newVal) {
      if (newVal) {
        this.currentPlaybackRate = this.selected
      }
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
    selected: {
      get() {
        return this.playbackRate
      },
      set(val) {
        this.$emit('update:playbackRate', val)
      }
    },
    rates() {
      return [0.5, 1, 1.2, 1.5, 1.7, 2, 3]
    },
    canIncrement() {
      return this.playbackRate + 0.1 <= this.MAX_SPEED
    },
    canDecrement() {
      return this.playbackRate - 0.1 >= this.MIN_SPEED
    }
  },
  methods: {
    incrementClick() {
      this.increment()
    },
    decrementClick() {
      this.decrement()
    },
    async increment() {
      await this.$hapticsImpact()
      if (this.selected + 0.1 > this.MAX_SPEED) return
      var newPlaybackRate = this.selected + 0.1
      this.selected = Number(newPlaybackRate.toFixed(1))
    },
    async decrement() {
      await this.$hapticsImpact()
      if (this.selected - 0.1 < this.MIN_SPEED) return
      var newPlaybackRate = this.selected - 0.1
      this.selected = Number(newPlaybackRate.toFixed(1))
    },
    modalInput(val) {
      if (!val) {
        if (this.currentPlaybackRate !== this.selected) {
          this.$emit('change', this.selected)
        }
      }
    },
    clickedOption(rate) {
      this.selected = Number(rate)
      this.show = false
      this.$emit('change', Number(rate))
    }
  },
  mounted() {}
}
</script>

<style>
button.icon-num-btn:disabled {
  cursor: not-allowed;
}
button.icon-num-btn:disabled::before {
  background-color: rgb(var(--md-sys-color-surface-variant) / 0.3);
}
button.icon-num-btn:disabled span {
  color: rgb(var(--md-sys-color-on-surface-variant));
}
</style>
