<template>
  <button class="material-3-icon-btn state-layer rounded-full flex items-center justify-center relative transition-all duration-200 ease-standard" :disabled="disabled || loading" :class="className" :type="type" @mousedown.prevent @click="clickBtn">
    <div v-if="loading" class="text-on-surface absolute top-0 left-0 w-full h-full flex items-center justify-center">
      <svg class="animate-spin" style="width: 24px; height: 24px" viewBox="0 0 24 24">
        <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
      </svg>
    </div>
    <span v-else class="material-symbols transition-colors duration-200 ease-standard" :class="iconClass" :style="{ fontSize }">{{ icon }}</span>
  </button>
</template>

<script>
export default {
  props: {
    icon: String,
    type: {
      type: String,
      default: 'button'
    },
    disabled: Boolean,
    variant: {
      type: String,
      default: 'standard' // standard, filled, tonal, outlined
    },
    color: {
      type: String,
      default: 'on-surface-variant'
    },
    size: {
      type: String,
      default: 'medium' // small, medium, large
    },
    outlined: Boolean,
    loading: Boolean
  },
  data() {
    return {}
  },
  computed: {
    className() {
      var classes = []

      // Size classes
      if (this.size === 'small') {
        classes.push('w-10 h-10')
      } else if (this.size === 'large') {
        classes.push('w-14 h-14')
      } else {
        classes.push('w-12 h-12')
      }

      // Material 3 icon button variants
      if (this.variant === 'filled') {
        classes.push('bg-primary text-on-primary shadow-elevation-2 hover:shadow-elevation-4')
      } else if (this.variant === 'tonal') {
        classes.push('bg-secondary-container text-on-secondary-container')
      } else if (this.variant === 'outlined') {
        classes.push('bg-transparent border border-outline text-on-surface-variant')
      } else {
        // Standard variant
        classes.push('bg-transparent')
      }

      // Disabled state
      if (this.disabled) {
        classes.push('cursor-not-allowed opacity-38')
      }

      return classes.join(' ')
    },
    iconClass() {
      var classes = []

      // Fill style - only for outlined variant
      if (this.outlined) {
        classes.push('fill')
      }

      // Icon color based on variant and state
      if (this.disabled) {
        classes.push('text-on-surface opacity-38')
      } else if (this.variant === 'filled') {
        classes.push('text-on-primary')
      } else if (this.variant === 'tonal') {
        classes.push('text-on-secondary-container')
      } else if (this.variant === 'outlined') {
        classes.push('text-on-surface-variant')
      } else {
        // Standard variant
        if (this.color === 'primary') {
          classes.push('text-primary')
        } else if (this.color === 'secondary') {
          classes.push('text-secondary')
        } else if (this.color === 'error') {
          classes.push('text-error')
        } else if (this.color === 'on-surface') {
          classes.push('text-on-surface')
        } else {
          classes.push('text-on-surface-variant')
        }
      }

      return classes.join(' ')
    },
    fontSize() {
      if (this.size === 'small') {
        return '1.2rem'
      } else if (this.size === 'large') {
        return '1.8rem'
      }

      // Specific icon adjustments
      if (this.icon === 'edit') return '1.25rem'
      return '1.875rem' // Match text-2xl (1.875rem) used in list item
    }
  },
  methods: {
    clickBtn(e) {
      if (this.disabled || this.loading) {
        e.preventDefault()
        return
      }
      e.preventDefault()
      this.$emit('click')
      e.stopPropagation()
    }
  },
  mounted() {}
}
</script>

<style scoped>
/* Material 3 Icon Button State Layers */
.material-3-icon-btn::before {
  content: '';
  position: absolute;
  border-radius: inherit;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-color: transparent;
  transition: background-color var(--md-sys-motion-duration-short2) var(--md-sys-motion-easing-standard);
  pointer-events: none;
}

/* Standard and outlined variants hover states */
.material-3-icon-btn.bg-transparent:hover:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-on-surface-variant), var(--md-sys-state-hover-opacity));
}

.material-3-icon-btn.bg-transparent:focus:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-on-surface-variant), var(--md-sys-state-focus-opacity));
}

.material-3-icon-btn.bg-transparent:active:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-on-surface-variant), var(--md-sys-state-pressed-opacity));
}

/* Filled variant hover states */
.material-3-icon-btn.bg-primary:hover:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-on-primary), var(--md-sys-state-hover-opacity));
}

.material-3-icon-btn.bg-primary:focus:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-on-primary), var(--md-sys-state-focus-opacity));
}

.material-3-icon-btn.bg-primary:active:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-on-primary), var(--md-sys-state-pressed-opacity));
}

/* Tonal variant hover states */
.material-3-icon-btn.bg-secondary-container:hover:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-on-secondary-container), var(--md-sys-state-hover-opacity));
}

.material-3-icon-btn.bg-secondary-container:focus:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-on-secondary-container), var(--md-sys-state-focus-opacity));
}

.material-3-icon-btn.bg-secondary-container:active:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-on-secondary-container), var(--md-sys-state-pressed-opacity));
}

/* Disabled state */
.material-3-icon-btn:disabled {
  cursor: not-allowed;
}

.material-3-icon-btn:disabled::before {
  background-color: transparent;
}
</style>
