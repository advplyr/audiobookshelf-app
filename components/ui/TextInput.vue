<template>
  <input v-model="input" :type="type" :disabled="disabled" :placeholder="placeholder" class="px-2 py-1 bg-bg border border-gray-600 outline-none rounded-sm" :class="disabled ? 'text-gray-300' : 'text-white'" />
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
  methods: {},
  mounted() {}
}
</script>