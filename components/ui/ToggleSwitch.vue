<template>
  <div class="state-layer rounded-full p-2 -m-2 transition-all duration-200 ease-standard" :class="!disabled ? 'cursor-pointer' : 'cursor-not-allowed'">
    <div
      class="material-3-switch rounded-full flex items-center relative transition-all duration-200 ease-standard"
      :class="trackClassName"
      @click.stop="clickToggle"
    >
      <div
        class="material-3-switch-thumb rounded-full shadow-elevation-1 flex items-center justify-center transition-all duration-200 ease-standard"
        :class="thumbClassName"
      >
        <!-- Material 3 switch icons -->
        <span v-if="toggleValue" class="material-symbols text-xs" :class="iconClassName">check</span>
        <span v-else class="material-symbols text-xs" :class="iconClassName">close</span>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    value: Boolean,
    disabled: Boolean,
    showIcons: {
      type: Boolean,
      default: true
    }
  },
  computed: {
    toggleValue: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    trackClassName() {
      var classes = ['w-13', 'h-8']

      if (this.disabled) {
        if (this.toggleValue) {
          classes.push('bg-on-surface opacity-12')
        } else {
          classes.push('bg-surface-variant opacity-12')
        }
      } else if (this.toggleValue) {
        classes.push('bg-primary')
      } else {
        classes.push('bg-surface-variant')
      }

      return classes.join(' ')
    },
    thumbClassName() {
      var classes = ['w-6', 'h-6', 'absolute']

      // Position
      if (this.toggleValue) {
        classes.push('translate-x-6_5')
      } else {
        classes.push('translate-x-0.5')
      }

      // Color
      if (this.disabled) {
        if (this.toggleValue) {
          classes.push('bg-surface opacity-100')
        } else {
          classes.push('bg-on-surface opacity-38')
        }
      } else if (this.toggleValue) {
        classes.push('bg-on-primary')
      } else {
        classes.push('bg-outline')
      }

      return classes.join(' ')
    },
    iconClassName() {
      var classes = []

      if (this.disabled) {
        if (this.toggleValue) {
          classes.push('text-on-surface opacity-38')
        } else {
          classes.push('text-surface')
        }
      } else if (this.toggleValue) {
        classes.push('text-on-primary-container')
      } else {
        classes.push('text-surface-container-highest')
      }

      return classes.join(' ')
    }
  },
  methods: {
    clickToggle() {
      if (this.disabled) return
      this.toggleValue = !this.toggleValue
    }
  }
}
</script>

<style scoped>
/* Material 3 Switch Styles */
.material-3-switch {
  width: 52px;
  height: 32px;
}

.material-3-switch-thumb {
  transition: transform var(--md-sys-motion-duration-short2) var(--md-sys-motion-easing-standard);
}

/* Switch track hover states */
.state-layer:hover .material-3-switch:not(.cursor-not-allowed) {
  box-shadow: var(--md-sys-elevation-level1);
}

/* Custom width for switch track */
.w-13 {
  width: 3.25rem;
}

.translate-x-6_5 {
  transform: translateX(1.625rem);
}
</style>
