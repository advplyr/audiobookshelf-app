<template>
  <div :style="contentPaddingStyle">
    <bookshelf-lazy-bookshelf page="collections" />
  </div>
</template>

<script>
export default {
  computed: {
    contentPaddingStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { paddingBottom: '120px' } : {}
    }
  }
}
</script>
