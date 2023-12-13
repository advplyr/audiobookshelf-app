<template>
  <div class="inline-flex toggle-btn-wrapper shadow-md">
    <button v-for="item in items" :key="item.value" type="button" class="toggle-btn outline-none relative border border-border px-4 py-1" :class="{ selected: item.value === value }" @click.stop="clickBtn(item.value)">
      {{ item.text }}
    </button>
  </div>
</template>

<script>
export default {
  props: {
    value: String,
    items: {
      type: Array,
      default: Object
    }
  },
  data() {
    return {}
  },
  computed: {},
  methods: {
    clickBtn(value) {
      this.$emit('input', value)
    }
  },
  mounted() {}
}
</script>

<style scoped>
.toggle-btn-wrapper .toggle-btn:first-child {
  border-top-left-radius: 0.375rem /* 6px */;
  border-bottom-left-radius: 0.375rem /* 6px */;
}
.toggle-btn-wrapper .toggle-btn:last-child {
  border-top-right-radius: 0.375rem /* 6px */;
  border-bottom-right-radius: 0.375rem /* 6px */;
}
.toggle-btn-wrapper .toggle-btn:first-child::before {
  border-top-left-radius: 0.375rem /* 6px */;
  border-bottom-left-radius: 0.375rem /* 6px */;
}
.toggle-btn-wrapper .toggle-btn:last-child::before {
  border-top-right-radius: 0.375rem /* 6px */;
  border-bottom-right-radius: 0.375rem /* 6px */;
}

.toggle-btn-wrapper .toggle-btn:not(:first-child) {
  margin-left: -1px;
}
.toggle-btn {
  background-color: rgb(var(--color-bg-toggle));
  color: rgb(var(--color-fg) / 0.5);
}
.toggle-btn.selected {
  background-color: rgb(var(--color-bg-toggle-selected));
  color: rgb(var(--color-fg));
}
</style>