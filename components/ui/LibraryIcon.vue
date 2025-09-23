<template>
  <div :class="`h-${size} w-${size} min-w-${size} text-${fontSize}`" class="flex items-center justify-center">
    <span class="abs-icons transition-colors duration-200" :class="[`icon-${iconToUse}`, colorClass]" :style="dynamicColor"></span>
  </div>
</template>

<script>
export default {
  props: {
    icon: {
      type: String,
      default: 'audiobookshelf'
    },
    fontSize: {
      type: String,
      default: 'lg'
    },
    size: {
      type: Number,
      default: 5
    },
    color: {
      type: String,
      default: 'on-surface-variant'
    }
  },
  data() {
    return {}
  },
  computed: {
    iconToUse() {
      return this.icons.includes(this.icon) ? this.icon : 'audiobookshelf'
    },
    colorClass() {
      return `text-${this.color}`
    },
    dynamicColor() {
      // Ensure Material You colors are applied with proper CSS custom properties
      const colorMap = {
        'on-primary-container': 'rgb(var(--md-sys-color-on-primary-container))',
        'on-secondary-container': 'rgb(var(--md-sys-color-on-secondary-container))',
        'on-surface': 'rgb(var(--md-sys-color-on-surface))',
        'on-surface-variant': 'rgb(var(--md-sys-color-on-surface-variant))',
        primary: 'rgb(var(--md-sys-color-primary))',
        secondary: 'rgb(var(--md-sys-color-secondary))',
        tertiary: 'rgb(var(--md-sys-color-tertiary))'
      }

      return colorMap[this.color] ? { color: colorMap[this.color] } : {}
    },
    icons() {
      return this.$store.state.globals.libraryIcons
    }
  },
  methods: {},
  mounted() {}
}
</script>
