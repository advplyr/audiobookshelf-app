<template>
  <div class="tv-focus-color-picker flex items-center gap-3">
    <button
      v-for="preset in PRESETS"
      :key="preset.hex"
      type="button"
      tabindex="0"
      class="swatch"
      :style="{ backgroundColor: preset.hex }"
      :aria-label="`Focus ring color: ${preset.name}${preset.hex === value ? ' (selected)' : ''}`"
      @click="select(preset.hex)"
      @keydown.enter.prevent="select(preset.hex)"
    >
      <span v-if="preset.hex === value" class="selected-marker" aria-hidden="true">★</span>
    </button>
  </div>
</template>

<script>
export default {
  props: {
    value: {
      type: String,
      default: '#1ad691'
    }
  },
  data() {
    return {
      PRESETS: [
        { hex: '#1ad691', name: 'ABS Green' },
        { hex: '#3ea6ff', name: 'Sky' },
        { hex: '#ffb74d', name: 'Amber' },
        { hex: '#ff5252', name: 'Red' },
        { hex: '#e040fb', name: 'Violet' },
        { hex: '#ffeb3b', name: 'Yellow' },
        { hex: '#ffffff', name: 'White' }
      ]
    }
  },
  methods: {
    select(hex) {
      if (hex !== this.value) {
        this.$emit('input', hex)
      }
    }
  }
}
</script>

<style scoped>
.swatch {
  position: relative;
  width: 44px;
  height: 44px;
  border-radius: 6px;
  border: none;
  cursor: pointer;
  padding: 0;
}

/* TV D-pad focus indicator. The global .android-tv button:focus rule paints
   in var(--tv-focus-color), which blends invisibly into a same-colored
   swatch fill. Override with a black inner separator + outer ring in the
   current focus color — the black band keeps the ring readable even when
   the focused swatch is the same color as the focus ring itself.
   box-shadow takes no layout space, so swatches don't shift when focused. */
.swatch:focus,
.swatch:focus-visible {
  outline: 2px solid transparent !important;
  box-shadow: 0 0 0 2px #000, 0 0 0 4px var(--tv-focus-color, #1ad691);
}

/* In-use marker, top-right corner. White glyph + black halo reads on every
   swatch fill including white (the halo provides the silhouette there). */
.selected-marker {
  position: absolute;
  top: 2px;
  right: 4px;
  font-size: 12px;
  color: #ffffff;
  text-shadow: 0 0 2px #000, 0 0 3px rgba(0, 0, 0, 0.6);
  pointer-events: none;
}
</style>
