<template>
  <bookshelf-lazy-bookshelf page="books" />
</template>

<script>
export default {
  asyncData({ store, params, query }) {
    // Set filter by
    if (query.filter) {
      store.dispatch('user/updateUserSettings', { mobileFilterBy: query.filter })
    }
  }
}
</script>
