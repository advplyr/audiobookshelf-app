<template>
  <div class="inline-flex">
    <input v-model="input" type="range" :min="min" :max="max" :step="step" :style="{ width: inputWidth }" />

    <p class="text-xs ml-2">{{ input }}%</p>
  </div>
</template>

<script>
export default {
  props: {
    value: [String, Number],
    min: Number,
    max: Number,
    step: Number,
    inputWidth: {
      type: String,
      default: 'unset'
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
    }
  },
  methods: {},
  mounted() {}
}
</script>

<style scoped>
input[type='range'] {
  -webkit-appearance: none;
  appearance: none;
  background: transparent;
  cursor: pointer;
}
input[type='range']:focus {
  outline: none;
}

/* chromium */
input[type='range']::-webkit-slider-runnable-track {
  background-color: rgb(var(--color-track) / 0.5);
  border-radius: 9999px;
  height: 0.75rem;
}
input[type='range']::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  margin-top: -0.25rem;
  border-radius: 9999px;
  background-color: rgb(var(--color-track-cursor));
  height: 1.25rem;
  width: 1.25rem;
}
input[type='range']:focus::-webkit-slider-thumb {
  border: 1px solid rgb(var(--color-track));
  outline: 3px solid rgb(var(--color-track));
  outline-offset: 0.125rem;
}

/* firefox */
input[type='range']::-moz-range-track {
  background-color: rgb(var(--color-track) / 0.5);
  border-radius: 9999px;
  height: 0.75rem;
}
input[type='range']::-moz-range-thumb {
  border: none;
  border-radius: 9999px;
  margin-top: -0.25rem;
  background-color: rgb(var(--color-track-cursor));
  height: 1.25rem;
  width: 1.25rem;
}
input[type='range']:focus::-moz-range-thumb {
  border: 1px solid rgb(var(--color-track));
  outline: 3px solid rgb(var(--color-track));
  outline-offset: 0.125rem;
}
</style>