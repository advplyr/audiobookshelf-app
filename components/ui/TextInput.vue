<template>
  <div class="relative">
    <input v-model="input" ref="input" autofocus :type="type" :disabled="disabled" autocorrect="off" autocapitalize="none" autocomplete="off" :placeholder="placeholder" class="py-2 w-full outline-none bg-primary" :class="inputClass" @keyup="keyup" />
    <div v-if="prependIcon" class="absolute top-0 left-0 h-full px-2 flex items-center justify-center">
      <span class="material-icons text-lg">{{ prependIcon }}</span>
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
    }
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
      if (this.disabled) classes.push('text-gray-300')
      else classes.push('text-white')

      if (this.prependIcon) classes.push('pl-10 pr-2')
      else classes.push('px-2')

      if (!this.borderless) classes.push('border border-gray-600')
      return classes.join(' ')
    }
  },
  methods: {
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