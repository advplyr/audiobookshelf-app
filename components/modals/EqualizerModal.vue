<template>
  <modals-modal v-model="show" :width="400" height="100%">
    <template #outer>
      <div class="absolute top-11 left-4 z-40">
        <p class="text-white text-2xl truncate">Equalizer</p>
      </div>
    </template>

    <div
      class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center"
      @click="show = false"
    >
      <div
        class="w-full rounded-lg bg-primary border border-border overflow-hidden relative mt-16"
        style="max-height: 80vh"
        @click.stop
      >
        <!-- EQ BODY -->
        <div class="p-4">
          <div class="flex justify-center">
            <div
              v-for="(band, i) in bands"
              :key="i"
              class="flex flex-col items-center justify-end w-12"
            >

              <!-- Slider wrapper -->
              <div class="relative h-40 w-6 flex items-center justify-center">
                <input
                  type="range"
                  min="-1500"
                  max="1500"
                  step="50"
                  :value="band.gain"
                  @input="onBandInput(i, $event.target.valueAsNumber)"
                  @mousedown="activeBand = i"
                  @touchstart="activeBand = i"
                  class="-rotate-90 w-40 h-6"
                />
              </div>

              <!-- Gain -->
              <p class="text-xs text-fg/60 mb-1">
                {{ band.gain > 0 ? '+' : '' }}{{ (band.gain / 100).toFixed(1) }}
              </p>

              <!-- Frequency -->
              <p class="text-xs text-fg/50 mt-2">
                {{ band.label }}
              </p>
            </div>
          </div>
        </div>

        <div class="flex flex-row align-center justify-around">
          <!-- RESET -->
          <div
            class="flex items-center justify-center border-t border-fg/10 p-3 cursor-pointer select-none"
            @click="reset"
          >
            <span class="material-symbols mr-2">restart_alt</span>
            Reset
          </div>

          <!--- LINKED BANDS TOGGLE -->
          <div
            class="flex items-center gap-3 border-t border-fg/10 p-3 cursor-pointer select-none"
            @click="linkBandsEnabled = !linkBandsEnabled"
          >
            <!-- Checkbox -->
            <div
              class="w-5 h-5 rounded border flex items-center justify-center
                    transition
                    "
              :class="linkBandsEnabled
                ? 'border-success text-white'
                : 'border-fg/40'"
            >
              <span
                v-if="linkBandsEnabled"
                class="material-symbols text-sm"
              >
                check
              </span>
            </div>

            <span class="text-sm select-none">
              Link bands
            </span>
          </div>
        </div>
      </div>
    </div>
  </modals-modal>
</template>


<script>
export default {
  props: {
    value: Boolean,
    equalizerSettings: {
      type: Array,
      default: () => ({ bands: [] }) // Band frequencies have to be decided by the equalizer implementation, as they are device specific. So a default is not safe
    }
  },
  data() {
    return {
      bands: [],
      activeBand: null,
      linkBandsEnabled: true
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
    }
  },
  watch: {
    value(newVal, oldVal) {
      // If the modal was open, and then is set to closed: save the settings
      if (oldVal === true && newVal === false) {
        this.$emit('save', this.bands)
      }
    },
    equalizerSettings() { // Don't care about the new value, only that it has been changed
      this.setBands()
    }
  },
  mounted() {
    this.setBands()
  },
  methods: {
    setBands() {
      this.bands = this.equalizerSettings.bands.map(band => ({
        freq: band.freq,
        label: band.freq >= 1000 ? `${band.freq / 1000}kHz` : `${band.freq}Hz`,
        gain: band.gain
      }))
    },
    onBandInput(index, newGain) {
      if (!this.linkBandsEnabled) {
        this.bands[index].gain = newGain
        this.emitChange()
        return
      }

      const delta = newGain - this.bands[index].gain
      const strength = 0.85 // lower strength means wider influence
      // Ignores bands further away than round(X*totalBands)
      // eg. if set to 0.3, with 8 bands. round(0.3*8) = 2, so only the adjacent 2 left and right will modified
      const distanceCutoff = 0.3 

      this.bands.forEach((band, i) => {
        const distance = Math.abs(i - index)

        // If within distance threshold, make changes
        if (distance <= Math.round(distanceCutoff * this.bands.length)) {
          const influence = Math.exp(-distance * strength)
          band.gain += delta * influence
          band.gain = Math.max(-1500, Math.min(1500, band.gain))
        }
      })

      this.emitChange()
    },
    emitChange() {
      this.$emit(
        'change',
        this.bands
      )
    },
    reset() {
      this.bands.forEach(b => (b.gain = 0))
      this.emitChange()
    }
  }
}
</script>
