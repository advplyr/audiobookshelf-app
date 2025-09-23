<template>
  <span
    :style="{
      width: actualSize + 'px',
      height: actualSize + 'px',
      display: 'inline-block',
      maskImage: `url(${foregroundIconPath})`,
      WebkitMaskImage: `url(${foregroundIconPath})`,
      maskSize: 'contain',
      WebkitMaskSize: 'contain',
      maskRepeat: 'no-repeat',
      WebkitMaskRepeat: 'no-repeat',
      maskPosition: 'center',
      WebkitMaskPosition: 'center',
      backgroundColor: 'currentColor'
    }"
    :class="[`text-${color}`, 'tomesonic-app-icon']"
  />
</template><script>
export default {
  name: 'TomesonicAppIcon',
  props: {
    size: {
      type: Number,
      default: 32
    },
    color: {
      type: String,
      default: 'on-surface-variant'
    }
  },
  computed: {
    foregroundIconPath() {
      // Try direct path first
      return '/ic_launcher_foreground.png'
    },
    actualSize() {
      // Double the size from previous (was 0.85x, now 1.7x)
      // For 32px prop, render at ~54px to be more prominent
      return Math.round(this.size * 1.7)
    }
  }
}
</script>

<style scoped>
.tomesonic-app-icon {
  display: inline-block;
}
</style>
