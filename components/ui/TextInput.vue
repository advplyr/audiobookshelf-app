<template>
  <div class="relative">
    <input v-model="input" ref="input" autofocus :type="type" :disabled="disabled" :readonly="readonly" autocorrect="off" autocapitalize="none" autocomplete="off" :placeholder="placeholder" class="py-2 w-full outline-none bg-primary" :class="inputClass" @keyup="keyup" />
    <div v-if="prependIcon" class="absolute top-0 left-0 h-full px-2 flex items-center justify-center">
      <span class="material-icons text-lg">{{ prependIcon }}</span>
    </div>
    <div v-if="clearable && input" class="absolute top-0 right-0 h-full px-2 flex items-center justify-center" @click.stop="clear">
      <span class="material-icons text-lg">close</span>
    </div>
    <div v-else-if="!clearable && appendIcon" class="absolute top-0 right-0 h-full px-2 flex items-center justify-center">
      <span class="material-icons text-lg">{{ appendIcon }}</span>
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
    bg: {
      type: String,
      default: 'bg'
    },
    rounded: {
      type: String,
      default: 'sm'
    },
    prependIcon: {
      type: String,
      default: null
    },
    appendIcon: {
      type: String,
      default: null
    },
    clearable: Boolean
  },
  data() {
    return {}
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
      var classes = [`bg-${this.bg}`, `rounded-${this.rounded}`]
      if (this.disabled) classes.push('text-fg-muted')
      else classes.push('text-fg')

      if (this.prependIcon) classes.push('pl-10 pr-2')
      else classes.push('px-2')

      if (!this.borderless) classes.push('border border-border')
      return classes.join(' ')
    }
  },
  methods: {
    clear() {
      this.input = ''
    },
    focus() {
      if (this.$refs.input) {
        this.$refs.input.focus()
        this.$refs.input.click()
      }
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
input[type='time']::-webkit-calendar-picker-indicator {
  filter: invert(100%);
}
</style>