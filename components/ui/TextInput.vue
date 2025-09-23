<template>
  <div class="relative">
    <input v-model="input" ref="input" :autofocus="autofocus" :type="type" :disabled="disabled" :readonly="readonly" autocorrect="off" autocapitalize="none" autocomplete="off" :placeholder="placeholder" class="material-3-input w-full outline-none transition-all duration-200 ease-standard text-body-large" :class="inputClass" @keyup="keyup" @focus="onFocus" @blur="onBlur" />
    <div v-if="prependIcon" class="absolute top-0 left-0 h-full px-3 flex items-center justify-center">
      <span class="material-symbols text-lg text-on-surface-variant">{{ prependIcon }}</span>
    </div>
    <div v-if="clearable && input" class="absolute top-0 right-0 h-full px-3 flex items-center justify-center cursor-pointer state-layer rounded-full" @click.stop="clear">
      <span class="material-symbols text-lg text-on-surface-variant hover:text-on-surface">close</span>
    </div>
    <div v-else-if="!clearable && appendIcon" class="absolute top-0 right-0 h-full px-3 flex items-center justify-center">
      <span class="material-symbols text-lg text-on-surface-variant">{{ appendIcon }}</span>
    </div>
    <div v-if="supportingText" class="mt-1 px-4">
      <span class="text-body-small text-on-surface-variant">{{ supportingText }}</span>
    </div>
    <div v-if="errorText" class="mt-1 px-4">
      <span class="text-body-small text-error">{{ errorText }}</span>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    value: [String, Number],
    placeholder: String,
    type: String,
    disabled: Boolean,
    readonly: Boolean,
    borderless: Boolean,
    autofocus: {
      type: Boolean,
      default: true
    },
    variant: {
      type: String,
      default: 'outlined' // filled, outlined - changed to outlined for better visibility
    },
    prependIcon: {
      type: String,
      default: null
    },
    appendIcon: {
      type: String,
      default: null
    },
    clearable: Boolean,
    supportingText: String,
    errorText: String
  },
  data() {
    return {
      focused: false
    }
  },
  computed: {
    input: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    inputClass() {
      var classes = []

      // Material 3 input variants
      if (this.variant === 'filled') {
        classes.push('bg-surface-container-highest')
        classes.push('rounded-t-md')
        classes.push('border-b-2')
        if (this.focused) {
          classes.push('border-primary')
        } else if (this.errorText) {
          classes.push('border-error')
        } else {
          classes.push('border-outline-variant')
        }
      } else if (this.variant === 'outlined') {
        // Material 3 outlined inputs should have surface background
        classes.push('bg-surface')
        classes.push('rounded-md')
        classes.push('border-2')
        if (this.focused) {
          classes.push('border-primary')
        } else if (this.errorText) {
          classes.push('border-error')
        } else {
          classes.push('border-outline')
        }
      }

      // Text color
      if (this.disabled) {
        classes.push('text-on-surface opacity-38')
      } else {
        classes.push('text-on-surface')
      }

      // Padding based on icons
      if (this.prependIcon && (this.clearable || this.appendIcon)) {
        classes.push('pl-14 pr-12')
      } else if (this.prependIcon) {
        classes.push('pl-14 pr-4')
      } else if (this.clearable || this.appendIcon) {
        classes.push('pl-4 pr-12')
      } else {
        classes.push('px-4')
      }

      // Height - ensure consistent height for proper icon positioning
      classes.push('h-14 py-4')

      return classes.join(' ')
    }
  },
  methods: {
    clear() {
      this.input = ''
      this.$emit('clear')
    },
    focus() {
      if (this.$refs.input) {
        this.$refs.input.focus()
        this.$refs.input.click()
      }
    },
    onFocus() {
      this.focused = true
      this.$emit('focus')
    },
    onBlur() {
      this.focused = false
      this.$emit('blur')
    },
    keyup() {
      if (this.$refs.input) {
        this.input = this.$refs.input.value
      }
    }
  },
  mounted() {}
}
</script>

<style scoped>
/* Material 3 Input Styles */
.material-3-input::placeholder {
  @apply text-on-surface-variant;
}

.material-3-input:focus::placeholder {
  @apply text-on-surface-variant opacity-60;
}

input[type='time']::-webkit-calendar-picker-indicator {
  filter: invert(100%);
}

html[data-theme='light'] input[type='time']::-webkit-calendar-picker-indicator {
  filter: unset;
}

/* Disable browser default focus styles */
.material-3-input:focus {
  outline: none;
  box-shadow: none;
}
</style>
