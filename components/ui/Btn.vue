<template>
  <nuxt-link
    v-if="to"
    :to="to"
    class="material-3-button state-layer outline-none relative text-center transition-all duration-200 ease-standard"
    :disabled="disabled || loading"
    :class="classList"
  >
    <slot />
    <div v-if="loading" class="text-on-surface absolute top-0 left-0 w-full h-full flex items-center justify-center">
      <svg class="animate-spin" style="width: 24px; height: 24px" viewBox="0 0 24 24">
        <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
      </svg>
    </div>
  </nuxt-link>
  <button
    v-else
    class="material-3-button state-layer outline-none relative transition-all duration-200 ease-standard"
    :disabled="disabled || loading"
    :type="type"
    :class="classList"
    @mousedown.prevent
    @click="click"
  >
    <slot />
    <div v-if="loading" class="text-on-surface absolute top-0 left-0 w-full h-full flex items-center justify-center">
      <svg class="animate-spin" style="width: 24px; height: 24px" viewBox="0 0 24 24">
        <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
      </svg>
    </div>
  </button>
</template>

<script>
export default {
  props: {
    to: String,
    color: {
      type: String,
      default: 'primary'
    },
    variant: {
      type: String,
      default: 'filled' // filled, outlined, text, elevated, tonal
    },
    type: {
      type: String,
      default: ''
    },
    paddingX: Number,
    paddingY: Number,
    small: Boolean,
    loading: Boolean,
    disabled: Boolean
  },
  data() {
    return {}
  },
  computed: {
    classList() {
      var list = []

      if (this.loading) list.push('text-opacity-0')

      // Material 3 button variants
      if (this.variant === 'filled') {
        if (this.color === 'primary') {
          list.push('bg-primary text-on-primary shadow-elevation-0 hover:shadow-elevation-1')
        } else if (this.color === 'secondary') {
          list.push('bg-secondary text-on-secondary shadow-elevation-0 hover:shadow-elevation-1')
        } else if (this.color === 'tertiary') {
          list.push('bg-tertiary text-on-tertiary shadow-elevation-0 hover:shadow-elevation-1')
        } else if (this.color === 'error') {
          list.push('bg-error text-on-error shadow-elevation-0 hover:shadow-elevation-1')
        } else if (this.color === 'success') {
          list.push('bg-success text-white shadow-elevation-0 hover:shadow-elevation-1')
        }
      } else if (this.variant === 'outlined') {
        list.push('bg-transparent border border-outline')
        if (this.color === 'primary') {
          list.push('text-primary')
        } else if (this.color === 'secondary') {
          list.push('text-secondary')
        } else if (this.color === 'tertiary') {
          list.push('text-tertiary')
        } else if (this.color === 'error') {
          list.push('text-error')
        }
      } else if (this.variant === 'text') {
        list.push('bg-transparent')
        if (this.color === 'primary') {
          list.push('text-primary')
        } else if (this.color === 'secondary') {
          list.push('text-secondary')
        } else if (this.color === 'tertiary') {
          list.push('text-tertiary')
        } else if (this.color === 'error') {
          list.push('text-error')
        }
      } else if (this.variant === 'elevated') {
        list.push('bg-surface-container-low text-primary shadow-elevation-1 hover:shadow-elevation-2')
      } else if (this.variant === 'tonal') {
        if (this.color === 'primary') {
          list.push('bg-secondary-container text-on-secondary-container')
        } else if (this.color === 'secondary') {
          list.push('bg-secondary-container text-on-secondary-container')
        } else if (this.color === 'tertiary') {
          list.push('bg-tertiary-container text-on-tertiary-container')
        } else if (this.color === 'error') {
          list.push('bg-error-container text-on-error-container')
        }
      }

      // Size and padding
      if (this.small) {
        list.push('text-label-medium')
        if (this.paddingX === undefined) list.push('px-3')
        if (this.paddingY === undefined) list.push('py-2')
      } else {
        list.push('text-label-large')
        if (this.paddingX === undefined) list.push('px-6')
        if (this.paddingY === undefined) list.push('py-2.5')
      }

      if (this.paddingX !== undefined) {
        list.push(`px-${this.paddingX}`)
      }
      if (this.paddingY !== undefined) {
        list.push(`py-${this.paddingY}`)
      }

      if (this.disabled) {
        list.push('cursor-not-allowed opacity-38')
      }

      return list
    }
  },
  methods: {
    click(e) {
      this.$emit('click', e)
    }
  },
  mounted() {}
}
</script>

<style scoped>
/* Material 3 Button State Layers */
.material-3-button::before {
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

.material-3-button:hover:not(:disabled)::before {
  background-color: rgba(255, 255, 255, var(--md-sys-state-hover-opacity));
}

.material-3-button:focus:not(:disabled)::before {
  background-color: rgba(255, 255, 255, var(--md-sys-state-focus-opacity));
}

.material-3-button:active:not(:disabled)::before {
  background-color: rgba(255, 255, 255, var(--md-sys-state-pressed-opacity));
}

/* Outlined button state layers use surface color */
.material-3-button.bg-transparent:hover:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-primary), var(--md-sys-state-hover-opacity));
}

.material-3-button.bg-transparent:focus:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-primary), var(--md-sys-state-focus-opacity));
}

.material-3-button.bg-transparent:active:not(:disabled)::before {
  background-color: rgba(var(--md-sys-color-primary), var(--md-sys-state-pressed-opacity));
}

button:disabled::before {
  background-color: transparent;
}
</style>
