<template>
  <div :style="contentPaddingStyle">
    <bookshelf-lazy-bookshelf page="series" />
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
