<template>
  <div :style="contentPaddingStyle">
    <bookshelf-lazy-bookshelf page="books" />
  </div>
</template>

<script>
export default {
  async asyncData({ store, params, query }) {
    // Set filter by
    if (query.filter) {
      await store.dispatch('user/updateUserSettings', { mobileFilterBy: query.filter })
    }
  },
  computed: {
    contentPaddingStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { paddingBottom: '120px' } : {}
    }
  }
}
</script>
