<template>
  <input v-model="input" ref="input" autofocus :type="type" :disabled="disabled" autocorrect="off" autocapitalize="none" autocomplete="off" :placeholder="placeholder" class="px-2 py-1 bg-bg border border-gray-600 outline-none rounded-sm" :class="disabled ? 'text-gray-300' : 'text-white'" @keyup="keyup" />
</template>

<script>
export default {
  props: {
    value: [String, Number],
    placeholder: String,
    type: String,
    disabled: Boolean
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