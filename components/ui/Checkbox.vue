<template>
  <label class="flex justify-start items-center state-layer rounded-lg p-2 -m-2 transition-all duration-200 ease-standard" :class="!disabled ? 'cursor-pointer' : 'cursor-not-allowed'">
    <div class="border-2 rounded-sm flex flex-shrink-0 justify-center items-center relative overflow-hidden transition-all duration-200 ease-standard" :class="wrapperClass">
      <input v-model="selected" :disabled="disabled" type="checkbox" class="opacity-0 absolute" :class="!disabled ? 'cursor-pointer' : ''" />
      <svg v-if="selected" class="fill-current pointer-events-none transition-all duration-200 ease-standard" :class="svgClass" viewBox="0 0 20 20">
        <path d="M0 11l2-2 5 5L18 3l2 2L7 18z" />
      </svg>
      <!-- Material 3 ripple effect container -->
      <div class="absolute inset-0 rounded-sm overflow-hidden">
        <div class="absolute inset-0 bg-primary opacity-0 transition-opacity duration-200 ease-standard" :class="{ 'opacity-12': selected && !disabled }"></div>
      </div>
    </div>
    <div v-if="label" class="select-none transition-colors duration-200 ease-standard" :class="labelClassname">{{ label }}</div>
  </label>
</template>

<script>
export default {
  props: {
    value: Boolean,
    label: String,
    small: Boolean,
    disabled: Boolean
  },
  data() {
    return {}
  },
  computed: {
    selected: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', !!val)
      }
    },
    wrapperClass() {
      var classes = []

      // Size
      if (this.small) {
        classes.push('w-4 h-4')
      } else {
        classes.push('w-5 h-5')
      }

      // Material 3 checkbox states
      if (this.disabled) {
        if (this.selected) {
          classes.push('bg-on-surface border-on-surface opacity-38')
        } else {
          classes.push('bg-surface border-on-surface opacity-38')
        }
      } else if (this.selected) {
        classes.push('bg-primary border-primary')
      } else {
        classes.push('bg-surface border-outline')
      }

      return classes.join(' ')
    },
    labelClassname() {
      var classes = ['pl-3']

      // Typography
      if (this.small) {
        classes.push('text-body-small')
      } else {
        classes.push('text-body-medium')
      }

      // Color
      if (this.disabled) {
        classes.push('text-on-surface opacity-38')
      } else {
        classes.push('text-on-surface')
      }

      return classes.join(' ')
    },
    svgClass() {
      var classes = []

      // Size
      if (this.small) {
        classes.push('w-3 h-3')
      } else {
        classes.push('w-3.5 h-3.5')
      }

      // Color
      if (this.disabled) {
        classes.push('text-surface opacity-38')
      } else {
        classes.push('text-on-primary')
      }

      return classes.join(' ')
    }
  },
  methods: {},
  mounted() {}
}
</script>

<style scoped>
/* Material 3 Checkbox State Layers */
label:hover .state-layer:not(.cursor-not-allowed)::before {
  background-color: rgba(var(--md-sys-color-primary), var(--md-sys-state-hover-opacity));
}

label:focus-within .state-layer::before {
  background-color: rgba(var(--md-sys-color-primary), var(--md-sys-state-focus-opacity));
}

/* Custom opacity classes */
.opacity-12 {
  opacity: 0.12;
}

.opacity-38 {
  opacity: 0.38;
}
</style>
