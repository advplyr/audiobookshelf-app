<template>
  <div class="tomesonic-splash-icon" :style="{ width: size + 'px', height: size + 'px' }">
    <ui-tomesonic-app-icon :size="size" :color="iconColor" class="tomesonic-icon" />
  </div>
</template>

<script>
export default {
  name: 'TomesonicSplashIcon',
  props: {
    size: {
      type: Number,
      default: 120
    },
    color: {
      type: String,
      default: 'on-surface'
    }
  },
  computed: {
    iconColor() {
      return this.color
    }
  }
}
</script>

<style scoped>
.tomesonic-splash-icon {
  display: inline-block;
}
</style>
