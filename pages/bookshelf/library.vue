<template>
  <bookshelf-lazy-bookshelf page="books" />
</template>

<script>
export default {
  async asyncData({ store, params, query }) {
    // Set filter by
    if (query.filter) {
      store.commit('user/setSettings', { mobileFilterBy: query.filter })
      await store.dispatch('user/updateUserSettings', { mobileFilterBy: query.filter })
    }
  }
}
</script>
