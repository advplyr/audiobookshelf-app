<template>
  <div class="w-full">
    <p class="pb-0.5 text-sm font-semibold">{{ label }}</p>
    <ui-text-input v-model="inputValue" :disabled="disabled" :type="type" text-size="base" class="w-full" />
  </div>
</template>

<script>
export default {
  props: {
    value: [String, Number],
    label: String,
    type: {
      type: String,
      default: 'text'
    },
    disabled: Boolean
  },
  data() {
    return {}
  },
  computed: {
    inputValue: {
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